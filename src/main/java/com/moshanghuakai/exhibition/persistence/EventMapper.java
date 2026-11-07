package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.Capture;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 事件接收、身份重试及日去重共用事实表，不维护容易漂移的累加计数。 @author MoShangHuaKai */
public interface EventMapper {
  @Insert("INSERT INTO exh_capture_event(id,event_key,device_sn,event_id,track_id,event_time,received_at,business_date,image_id,background_id,status,hall_id,event_type,match_status,match_reason,match_number,similarity,device_person_id,device_unique_id,callback_group,identity_type,identity_value,staff_id,staff_no,staff_name,staff_position,staff_department,staff_avatar_id,recognize_image_id,picture_md5,protocol_json,ack_json,resolve_attempts,next_resolve_at,processed_at) VALUES(#{id},#{eventKey},#{deviceSn},#{eventId},#{trackId},#{eventTime},#{receivedAt},#{businessDate},#{imageId},#{backgroundId},#{status},#{hallId},#{eventType},#{matchStatus},#{matchReason},#{matchNumber},#{similarity},#{devicePersonId},#{deviceUniqueId},#{callbackGroup},#{identityType},#{identityValue},#{staffId},#{staffNo},#{staffName},#{staffPosition},#{staffDepartment},#{staffAvatarId},#{recognizeImageId},#{pictureMd5},#{protocolJson},#{ackJson},#{resolveAttempts},#{nextResolveAt},#{processedAt})")
  void insert(Capture capture);

  @Select("SELECT * FROM exh_capture_event WHERE event_key=#{key}")
  Capture byKey(String key);
  @Select("SELECT * FROM exh_capture_event WHERE id=#{id}")
  Capture find(String id);
  @Select("SELECT * FROM exh_capture_event WHERE id=#{id} FOR UPDATE")
  Capture lock(String id);

  @Select({"<script>SELECT * FROM exh_capture_event WHERE 1=1",
    "<if test='date != null'>AND business_date=#{date}</if><if test='deviceSn != null'>AND device_sn=#{deviceSn}</if><if test='status != null'>AND status=#{status}</if>",
    "<if test='eventType != null'>AND event_type=#{eventType}</if><if test='matchStatus != null'>AND match_status=#{matchStatus}</if><if test='staffId != null'>AND staff_id=#{staffId}</if>",
    "ORDER BY received_at DESC,id LIMIT #{limit} OFFSET #{offset}</script>"})
  List<Capture> query(@Param("date") LocalDate date, @Param("deviceSn") String deviceSn,
      @Param("status") String status, @Param("eventType") String eventType,
      @Param("matchStatus") String matchStatus, @Param("staffId") String staffId,
      @Param("offset") int offset, @Param("limit") int limit);

  default List<Capture> list(LocalDate date, String deviceSn, String status, int offset, int limit) {
    return query(date, deviceSn, status, null, null, null, offset, limit);
  }

  @Update("UPDATE exh_capture_event SET match_status=#{matchStatus},match_reason=#{matchReason},similarity=#{similarity},identity_type=#{identityType},identity_value=#{identityValue},callback_group=#{callbackGroup},staff_id=#{staffId},staff_no=#{staffNo},staff_name=#{staffName},staff_position=#{staffPosition},staff_department=#{staffDepartment},staff_avatar_id=#{staffAvatarId},processed_at=#{processedAt},resolve_attempts=#{resolveAttempts},next_resolve_at=#{nextResolveAt} WHERE id=#{id}")
  void resolved(Capture event);
  @Update("UPDATE exh_capture_event SET ack_json=#{ackJson} WHERE id=#{id} AND ack_json IS NULL")
  void saveAck(Capture event);

  @Select("<script>SELECT id FROM exh_capture_event WHERE status='RECEIVED' AND match_status='UNRESOLVED' AND next_resolve_at &lt;= #{now} ORDER BY next_resolve_at,id LIMIT 200</script>")
  @Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
  List<String> due(LocalDateTime now);

  // 身份提交时只唤醒这个设备、脸库、字段及身份的未解决事件，不回扫所有记录。
  @Update("UPDATE exh_capture_event SET resolve_attempts=0,next_resolve_at=#{now} WHERE device_sn=#{deviceSn} AND callback_group=#{faceGroupId} AND identity_type=#{identityType} AND identity_value=#{identityValue} AND match_status='UNRESOLVED' AND status='RECEIVED'")
  void wakeIdentity(@Param("deviceSn") String deviceSn, @Param("faceGroupId") String faceGroupId,
      @Param("identityType") String identityType, @Param("identityValue") String identityValue,
      @Param("now") LocalDateTime now);

  String VALID = " FROM exh_capture_event WHERE hall_id=#{hall} AND business_date=#{date} AND event_type='FaceReco' AND status='RECEIVED' AND event_time >= #{started}";
  @Select("SELECT COUNT(DISTINCT staff_id)" + VALID + " AND match_status='STAFF'")
  long staffCount(@Param("hall") String hall, @Param("date") LocalDate date, @Param("started") LocalDateTime started);
  @Select("SELECT COUNT(*)" + VALID + " AND match_status=#{state}")
  long stateCount(@Param("hall") String hall, @Param("date") LocalDate date,
      @Param("started") LocalDateTime started, @Param("state") String state);
  @Select("SELECT MAX(processed_at)" + VALID)
  LocalDateTime statisticsUpdated(@Param("hall") String hall, @Param("date") LocalDate date, @Param("started") LocalDateTime started);
  @Select("SELECT *" + VALID + " AND match_status='STAFF' AND event_time <= #{now} AND event_time > #{after} ORDER BY event_time DESC,received_at DESC,id DESC LIMIT 1")
  Capture latest(@Param("hall") String hall, @Param("date") LocalDate date,
      @Param("started") LocalDateTime started, @Param("now") LocalDateTime now, @Param("after") LocalDateTime after);

  @Insert("INSERT INTO exh_device(serial_number,last_heartbeat) VALUES(#{sn},#{time}) ON DUPLICATE KEY UPDATE last_heartbeat=VALUES(last_heartbeat)")
  void heartbeat(@Param("sn") String sn, @Param("time") LocalDateTime time);
  @Select("SELECT MAX(last_heartbeat) FROM exh_device")
  LocalDateTime lastHeartbeat();
  @Select("SELECT last_heartbeat FROM exh_device WHERE serial_number=#{sn}")
  LocalDateTime deviceHeartbeat(String sn);
  @Insert("INSERT INTO exh_device(serial_number,recognition_started_at) VALUES(#{sn},#{time}) ON DUPLICATE KEY UPDATE recognition_started_at=COALESCE(recognition_started_at,VALUES(recognition_started_at))")
  void startRecognition(@Param("sn") String sn, @Param("time") LocalDateTime time);
  @Select("SELECT recognition_started_at FROM exh_device WHERE serial_number=#{sn}")
  LocalDateTime recognitionStarted(String sn);
  @Select("SELECT MAX(event_time) FROM exh_capture_event")
  LocalDateTime lastEventTime();
  @Delete("DELETE FROM exh_capture_event WHERE received_at < #{before}")
  void purge(LocalDateTime before);
}
