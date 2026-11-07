package com.moshanghuakai.exhibition.device;

import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.domain.Models.Capture;
import com.moshanghuakai.exhibition.persistence.EventMapper;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 事件查询不暴露Base64；重试只做本地身份关联，不调用设备或改写已确认结果。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/admin/captures")
@Tag(name = "03 抓拍诊断", description = "抓拍及设备比对查询，无需访问令牌")
public class CaptureAdminController {
  private final EventMapper mapper;
  private final RecognitionService recognition;
  public CaptureAdminController(EventMapper mapper, RecognitionService recognition) {
    this.mapper = mapper; this.recognition = recognition;
  }

  @GetMapping
  @Operation(summary = "查询抓拍及比对记录", description = "data为记录数组，无总条数；按日期、设备、接收状态、事件类型、比对状态及staffId过滤。时间带+08:00，图片使用媒体接口。")
  public ApiResult<List<Map<String, Object>>> list(
      @Parameter(description = "北京时间业务日期yyyy-MM-dd") @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Parameter(description = "来源设备序列号") @RequestParam(required = false) String deviceSn,
      @Parameter(description = "RECEIVED或EXPIRED") @RequestParam(required = false) String status,
      @Parameter(description = "FaceSnapshot或FaceReco") @RequestParam(required = false) String eventType,
      @Parameter(description = "NOT_COMPARED、STAFF、NO_MATCH、UNRESOLVED、REVIEW") @RequestParam(required = false) String matchStatus,
      @Parameter(description = "本地工作人员ID") @RequestParam(required = false) String staffId,
      @Parameter(description = "页码1至1000000") @RequestParam(defaultValue = "1") int page,
      @Parameter(description = "每页1至100条") @RequestParam(defaultValue = "20") int size) {
    if (page < 1 || page > 1000000 || size < 1 || size > 100) throw new BusinessException("分页参数无效");
    validate(status, "RECEIVED", "EXPIRED"); validate(eventType, "FaceSnapshot", "FaceReco");
    validate(matchStatus, "NOT_COMPARED", "STAFF", "NO_MATCH", "UNRESOLVED", "REVIEW");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Capture event : mapper.query(date, deviceSn, status, eventType, matchStatus, staffId, (page - 1) * size, size))
      result.add(recognition.view(event, false));
    return ApiResult.ok(result);
  }

  private void validate(String value, String... allowed) {
    if (value != null && !Arrays.asList(allowed).contains(value)) throw new BusinessException("事件筛选枚举无效");
  }

  @GetMapping("/{id}")
  @Operation(summary = "获取比对事件详情", description = "包含候选协议、图片引用、ACK快照及重新关联状态，协议已移除picture载荷；不存在或过期已清理返回400。")
  public ApiResult<Map<String, Object>> detail(@Parameter(description = "后台事件ID") @PathVariable String id) {
    Capture event = mapper.find(id);
    if (event == null) throw new BusinessException("抓拍事件不存在或已到期");
    return ApiResult.ok(recognition.view(event, true));
  }

  @PostMapping("/{id}/resolve")
  @Operation(summary = "重新关联未解决的设备身份", description = "只对UNRESOLVED执行本地历史身份匹配；其他状态原样返回。无设备写入，已确认STAFF快照不覆盖。")
  public ApiResult<Map<String, Object>> resolve(@Parameter(description = "后台事件ID") @PathVariable String id) {
    return ApiResult.ok(recognition.view(recognition.resolve(id, false), true));
  }
}
