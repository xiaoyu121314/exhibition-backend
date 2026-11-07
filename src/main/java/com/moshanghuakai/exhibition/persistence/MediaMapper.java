package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.Media;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 文件元信息与引用检查，物理删除只针对已解除引用的文件。 @author MoShangHuaKai */
public interface MediaMapper {
  @Insert(
      "INSERT INTO exh_media(id,relative_path,content_type,owner_type,created_at) VALUES(#{id},#{relativePath},#{contentType},#{ownerType},#{createdAt})")
  void insert(Media media);

  @Select("SELECT * FROM exh_media WHERE id=#{id}")
  Media find(String id);

  @Delete("DELETE FROM exh_media WHERE id=#{id}")
  void delete(String id);

  @Select(
      "SELECT m.* FROM exh_media m WHERE m.created_at < #{before} AND NOT EXISTS(SELECT 1 FROM exh_staff s WHERE s.avatar_id=m.id AND s.deleted=FALSE) AND NOT EXISTS(SELECT 1 FROM exh_capture_event e WHERE e.image_id=m.id OR e.background_id=m.id OR e.recognize_image_id=m.id OR e.staff_avatar_id=m.id) AND NOT EXISTS(SELECT 1 FROM exh_staff_import_task j WHERE j.file_id=m.id) LIMIT 200")
  List<Media> unused(LocalDateTime before);
}
