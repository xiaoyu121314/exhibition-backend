package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 人员及照片持久化；更新带版本条件，导入和单条维护共用相同约束。 @author MoShangHuaKai */
public interface StaffMapper {
  @Select("SELECT * FROM exh_staff WHERE id=#{id}")
  Staff find(String id);

  @Select("SELECT * FROM exh_staff WHERE staff_no=#{staffNo}")
  Staff findByNo(String staffNo);

  /** 按数值取最大工号，包含逻辑删除人员，防止工号复用；空表从0起算。 */
  @Select("SELECT COALESCE(MAX(CAST(staff_no AS DECIMAL(19,0))),0) FROM exh_staff")
  long maxStaffNo();

  /**
   * 一次查询同时返回头像的实际保存路径，避免列表逐人查询媒体表。
   * 媒体 ID 为主键，左关联不会增加人员行数；头像未关联或媒体缺失时仍保留人员并返回空路径。
   */
  @Select({
    "<script>SELECT s.*,m.relative_path AS avatar_url FROM exh_staff s",
    "LEFT JOIN exh_media m ON m.id=s.avatar_id WHERE s.deleted=FALSE",
    "<if test='staffNo != null and staffNo != &quot;&quot;'>AND staff_no LIKE CONCAT('%',#{staffNo},'%')</if>",
    "<if test='name != null and name != &quot;&quot;'>AND name LIKE CONCAT('%',#{name},'%')</if>",
    "<if test='department != null and department != &quot;&quot;'>AND department=#{department}</if>",
    "<if test='enabled != null'>AND enabled=#{enabled}</if>",
    "ORDER BY s.created_at DESC,s.id LIMIT #{limit} OFFSET #{offset}</script>"
  })
  List<Staff> list(
      @Param("staffNo") String staffNo,
      @Param("name") String name,
      @Param("department") String department,
      @Param("enabled") Boolean enabled,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select({
    "<script>SELECT COUNT(*) FROM exh_staff s WHERE deleted=FALSE",
    "<if test='staffNo != null and staffNo != &quot;&quot;'>AND staff_no LIKE CONCAT('%',#{staffNo},'%')</if>",
    "<if test='name != null and name != &quot;&quot;'>AND name LIKE CONCAT('%',#{name},'%')</if>",
    "<if test='department != null and department != &quot;&quot;'>AND department=#{department}</if>",
    "<if test='enabled != null'>AND enabled=#{enabled}</if></script>"
  })
  int count(
      @Param("staffNo") String staffNo,
      @Param("name") String name,
      @Param("department") String department,
      @Param("enabled") Boolean enabled);

  @Insert(
      "INSERT INTO exh_staff(id,staff_no,name,sex,position,department,enabled,deleted,avatar_id,version,created_at,updated_at) VALUES(#{id},#{staffNo},#{name},#{sex},#{position},#{department},#{enabled},FALSE,#{avatarId},0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
  void insert(Staff staff);

  @Update(
      "UPDATE exh_staff SET name=#{name},sex=#{sex},position=#{position},department=#{department},enabled=#{enabled},avatar_id=#{avatarId},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version} AND deleted=FALSE")
  int update(Staff staff);

  @Update(
      "UPDATE exh_staff SET deleted=TRUE,enabled=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND deleted=FALSE AND version=#{version}")
  int delete(@Param("id") String id, @Param("version") int version);

  @Select("SELECT * FROM exh_staff_device WHERE staff_id=#{id}")
  StaffDeviceBinding binding(String id);

  @Select("SELECT * FROM exh_staff_device ORDER BY staff_id")
  List<StaffDeviceBinding> bindings();

  @Insert("INSERT INTO exh_staff_device(staff_id,device_serial_number,face_group_id,face_uuid,numeric_group_id,device_person_id,device_unique_id,model_state,identity_type,identity_value,callback_group,verified_at,valid_from) VALUES(#{staffId},#{deviceSerialNumber},#{faceGroupId},#{faceUuid},#{numericGroupId},#{devicePersonId},#{deviceUniqueId},#{modelState},#{identityType},#{identityValue},#{callbackGroup},#{verifiedAt},#{validFrom})")
  void insertBinding(StaffDeviceBinding binding);

  @Delete("DELETE FROM exh_staff_device WHERE staff_id=#{id}")
  void deleteBinding(String id);

  // 包括已停用人员，避免把另一人的照片绑定到当前档案；已删除人员释放引用。
  @Select("SELECT COUNT(*) FROM exh_staff WHERE avatar_id=#{imageId} AND id<>#{staffId} AND deleted=FALSE")
  int imageOwners(@Param("imageId") String imageId, @Param("staffId") String staffId);

}
