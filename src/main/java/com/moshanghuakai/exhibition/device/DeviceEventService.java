package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.Device;
import com.moshanghuakai.exhibition.domain.Models.Capture;
import com.moshanghuakai.exhibition.persistence.EventMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化成功后才应答设备；幂等键同时包含事件时间、图内标记及图片摘要，允许设备重启复用编号。 @author MoShangHuaKai */
@Service
public class DeviceEventService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  // 与厂商心跳 ACK 示例保持一致：北京时间、精确到秒、紧凑日期及小时偏移，如 20261103T131155+08。
  private static final DateTimeFormatter ACK_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX");
  private final EventMapper mapper;
  private final StorageService storage;
  private final ExhibitionProperties properties;
  private final RecognitionService recognition;
  private final FaceFeatureService faceFeatures;

  public DeviceEventService(
      EventMapper mapper, StorageService storage, ExhibitionProperties properties,
      RecognitionService recognition, FaceFeatureService faceFeatures) {
    this.mapper = mapper;
    this.storage = storage;
    this.properties = properties;
    this.recognition = recognition;
    this.faceFeatures = faceFeatures;
  }

  @Transactional
  public Map<String, Object> receive(Device device, JsonNode root) {
    String operator = text(root, "operator", 64);
    JsonNode deviceInfo = root.path("deviceInfo"), info = root.path("info");
    if (!device.getSerialNumber().equals(text(deviceInfo, "serialNumber", 128)))
      throw new BusinessException("报文设备序列号与配置设备不一致");
    String eventId = text(info, "eventId", 64);
    LocalDateTime eventTime = parseTime(text(info, "time", 64));
    if (eventTime.isAfter(LocalDateTime.now(ZONE).plusMinutes(5)))
      throw new BusinessException("事件时间在未来，请校准摄像头时间");
    if ("heartbeat".equals(operator)) {
      mapper.heartbeat(device.getSerialNumber(), LocalDateTime.now(ZONE));
      Map<String, Object> strategy = new LinkedHashMap<>();
      strategy.put("heartBeatInterval", properties.getHeartbeatSeconds());
      strategy.put("passengerStaticsInterval", 1);
      strategy.put("isSnapshotEnable", true);
      for (String key :
          Arrays.asList(

              "isPassengerFlowStaticsEnable",
              "isPeopleNumberStatisticsEventEnable",
              "isEnableElectronicDefence",
              "isCrossBorderDetectEnable",
              "isOffDutyDetectEnable",
              "isCryScreamDetectEnable",
              "isPetDetectEnable",
              "isFallDetectEnable",
              "isPersonDetectEnable",
              "isCarLicenseSnapshotEnable",
              "isCarDetectEnable",
              "isTimedSnapshotEnable",
              "isGroundLockStatusChangedEnable",
              "isStartupReportEnable",
              "isAbnormalEventEnable",
              "isNonWhitelistCarEnable",
              "isMotionDetectEnable",
              "isTrafficStatisticsEnable",
              "isTrafficStatisticsCarShapeEnable",
              "isKey2CallEnable",
              "isLicensePlateSurveillanceEnable",
              "isFireDetectEventEnable",
              "isVideoCoverEventEnable",
              "isElectricBikeEventEnable",
              "isGasContainerEventEnable",
              "isElectronicDefenceV2EventEnable",
              "isPeopleNumberOverLimitEventEnable")) strategy.put(key, false);
      strategy.put("isPersonInfoEnable", recognition.uploadEnabled(device.getSerialNumber()));
      Map<String, Object> ackInfo = new LinkedHashMap<>();
      ackInfo.put("eventId", info.get("eventId"));
      ackInfo.put("time", OffsetDateTime.now(ZONE).format(ACK_TIME));
      ackInfo.put("eventSendMode", "realTime");
      ackInfo.put("strategy", strategy);
      return ack("heartbeat", ackInfo);
    }
    if (!"FaceSnapshot".equals(operator) && !"FaceReco".equals(operator))
      throw new BusinessException("仅支持 heartbeat、FaceSnapshot 和 FaceReco 事件");
    byte[] capture = picture(info.path("CaptureImage"));
    String trackId = info.path("trackId").asText("");
    if (trackId.length() > 64) throw new BusinessException("trackId 过长");
    String identity = device.getSerialNumber() + "|" + operator + "|" + eventId + "|" + eventTime
        + "|" + trackId + "|" + DeviceAuthentication.hex("SHA-256", capture)
        + ("FaceReco".equals(operator) ? "|" + candidateIdentity(info) : "");
    String key = DeviceAuthentication.hex("SHA-256", identity.getBytes(StandardCharsets.UTF_8));
    Capture existing = mapper.byKey(key);
    if (existing != null) {
      if ("FaceSnapshot".equals(operator)) return ack("FaceSnapshot", Collections.singletonMap("eventID", info.get("eventId")));
      Map<String, Object> response = recognition.recognitionAck(existing);
      if (response != null && existing.getAckJson() != null) mapper.saveAck(existing);
      return response;
    }
    Capture event = new Capture();
    event.setId(Ids.next()); event.setEventKey(key); event.setDeviceSn(device.getSerialNumber());
    event.setEventId(eventId); event.setTrackId(trackId); event.setEventType(operator);
    event.setHallId(properties.getHallId()); event.setEventTime(eventTime);
    event.setBusinessDate(eventTime.toLocalDate()); event.setReceivedAt(DeviceIdentityService.now());
    event.setResolveAttempts(0); event.setPictureMd5(DeviceAuthentication.hex("MD5", capture));
    event.setProtocolJson(recognition.encode(withoutPictures(root)));
    boolean expired = eventTime.isBefore(DeviceIdentityService.now().minusDays(properties.getRetentionDays()));
    event.setStatus(expired ? "EXPIRED" : "RECEIVED");
    if (!expired) {
      event.setImageId(storage.saveImage(capture, "CAPTURE").getId());
      event.setBackgroundId(optionalImage(info.path("BackgroundImage"), "BACKGROUND"));
      if ("FaceReco".equals(operator)) event.setRecognizeImageId(optionalImage(info.path("recognizeImage"), "RECOGNIZE"));
    }
    recognition.classify(event, info);
    Map<String, Object> response = "FaceReco".equals(operator) ? recognition.recognitionAck(event)
        : ack("FaceSnapshot", Collections.singletonMap("eventID", info.get("eventId")));
    if (response != null) event.setAckJson(recognition.encode(response));
    mapper.insert(event);
    faceFeatures.enqueue(event);
    // 事务代理在返回控制器之前提交；ACK未验收时返回null，控制器再返回503，样本不会回滚。
    return response;
  }

  private String optionalImage(JsonNode node, String type) {
    // 真机会以仅含 Data URL 前缀的 picture 表示没有库图；按实际载荷判空，不保存空图片。
    return pictureBase64(node).isEmpty() ? null : storage.saveImage(picture(node), type).getId();
  }

  /** 删除所有层级picture载荷，保留候选、质量和摘要；避免Base64进入列表或业务日志。 */
  private JsonNode withoutPictures(JsonNode node) {
    JsonNode copy = node.deepCopy();
    stripPictures(copy); return copy;
  }
  private void stripPictures(JsonNode node) {
    if (node.isObject()) {
      com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
      object.remove("picture");
    }
    if (node.isContainerNode()) for (JsonNode child : node) stripPictures(child);
  }

  /** 按固定字段顺序编码候选身份，JSON字段顺序改变不产生新的协议事件。 */
  private String candidateIdentity(JsonNode info) {
    List<Map<String, String>> candidates = new ArrayList<>();
    JsonNode person = info.path("personInfo");
    Iterable<JsonNode> values = person.isArray() ? person : Collections.singletonList(person);
    for (JsonNode value : values) {
      Map<String, String> fields = new TreeMap<>();
      for (String name : Arrays.asList("personId", "uniqueId", "serial", "personType", "groupId"))
        fields.put(name, value.path(name).asText(""));
      candidates.add(fields);
    }
    return info.path("matchNumber").asText("") + "|" + recognition.encode(candidates);
  }

  /** 统一提取图片载荷；现场固件使用 image/jpg，和 image/jpeg 一样按 JPEG 解码。 */
  private String pictureBase64(JsonNode node) {
    String value = node.path("picture").asText("");
    if (value.length() > 1024 * 1024 + 128) throw new BusinessException("设备单张图片 Base64 超过1MB");
    if (value.startsWith("data:")) {
      int separator = value.indexOf(',');
      if (separator < 0 || !value.substring(0, separator).matches("data:image/(jpeg|jpg|png);base64"))
        throw new BusinessException("图片 Data URL 格式无效");
      value = value.substring(separator + 1);
    }
    return value;
  }

  public byte[] picture(JsonNode node) {
    String value = pictureBase64(node);
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new BusinessException("设备图片 Base64 无效");
    }
    if (bytes.length == 0 || bytes.length > 768 * 1024) throw new BusinessException("设备图片大小无效");
    if (node.has("pictureMd5")
        && !node.path("pictureMd5").asText().isEmpty()
        && !DeviceAuthentication.hex("MD5", bytes)
            .equalsIgnoreCase(node.path("pictureMd5").asText()))
      throw new BusinessException("设备图片 MD5 校验失败");
    return bytes;
  }

  private Map<String, Object> ack(String operator, Map<String, ?> info) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operator", operator + "-Ack");
    result.put("info", info);
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("errorNo", 0);
    status.put("description", "ok");
    result.put("result", status);
    return result;
  }

  private String text(JsonNode node, String field, int max) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isValueNode()
        || value.asText().isEmpty()
        || value.asText().length() > max) throw new BusinessException("缺少或无效字段：" + field);
    return value.asText();
  }
  /** 仅支持协议中已展示的时间形式，无时区值按部署约定的北京时间解释。 */
  public static LocalDateTime parseTime(String value) {
    for (DateTimeFormatter f :
        Arrays.asList(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX")
                .withResolverStyle(ResolverStyle.STRICT))) {
      try {
        return OffsetDateTime.parse(value, f).atZoneSameInstant(ZONE).toLocalDateTime();
      } catch (DateTimeParseException ignored) {
      }
    }
    for (DateTimeFormatter f :
        Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withResolverStyle(ResolverStyle.STRICT))) {
      try {
        return LocalDateTime.parse(value, f);
      } catch (DateTimeParseException ignored) {
      }
    }
    throw new BusinessException("事件时间格式不支持，请核对实际设备报文");
  }
}
