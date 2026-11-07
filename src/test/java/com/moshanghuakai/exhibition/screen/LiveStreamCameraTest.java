package com.moshanghuakai.exhibition.screen;

import static org.assertj.core.api.Assertions.*;

import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式提供设备配置后验证真实 H.264 拉流；提供浏览器 offer 文件时同时验证 WHEP。 @author MoShangHuaKai */
@EnabledIfEnvironmentVariable(named = "TEST_LIVE_RTSP", matches = ".+")
class LiveStreamCameraTest {
  @Test
  void cameraProvidesH264AndOptionalRealWebRtcAnswer() throws Exception {
    // 独立测试必须关闭 HTTP 表单调试，避免实体凭据进入测试报告。
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.springframework.web.client.RestTemplate.class);
    ch.qos.logback.classic.Level previous = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    ExhibitionProperties properties = new ExhibitionProperties();
    properties.getLive().setEnabled(true);
    properties.getLive().setRtspUrl(System.getenv("TEST_LIVE_RTSP"));
    properties.getLive().setZlmBaseUrl(System.getenv("TEST_ZLM_URL"));
    properties.getLive().setZlmSecret(System.getenv("TEST_ZLM_SECRET"));
    LiveStreamService service = new LiveStreamService(properties);
    try {
      String session = service.open(); // 内部检查实际 H.264 轨道和视频帧。
      assertThat(session).isNotBlank();
      String offerFile = System.getenv("TEST_RTC_OFFER_FILE");
      if (offerFile != null && !offerFile.trim().isEmpty()) {
        String offer = new String(Files.readAllBytes(Paths.get(offerFile)), StandardCharsets.UTF_8);
        assertThat(service.negotiate(session, offer)).contains("a=ice-ufrag:", "a=fingerprint:");
        service.heartbeat(session);
      }
      service.leave(session);
      // SDP 验证不代表浏览器已经解码；实际画面和延迟必须在播放页单独验收。
    } finally {
      service.close();
      logger.setLevel(previous);
    }
  }
}
