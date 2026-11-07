package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.Recognition;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 设备算法结果的本地分类和历史身份关联；不运行人脸模型、不在回调中请求设备。 @author MoShangHuaKai */
@Slf4j
@Service
public class RecognitionService {
  private final ExhibitionProperties properties;
  private final EventMapper events;
  private final IdentityMapper identities;
  private final StaffMapper staff;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final FaceFeatureMapper faceFeatures;

  public RecognitionService(ExhibitionProperties properties, EventMapper events, IdentityMapper identities,
      StaffMapper staff, ObjectMapper json, TransactionTemplate transaction, FaceFeatureMapper faceFeatures) {
    this.properties = properties; this.events = events; this.identities = identities;
    this.staff = staff; this.json = json; this.transaction = transaction;
    this.faceFeatures = faceFeatures;
  }

  public String targetSn() {
    List<ExhibitionProperties.Device> targets = new ArrayList<>();
    for (ExhibitionProperties.Device device : properties.getDevices())
      if (device.getBaseUrl() != null && !device.getBaseUrl().trim().isEmpty()) targets.add(device);
    if (targets.size() != 1) throw new BusinessException("识别要求且仅允许一台工作人员管理设备");
    return targets.get(0).getSerialNumber();
  }

  public void validateContract() {
    Recognition config = properties.getRecognition();
    if (!config.isProtocolVerified() || !config.isAckVerified() || config.getChannelId() < 1
        || !Arrays.asList("personId", "uniqueId").contains(config.getIdentityField())
        || !Arrays.asList("FACE_UUID", "PERSON_ID", "UNIQUE_ID").contains(config.getIdentitySource())
        || !Arrays.asList("personType", "groupId").contains(config.getGroupField())
        || config.getGroupValue() == null || config.getGroupValue().trim().isEmpty()
        || config.getMinSimilarity() == null || config.getMinSimilarity().compareTo(BigDecimal.ZERO) <= 0
        || config.getMinSimilarity().compareTo(new BigDecimal("100")) > 0
        || !Arrays.asList("STRING", "INTEGER").contains(config.getAckPersonIdType()))
      throw new BusinessException("识别契约未完整验收：请配置身份来源、脸库作用域、阈值及ACK类型");
    if (config.getGroupValue().length() > 128) throw new BusinessException("识别脸库值过长");
    if ("INTEGER".equals(config.getAckPersonIdType())) {
      try { new java.math.BigInteger(config.getUnmatchedPersonId()); }
      catch (Exception e) { throw new BusinessException("未匹配人员的ACK personId必须符合已确认的整数契约"); }
    }
  }

  /** 首次启用时间先持久化再对设备开放上报，重启只读取原值。基线必须事先显式核实。 */
  @EventListener(ApplicationReadyEvent.class)
  public void activate() {
    if (!properties.getRecognition().isEnabled()) return;
    validateContract();
    String sn = targetSn();
    for (StaffDeviceBinding binding : staff.bindings()) {
      String source = properties.getRecognition().getIdentitySource();
      String expected = "FACE_UUID".equals(source) ? binding.getFaceUuid()
          : "PERSON_ID".equals(source) ? binding.getDevicePersonId() : binding.getDeviceUniqueId();
      List<DeviceIdentity> active = identities.active(binding.getStaffId());
      // 绑定和历史必须描述同一个唯一身份，避免错误基线在重启时被默许。
      boolean historyMatches = active.size() == 1
          && Objects.equals(active.get(0).getDeviceSn(), binding.getDeviceSerialNumber())
          && Objects.equals(active.get(0).getFaceGroupId(), binding.getCallbackGroup())
          && Objects.equals(active.get(0).getIdentityType(), binding.getIdentityType())
          && Objects.equals(active.get(0).getIdentityValue(), binding.getIdentityValue());
      if (!sn.equals(binding.getDeviceSerialNumber()) || binding.getIdentityValue() == null
          || !Objects.equals(binding.getIdentityValue(), expected)
          || !Objects.equals(binding.getIdentityType(), properties.getRecognition().getIdentityField())
          || !Objects.equals(binding.getCallbackGroup(), properties.getRecognition().getGroupValue())
          || !historyMatches)
        throw new BusinessException("请先关闭识别并执行设备身份基线核实，再启用识别");
    }
    events.startRecognition(sn, DeviceIdentityService.now());
  }

  public boolean enabled() { return properties.getRecognition().isEnabled(); }
  public boolean uploadEnabled(String sn) {
    return enabled() && targetSn().equals(sn) && events.recognitionStarted(sn) != null;
  }

  public String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception e) { throw new BusinessException("设备事件序列化失败"); }
  }
  public JsonNode decode(String value) {
    try { return json.readTree(value); }
    catch (Exception e) { throw new BusinessException("设备事件持久化内容无效"); }
  }

  private void state(Capture event, String state, String reason) {
    event.setMatchStatus(state); event.setMatchReason(reason);
    event.setProcessedAt(DeviceIdentityService.now());
  }

  /** 候选结构按对象或数组读取；数量矛盾保留REVIEW，不猜最高分候选。 */
  public void classify(Capture event, JsonNode info) {
    event.setNextResolveAt(null);
    if (!"FaceReco".equals(event.getEventType())) {
      state(event, "NOT_COMPARED", "普通抓拍没有设备比对结果"); return;
    }
    state(event, "REVIEW", "识别尚未启用或协议未验收");
    if (!enabled() || !properties.getRecognition().isProtocolVerified() || "EXPIRED".equals(event.getStatus())) return;
    validateContract();
    if (!targetSn().equals(event.getDeviceSn())) { state(event, "REVIEW", "非工作人员目标设备"); return; }
    JsonNode count = info.path("matchNumber"), person = info.path("personInfo");
    if (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 0) {
      state(event, "REVIEW", "候选数量缺失或无效"); return;
    }
    event.setMatchNumber(count.asInt());
    if (count.asInt() == 0) {
      if (person.isMissingNode() || person.isNull() || person.isContainerNode() && person.size() == 0
          || person.isTextual() && person.asText().isEmpty()) state(event, "NO_MATCH", "未匹配工作人员库");
      else state(event, "REVIEW", "未匹配却包含人员候选");
      return;
    }
    if (count.asInt() != 1 || person.isArray() && person.size() != 1) {
      state(event, "REVIEW", "多候选或候选数量不一致"); return;
    }
    if (person.isArray()) person = person.get(0);
    if (!person.isObject() || person.size() == 0) { state(event, "REVIEW", "缺少人员候选"); return; }
    Recognition config = properties.getRecognition();
    String id = scalar(person, config.getIdentityField());
    String group = scalar(person, config.getGroupField());
    event.setDevicePersonId(scalar(person, "personId")); event.setDeviceUniqueId(scalar(person, "uniqueId"));
    event.setIdentityType(config.getIdentityField()); event.setIdentityValue(id); event.setCallbackGroup(group);
    try {
      BigDecimal score = new BigDecimal(person.path("match").asText());
      if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0)
        throw new NumberFormatException();
      event.setSimilarity(score.setScale(6, java.math.RoundingMode.HALF_UP));
      if (score.compareTo(config.getMinSimilarity()) < 0) { state(event, "REVIEW", "匹配分数低于已核实阈值"); return; }
    } catch (NumberFormatException e) { state(event, "REVIEW", "匹配分数缺失或超出0至100"); return; }
    if (!config.getGroupValue().equals(group)) { state(event, "REVIEW", "非已核实的工作人员脸库"); return; }
    if (id == null || id.isEmpty()) { state(event, "REVIEW", "缺少已验证的设备身份字段"); return; }
    if (event.getEventTime().isAfter(event.getReceivedAt())) {
      state(event, "REVIEW", "事件时间在接收时间之后，请校准设备时间"); return;
    }
    List<DeviceIdentity> matches = identities.at(event.getDeviceSn(), group, config.getIdentityField(), id, event.getEventTime());
    if (matches.size() != 1) {
      state(event, "UNRESOLVED", matches.isEmpty() ? "没有事件发生时有效的身份映射" : "身份有效区间存在冲突");
      if (event.getResolveAttempts() < 3) event.setNextResolveAt(DeviceIdentityService.now().plusSeconds(30));
      return;
    }
    Staff personRecord = staff.find(matches.get(0).getStaffId());
    if (personRecord == null) { state(event, "UNRESOLVED", "身份对应档案缺失"); return; }
    // 使用事件时间匹配历史，不按当前停用或逻辑删除状态过滤；首次成功后不再次覆盖快照。
    event.setStaffId(personRecord.getId()); event.setStaffNo(personRecord.getStaffNo());
    event.setStaffName(personRecord.getName()); event.setStaffPosition(personRecord.getPosition());
    event.setStaffDepartment(personRecord.getDepartment()); event.setStaffAvatarId(personRecord.getAvatarId());
    state(event, "STAFF", "唯一有效的工作人员设备身份");
  }

  public static String scalar(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isValueNode() || value.isNull()) return null;
    String text = value.asText();
    return text.length() <= 128 ? text : null;
  }

  /** 未核实ACK时返回null，由控制器在事务提交后返回503，保证样本已保存但不伪造成功。 */
  public Map<String, Object> recognitionAck(Capture event) {
    if (event.getAckJson() != null) return json.convertValue(decode(event.getAckJson()),
        new TypeReference<Map<String, Object>>() {});
    if (!properties.getRecognition().isAckVerified()) return null;
    JsonNode info = decode(event.getProtocolJson()).path("info"), person = info.path("personInfo");
    if (person.isArray()) person = person.size() == 0 ? json.createObjectNode() : person.get(0);
    String personId = scalar(person, "personId"), uniqueId = scalar(person, "uniqueId");
    Recognition config = properties.getRecognition();
    Map<String, Object> ackInfo = new LinkedHashMap<>();
    personId = personId == null || personId.isEmpty() ? config.getUnmatchedPersonId() : personId;
    Object ackId = personId;
    if ("INTEGER".equals(config.getAckPersonIdType())) {
      try { ackId = new java.math.BigInteger(personId); }
      catch (Exception e) { return null; }
    } else if (!"STRING".equals(config.getAckPersonIdType())) return null;
    ackInfo.put("personId", ackId);
    ackInfo.put("uniqueId", uniqueId == null ? config.getUnmatchedUniqueId() : uniqueId);
    ackInfo.put("pictureMd5", event.getPictureMd5());
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("operator", "FaceReco-Ack"); ack.put("info", ackInfo);
    Map<String, Object> result = new LinkedHashMap<>(); result.put("errorNo", 0); result.put("description", "ok");
    ack.put("result", result); event.setAckJson(encode(ack));
    return ack;
  }

  /** 行锁覆盖状态检查、次数更新和分类；计划任务与人工重试不会相互覆盖。 */
  public Capture resolve(String id, boolean automatic) {
    return transaction.execute(status -> {
      Capture event = events.lock(id);
      if (event == null) throw new BusinessException("抓拍事件不存在或已到期");
      if (!"UNRESOLVED".equals(event.getMatchStatus()) || !"RECEIVED".equals(event.getStatus()) || !enabled()) return event;
      if (automatic && (event.getResolveAttempts() >= 3 || event.getNextResolveAt() == null
          || event.getNextResolveAt().isAfter(DeviceIdentityService.now()))) return event;
      event.setResolveAttempts(automatic ? event.getResolveAttempts() + 1 : 0);
      classify(event, decode(event.getProtocolJson()).path("info"));
      events.resolved(event);
      return event;
    });
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void retryDue() {
    if (!properties.isJobsEnabled() || !enabled()) return;
    for (String id : events.due(DeviceIdentityService.now())) {
      try { resolve(id, true); }
      catch (Exception e) { log.warn("识别身份重试失败，事件ID={}，留待下轮", id); }
    }
  }

  /** 列表和大屏只输出必要字段；协议及ACK快照仅在管理详情出现，不输出Base64。 */
  public Map<String, Object> view(Capture event, boolean detail) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", event.getId()); result.put("eventType", event.getEventType());
    result.put("deviceSn", event.getDeviceSn()); result.put("eventId", event.getEventId());
    result.put("businessDate", event.getBusinessDate()); result.put("eventTime", DeviceIdentityService.offset(event.getEventTime()));
    result.put("receivedAt", DeviceIdentityService.offset(event.getReceivedAt()));
    result.put("status", event.getStatus()); result.put("matchStatus", event.getMatchStatus());
    result.put("matchReason", event.getMatchReason()); result.put("matchNumber", event.getMatchNumber());
    result.put("similarity", event.getSimilarity()); result.put("imageId", event.getImageId());
    result.put("recognizeImageId", event.getRecognizeImageId()); result.put("backgroundId", event.getBackgroundId());
    result.put("staff", staffView(event));
    if (detail) {
      result.put("protocol", event.getProtocolJson() == null ? null : decode(event.getProtocolJson()));
      result.put("ack", event.getAckJson() == null ? null : decode(event.getAckJson()));
      result.put("resolveAttempts", event.getResolveAttempts());
      result.put("nextResolveAt", DeviceIdentityService.offset(event.getNextResolveAt()));
      result.put("processedAt", DeviceIdentityService.offset(event.getProcessedAt()));
      if (properties.getFaceFeature().isEnabled()) {
        FaceFeatureEvent feature = faceFeatures.event(event.getId());
        boolean current = feature != null && properties.getFaceFeature().getModelId().equals(feature.getModelId());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("modelId", properties.getFaceFeature().getModelId());
        status.put("status", current ? feature.getStatus() : "PENDING");
        status.put("attempts", current ? feature.getAttempts() : 0);
        status.put("error", current ? feature.getError() : null);
        status.put("nextRetryAt", current ? DeviceIdentityService.offset(feature.getNextRetryAt()) : null);
        result.put("faceFeature", status);
      }
    }
    return result;
  }

  public Map<String, Object> staffView(Capture event) {
    if (event.getStaffId() == null) return null;
    Map<String, Object> result = new LinkedHashMap<>(); result.put("id", event.getStaffId());
    result.put("staffNo", event.getStaffNo()); result.put("name", event.getStaffName());
    result.put("position", event.getStaffPosition()); result.put("department", event.getStaffDepartment());
    result.put("avatarId", event.getStaffAvatarId()); return result;
  }
}
