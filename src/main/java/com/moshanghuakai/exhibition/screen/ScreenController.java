package com.moshanghuakai.exhibition.screen;

import com.moshanghuakai.exhibition.common.ApiResult;
import com.moshanghuakai.exhibition.device.*;
import com.moshanghuakai.exhibition.domain.Models.Capture;
import com.moshanghuakai.exhibition.config.*;
import com.moshanghuakai.exhibition.persistence.EventMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 大屏只读接口：展示文案独立获取；人数无可用来源时明确返回 null。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/screen")
@Tag(name = "04 展厅大屏", description = "展示文案及摄像头状态，无需访问令牌")
public class ScreenController {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final DisplayConfiguration display;
  private final ExhibitionProperties properties;
  private final EventMapper events;
  private final RecognitionService recognition;
  private final FaceFeatureService faceFeatures;

  public ScreenController(
      DisplayConfiguration display, ExhibitionProperties properties, EventMapper events,
      RecognitionService recognition, FaceFeatureService faceFeatures) {
    this.display = display;
    this.properties = properties;
    this.events = events;
    this.recognition = recognition;
    this.faceFeatures = faceFeatures;
  }

  @GetMapping("/config")
  @Operation(
      summary = "获取展厅名称和欢迎语",
      description = "从外部 exhibition.display-file 指定的 YAML 读取，默认 JAR 同级 config/exhibition-display.yml；每 5 秒热更新，读取失败保留最后有效配置。hallName 为展厅名称，welcomeMessage 为欢迎语。")
  public ApiResult<Map<String, String>> config() {
    return ApiResult.ok(display.get());
  }

  @GetMapping("/overview")
  @Operation(summary = "获取今日工作人员去重及最新识别人员",
      description = "建议每2秒轮询。本机特征服务启用后，totalCount和guestCount始终返回已完成的当日去重结果，totalCount包含工作人员；单张抓拍失败不清空人数。personCountStatus=AVAILABLE表示任务全部完成，PROCESSING表示仍有待处理任务，PARTIAL表示失败样本尚未计入、可能漏计；仅关闭识别或未启用服务时人数为null。staffCount按不同staffId统计。latestStaff仅在事件发生10秒内返回，时间带+08:00；设备离线保留已有统计。")
  public ApiResult<Map<String, Object>> overview() {
    LocalDateTime now = DeviceIdentityService.now();
    String sn = recognition.targetSn();
    LocalDateTime heartbeat = events.deviceHeartbeat(sn);
    LocalDateTime started = events.recognitionStarted(sn);
    boolean enabled = recognition.enabled() && started != null;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("businessDate", now.toLocalDate()); result.put("timezone", "Asia/Shanghai");
    result.put("generatedAt", DeviceIdentityService.offset(now));
    result.put("countMode", enabled ? faceFeatures.enabled() ? "PEOPLE_DAILY_DISTINCT" : "STAFF_DAILY_DISTINCT" : "NONE");
    result.put("recognitionStatus", enabled ? "ENABLED" : "DISABLED");
    result.put("staffCount", enabled ? events.staffCount(properties.getHallId(), now.toLocalDate(), started) : null);
    // 总参观人数包含工作人员；前端读取totalCount，不能将guestCount当作含工作人员的总数。
    result.putAll(faceFeatures.overview(sn, now, enabled));
    result.put("cameraOnline", heartbeat != null && heartbeat.isAfter(now.minusSeconds(properties.getHeartbeatSeconds() * 3L)));
    result.put("lastEventAt", DeviceIdentityService.offset(events.lastEventTime()));
    result.put("statisticsStartedAt", DeviceIdentityService.offset(started));
    result.put("statisticsUpdatedAt", enabled ? DeviceIdentityService.offset(events.statisticsUpdated(properties.getHallId(), now.toLocalDate(), started)) : null);
    result.put("unresolvedCount", enabled ? events.stateCount(properties.getHallId(), now.toLocalDate(), started, "UNRESOLVED") : null);
    result.put("reviewCount", enabled ? events.stateCount(properties.getHallId(), now.toLocalDate(), started, "REVIEW") : null);
    Capture latest = enabled ? events.latest(properties.getHallId(), now.toLocalDate(), started, now, now.minusSeconds(10)) : null;
    Map<String, Object> card = null;
    if (latest != null) {
      card = new LinkedHashMap<>(); card.put("captureId", latest.getId());
      card.put("eventTime", DeviceIdentityService.offset(latest.getEventTime()));
      card.put("displayUntil", DeviceIdentityService.offset(latest.getEventTime().plusSeconds(10)));
      card.put("similarity", latest.getSimilarity()); card.put("imageId", latest.getImageId());
      card.put("recognizeImageId", latest.getRecognizeImageId()); card.put("staff", recognition.staffView(latest));
    }
    result.put("latestStaff", card);
    return ApiResult.ok(result);
  }
}
