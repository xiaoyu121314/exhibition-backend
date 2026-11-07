package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.*;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 从抓拍事实表取异步任务，按独立的当日身份表查人数，不累加抓拍次数。 @author MoShangHuaKai */
public interface FaceFeatureMapper {
  String SOURCE = " FROM exh_capture_event e JOIN exh_device d ON d.serial_number=e.device_sn"
      + " LEFT JOIN exh_face_feature_event f ON f.capture_id=e.id"
      + " WHERE e.hall_id=#{hall} AND e.device_sn=#{sn} AND e.event_type='FaceReco'"
      + " AND e.status='RECEIVED' AND e.image_id IS NOT NULL AND e.event_time >= d.recognition_started_at"
      + " AND e.event_time <= #{now}";
  String INCOMPLETE = "(f.capture_id IS NULL OR f.model_id != #{model} OR f.status='PENDING'"
      + " OR (f.status='FAILED' AND f.attempts < #{attempts}))";

  @Select("SELECT e.*" + SOURCE + " AND " + INCOMPLETE
      + " AND (f.capture_id IS NULL OR f.model_id != #{model} OR f.next_retry_at IS NULL OR f.next_retry_at <= #{now})"
      + " ORDER BY e.received_at,e.id LIMIT #{limit}")
  List<Capture> candidates(@Param("hall") String hall, @Param("sn") String sn,
      @Param("model") String model, @Param("now") LocalDateTime now,
      @Param("attempts") int attempts, @Param("limit") int limit);

  @Select("SELECT e.*" + SOURCE
      + " AND f.model_id=#{model} AND (f.status='PENDING' OR (f.status='FAILED' AND f.attempts < #{attempts}))"
      + " AND (f.next_retry_at IS NULL OR f.next_retry_at <= #{now}) ORDER BY e.received_at,e.id LIMIT #{limit}")
  List<Capture> queued(@Param("hall") String hall, @Param("sn") String sn,
      @Param("model") String model, @Param("now") LocalDateTime now,
      @Param("attempts") int attempts, @Param("limit") int limit);

  @Insert("INSERT INTO exh_face_feature_event(capture_id,model_id,status,attempts,processed_at)"
      + " VALUES(#{id},#{model},'PENDING',0,#{now}) ON DUPLICATE KEY UPDATE capture_id=VALUES(capture_id)")
  void enqueue(@Param("id") String id, @Param("model") String model, @Param("now") LocalDateTime now);

  @Select("SELECT COUNT(*)" + SOURCE + " AND e.business_date=#{date} AND " + INCOMPLETE)
  long pending(@Param("hall") String hall, @Param("sn") String sn, @Param("date") LocalDate date,
      @Param("model") String model, @Param("now") LocalDateTime now, @Param("attempts") int attempts);

  @Select("SELECT COUNT(*)" + SOURCE + " AND e.business_date=#{date} AND f.model_id=#{model} AND f.status='FAILED' AND f.attempts >= #{attempts}")
  long failed(@Param("hall") String hall, @Param("sn") String sn, @Param("date") LocalDate date,
      @Param("model") String model, @Param("now") LocalDateTime now, @Param("attempts") int attempts);

  @Select("SELECT * FROM exh_face_feature_event WHERE capture_id=#{id}")
  FaceFeatureEvent event(String id);

  @Insert("INSERT INTO exh_face_feature_event(capture_id,model_id,person_id,status,error,attempts,next_retry_at,processed_at)"
      + " VALUES(#{captureId},#{modelId},#{personId},#{status},#{error},#{attempts},#{nextRetryAt},#{processedAt})"
      + " ON DUPLICATE KEY UPDATE model_id=VALUES(model_id),person_id=VALUES(person_id),status=VALUES(status),"
      + " error=VALUES(error),attempts=VALUES(attempts),next_retry_at=VALUES(next_retry_at),processed_at=VALUES(processed_at)")
  void saveEvent(FaceFeatureEvent event);

  @Select("SELECT * FROM exh_person_daily WHERE hall_id=#{hall} AND business_date=#{date} AND model_id=#{model} AND merged_into IS NULL ORDER BY first_seen_at,id")
  List<DailyPerson> people(@Param("hall") String hall, @Param("date") LocalDate date, @Param("model") String model);

  @Insert("INSERT INTO exh_person_daily(id,hall_id,business_date,model_id,staff_id,features_json,first_seen_at,last_seen_at)"
      + " VALUES(#{id},#{hallId},#{businessDate},#{modelId},#{staffId},#{featuresJson},#{firstSeenAt},#{lastSeenAt})")
  void insertPerson(DailyPerson person);

  @Update("UPDATE exh_person_daily SET staff_id=#{staffId},features_json=#{featuresJson},first_seen_at=#{firstSeenAt},last_seen_at=#{lastSeenAt} WHERE id=#{id} AND merged_into IS NULL")
  void updatePerson(DailyPerson person);

  // 归并保留原身份和事件指向，支持审计；计数只看未被归并的身份，工作人员确认后不再重复算访客。
  @Update("UPDATE exh_person_daily SET merged_into=#{target} WHERE id=#{source} AND staff_id IS NULL AND merged_into IS NULL")
  void merge(@Param("source") String source, @Param("target") String target);

  @Select("SELECT COUNT(*) AS total_count,COALESCE(SUM(CASE WHEN staff_id IS NULL THEN 1 ELSE 0 END),0) AS guest_count"
      + " FROM exh_person_daily WHERE hall_id=#{hall} AND business_date=#{date} AND model_id=#{model} AND merged_into IS NULL")
  DailyPeopleCount counts(@Param("hall") String hall, @Param("date") LocalDate date, @Param("model") String model);

  @Update("UPDATE exh_face_feature_event SET status='PENDING',attempts=0,next_retry_at=NULL,error=NULL"
      + " WHERE status='FAILED' AND capture_id IN(SELECT id FROM exh_capture_event WHERE hall_id=#{hall} AND device_sn=#{sn})")
  int retryFailed(@Param("hall") String hall, @Param("sn") String sn);

  @Delete("DELETE FROM exh_face_feature_event WHERE capture_id IN(SELECT id FROM exh_capture_event WHERE received_at < #{before})")
  void purgeEvents(LocalDateTime before);
  @Delete("DELETE FROM exh_person_daily WHERE last_seen_at < #{before}")
  void purgePeople(LocalDateTime before);
}
