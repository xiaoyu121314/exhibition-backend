package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.DeviceIdentity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 身份区间与人员维护事务一致；查询历史不按当前enabled/deleted过滤。 @author MoShangHuaKai */
public interface IdentityMapper {
  @Insert("INSERT INTO exh_device_identity(id,staff_id,device_sn,face_group_id,identity_type,identity_value,valid_from,valid_until) VALUES(#{id},#{staffId},#{deviceSn},#{faceGroupId},#{identityType},#{identityValue},#{validFrom},#{validUntil})")
  void insert(DeviceIdentity identity);
  @Select("SELECT * FROM exh_device_identity WHERE staff_id=#{staffId} AND valid_until IS NULL")
  List<DeviceIdentity> active(String staffId);
  @Update("UPDATE exh_device_identity SET valid_until=#{until} WHERE staff_id=#{staffId} AND valid_until IS NULL")
  void close(@Param("staffId") String staffId, @Param("until") LocalDateTime until);
  @Select({"<script>SELECT * FROM exh_device_identity WHERE device_sn=#{sn} AND face_group_id=#{group} AND identity_type=#{type} AND identity_value=#{value}",
      "AND valid_from &lt;= #{time} AND (valid_until IS NULL OR valid_until > #{time})</script>"})
  List<DeviceIdentity> at(@Param("sn") String sn, @Param("group") String group,
      @Param("type") String type, @Param("value") String value, @Param("time") LocalDateTime time);
  @Delete("DELETE FROM exh_device_identity WHERE valid_until < #{before}")
  void purge(LocalDateTime before);
}
