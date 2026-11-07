package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.FaceFeature;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 单实例异步人脸日去重：模型调用在事务外，身份归并和任务结果在同一事务提交。 @author MoShangHuaKai */
@Slf4j
@Service
public class FaceFeatureService {
  private final ExhibitionProperties properties;
  private final FaceFeatureMapper mapper;
  private final EventMapper events;
  private final StorageService storage;
  private final FaceFeatureClient client;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final ReentrantLock identityLock = new ReentrantLock(true);
  private LocalDateTime nextBackfillAt = LocalDateTime.MIN;

  public FaceFeatureService(ExhibitionProperties properties, FaceFeatureMapper mapper, EventMapper events,
      StorageService storage, FaceFeatureClient client, ObjectMapper json, TransactionTemplate transaction) {
    this.properties = properties; this.mapper = mapper; this.events = events; this.storage = storage;
    this.client = client; this.json = json; this.transaction = transaction;
  }

  public boolean enabled() { return properties.getFaceFeature().isEnabled(); }

  @EventListener(ApplicationReadyEvent.class)
  public void validate() { if (enabled()) client.validate(); }

  /** 已提交的FaceReco才参与队列；不在摄像头回调内运行模型，不影响厂商ACK时限。 */
  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  public void processDue() {
    if (!enabled() || !properties.isJobsEnabled() || !properties.getRecognition().isEnabled()) return;
    try {
      FaceFeature config = properties.getFaceFeature();
      String sn = targetSn();
      LocalDateTime now = DeviceIdentityService.now();
      boolean backfill = !now.isBefore(nextBackfillAt);
      List<Capture> candidates = backfill
          ? mapper.candidates(properties.getHallId(), sn, config.getModelId(), now, config.getMaxAttempts(), config.getBatchSize())
          : mapper.queued(properties.getHallId(), sn, config.getModelId(), now, config.getMaxAttempts(), config.getBatchSize());
      // 常态只查有索引的待处理队列；首次启用、重启或模型切换的缺失任务分批补入。
      // 保留每分钟补扫，覆盖启用前已保存、但设备时间略超前的报文。
      if (backfill && candidates.size() < config.getBatchSize()) nextBackfillAt = now.plusMinutes(1);
      for (Capture capture : candidates) process(capture);
    } catch (Exception error) {
      // 失败详情保存到独立任务；这里只输出固定信息，避免模型路径和人脸特征进入业务日志。
      log.warn("人员日去重任务暂未完成，请核对特征服务及统计状态");
    }
  }

  /** 与抓拍事实在同一事务中入队；不调用模型，重复事件也不重置已经成功的任务。 */
  public void enqueue(Capture capture) {
    if (!enabled() || !properties.getRecognition().isEnabled() || !"FaceReco".equals(capture.getEventType())
        || !"RECEIVED".equals(capture.getStatus()) || capture.getImageId() == null
        || !targetSn().equals(capture.getDeviceSn())) return;
    LocalDateTime started = events.recognitionStarted(capture.getDeviceSn());
    if (started != null && !capture.getEventTime().isBefore(started))
      mapper.enqueue(capture.getId(), properties.getFaceFeature().getModelId(), DeviceIdentityService.now());
  }

  private String targetSn() {
    List<String> targets = new ArrayList<>();
    for (ExhibitionProperties.Device device : properties.getDevices())
      if (device.getBaseUrl() != null && !device.getBaseUrl().trim().isEmpty()) targets.add(device.getSerialNumber());
    if (targets.size() != 1) throw new BusinessException("人员日去重要求且仅允许一台目标设备");
    return targets.get(0);
  }

  /** 公开为同包业务/集成验证复用；重入时再次检查READY状态，重复模型调用不会重复计人数。 */
  public void process(Capture capture) {
    if (!enabled() || !properties.getRecognition().isEnabled()) return;
    FaceFeature config = properties.getFaceFeature();
    FaceFeatureEvent previous = mapper.event(capture.getId());
    if (previous != null && config.getModelId().equals(previous.getModelId())
        && ("READY".equals(previous.getStatus()) || previous.getAttempts() >= config.getMaxAttempts())) return;
    try {
      double[] vector = client.extract(storage.read(capture.getImageId()));
      identityLock.lock();
      try { transaction.execute(status -> { commit(capture, vector); return null; }); }
      finally { identityLock.unlock(); }
    } catch (Exception error) {
      identityLock.lock();
      try {
        transaction.execute(status -> {
          FaceFeatureEvent saved = mapper.event(capture.getId());
          if (saved != null && config.getModelId().equals(saved.getModelId()) && "READY".equals(saved.getStatus())) return null;
          FaceFeatureEvent failed = result(capture, saved);
          boolean retryable = !(error instanceof FaceFeatureClient.ExtractionFailure)
              || ((FaceFeatureClient.ExtractionFailure) error).isRetryable();
          failed.setStatus("FAILED");
          failed.setError(error instanceof FaceFeatureClient.ExtractionFailure ? error.getMessage() : "FEATURE_PROCESSING_FAILED");
          failed.setAttempts(retryable ? failed.getAttempts() : config.getMaxAttempts());
          failed.setNextRetryAt(retryable ? DeviceIdentityService.now().plusSeconds(config.getRetrySeconds()) : null);
          mapper.saveEvent(failed); return null;
        });
      } finally { identityLock.unlock(); }
    }
  }

  private FaceFeatureEvent result(Capture capture, FaceFeatureEvent previous) {
    FaceFeatureEvent result = new FaceFeatureEvent();
    result.setCaptureId(capture.getId()); result.setModelId(properties.getFaceFeature().getModelId());
    result.setAttempts(previous != null && result.getModelId().equals(previous.getModelId()) ? previous.getAttempts() + 1 : 1);
    result.setProcessedAt(DeviceIdentityService.now()); return result;
  }

  private void commit(Capture capture, double[] vector) {
    FaceFeature config = properties.getFaceFeature();
    FaceFeatureEvent previous = mapper.event(capture.getId());
    if (previous != null && config.getModelId().equals(previous.getModelId()) && "READY".equals(previous.getStatus())) return;
    // 工作人员身份只接受设备已经确认的快照；不按姓名、工号或低分候选猜工作人员。
    String staffId = "STAFF".equals(capture.getMatchStatus()) ? capture.getStaffId() : null;
    DailyPerson own = null, best = null;
    double bestScore = -1;
    for (DailyPerson person : mapper.people(capture.getHallId(), capture.getBusinessDate(), config.getModelId())) {
      if (staffId != null && staffId.equals(person.getStaffId())) own = person;
      // 一个设备已确认的工作人员不能因模型相似而覆盖另一个工作人员；只允许归并匿名身份。
      if (staffId != null && person.getStaffId() != null) continue;
      double score = similarity(person, vector);
      if (score >= config.getMinSimilarity() && score > bestScore) { best = person; bestScore = score; }
    }
    DailyPerson person = own != null ? own : best;
    if (person == null) {
      person = new DailyPerson(); person.setId(Ids.next()); person.setHallId(capture.getHallId());
      person.setBusinessDate(capture.getBusinessDate()); person.setModelId(config.getModelId());
      person.setStaffId(staffId); person.setFirstSeenAt(capture.getEventTime()); person.setLastSeenAt(capture.getEventTime());
      person.setFeaturesJson(encode(Collections.singletonList(vector))); mapper.insertPerson(person);
    } else {
      if (staffId != null) person.setStaffId(staffId);
      List<double[]> templates = decode(person.getFeaturesJson());
      if (own != null && best != null && !own.getId().equals(best.getId())) {
        mapper.merge(best.getId(), own.getId());
        // 已被归并的匿名视角仍属于同一身份，保留少量模板，避免下一次相同视角又生成访客。
        for (double[] template : decode(best.getFeaturesJson())) if (templates.size() < 5) templates.add(template);
        if (best.getFirstSeenAt().isBefore(person.getFirstSeenAt())) person.setFirstSeenAt(best.getFirstSeenAt());
        if (best.getLastSeenAt().isAfter(person.getLastSeenAt())) person.setLastSeenAt(best.getLastSeenAt());
      }
      if (capture.getEventTime().isBefore(person.getFirstSeenAt())) person.setFirstSeenAt(capture.getEventTime());
      if (capture.getEventTime().isAfter(person.getLastSeenAt())) person.setLastSeenAt(capture.getEventTime());
      // 保留少量视角样本。匿名身份仅接纳更高置信度的补充样本，降低连续边缘匹配造成的漂移。
      if (templates.size() < 5 && similarity(person, vector) < 0.95
          && (staffId != null || bestScore >= 0.65)) templates.add(vector);
      person.setFeaturesJson(encode(templates)); mapper.updatePerson(person);
    }
    FaceFeatureEvent ready = result(capture, previous);
    ready.setStatus("READY"); ready.setPersonId(person.getId()); mapper.saveEvent(ready);
  }

  private double similarity(DailyPerson person, double[] vector) {
    double best = -1;
    for (double[] template : decode(person.getFeaturesJson())) {
      if (template.length != vector.length) throw new BusinessException("本地特征维度无效");
      double score = 0;
      for (int i = 0; i < vector.length; i++) score += template[i] * vector[i];
      best = Math.max(best, score);
    }
    return best;
  }

  private List<double[]> decode(String value) {
    try { return json.readValue(value, new TypeReference<List<double[]>>() {}); }
    catch (Exception error) { throw new BusinessException("本地特征记录无效"); }
  }
  private String encode(List<double[]> value) {
    try { return json.writeValueAsString(value); }
    catch (Exception error) { throw new BusinessException("本地特征保存失败"); }
  }

  /**
   * 启用后始终返回已经落库的去重结果；单张抓拍失败或新任务排队不会抹去已形成的人数。
   * PARTIAL表示有失败样本尚未计入，PROCESSING表示仍在处理；只有AVAILABLE表示当前任务全部完成。
   * 关闭识别或未启用特征服务时仍返回null，避免把未提供统计能力误表示成当天无人。
   */
  public Map<String, Object> overview(String sn, LocalDateTime now, boolean recognitionEnabled) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("personCountStatus", !recognitionEnabled ? "DISABLED" : !enabled() ? "UNAVAILABLE" : "PROCESSING");
    result.put("totalCount", null); result.put("guestCount", null);
    result.put("personCountPending", null); result.put("personCountFailed", null);
    if (!recognitionEnabled || !enabled()) return result;
    identityLock.lock();
    try {
      FaceFeature config = properties.getFaceFeature();
      long pending = mapper.pending(properties.getHallId(), sn, now.toLocalDate(), config.getModelId(), now, config.getMaxAttempts());
      long failed = mapper.failed(properties.getHallId(), sn, now.toLocalDate(), config.getModelId(), now, config.getMaxAttempts());
      result.put("personCountPending", pending); result.put("personCountFailed", failed);
      result.put("personCountStatus", failed > 0 ? "PARTIAL" : pending > 0 ? "PROCESSING" : "AVAILABLE");
      DailyPeopleCount counts = mapper.counts(properties.getHallId(), now.toLocalDate(), config.getModelId());
      result.put("totalCount", counts.getTotalCount());
      result.put("guestCount", counts.getGuestCount());
      return result;
    } finally { identityLock.unlock(); }
  }

  public int retryFailed() {
    if (!enabled()) throw new BusinessException("请先启用本机特征服务");
    identityLock.lock();
    try { return mapper.retryFailed(properties.getHallId(), targetSn()); }
    finally { identityLock.unlock(); }
  }
}
