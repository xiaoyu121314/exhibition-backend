package com.moshanghuakai.exhibition.screen;

import com.moshanghuakai.exhibition.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 同源 WebRTC 信令入口；摄像头凭据、管理密钥与远端释放令牌不返回浏览器。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/screen/live")
@Tag(name = "04 展厅大屏")
public class LiveStreamController {
  private final LiveStreamService service;

  public LiveStreamController(LiveStreamService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(
      summary = "创建 WebRTC 观看会话",
      description = "共享一路 H.264 原生拉流，返回独立会话的同源协商、保活和关闭入口。仅视频，无需前端密钥。")
  public ResponseEntity<ApiResult<Map<String, String>>> live(HttpServletRequest request) {
    String id = service.open();
    String base = request.getContextPath() + "/api/screen/live/" + id;
    Map<String, String> result = new LinkedHashMap<>();
    result.put("protocol", "webrtc");
    result.put("session", id);
    result.put("playUrl", base + "/offer");
    result.put("heartbeatUrl", base + "/heartbeat");
    result.put("closeUrl", base + "/close");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(result));
  }

  @PostMapping(
      value = "/{session}/offer",
      consumes = "application/sdp",
      produces = "application/sdp")
  @Operation(
      summary = "协商 WebRTC 视频",
      description = "提交 recvonly 视频 offer SDP，成功返回 answer SDP 原文。每个会话仅协商一次，视频直连媒体端口。")
  public ResponseEntity<String> offer(@PathVariable String session, @RequestBody String offer) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType("application/sdp"))
        .body(service.negotiate(session, offer));
  }

  @PostMapping("/{session}/heartbeat")
  @Operation(summary = "保活 WebRTC 观看会话", description = "已建立播放连接后每 15 秒调用一次；超过空闲时间未调用则释放该观看会话。")
  public ApiResult<Void> heartbeat(@PathVariable String session) {
    service.heartbeat(session);
    return ApiResult.ok(null);
  }

  @PostMapping("/{session}/close")
  @Operation(summary = "关闭 WebRTC 观看会话", description = "页面退出时调用，支持 sendBeacon；幂等释放当前观看者，不影响其他观看者。")
  public ApiResult<Void> close(@PathVariable String session) {
    service.leave(session);
    return ApiResult.ok(null);
  }

  @GetMapping(value = "/player", produces = MediaType.TEXT_HTML_VALUE)
  @Operation(summary = "打开 WebRTC 播放页", description = "内置零外部依赖的播放页，可直接访问或嵌入大屏；播放、重连、心跳和离开回收均已实现。")
  public ResponseEntity<Resource> player() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.TEXT_HTML)
        .body(new ClassPathResource("live/player.html"));
  }

  @GetMapping(value = "/player.js", produces = "application/javascript")
  @Operation(
      summary = "加载 WebRTC 播放组件",
      description = "提供 ExhibitionLivePlayer，可复用于现有大屏，不依赖第三方播放器。")
  public ResponseEntity<Resource> playerScript() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType("application/javascript"))
        .body(new ClassPathResource("live/player.js"));
  }
}
