package com.moshanghuakai.exhibition.screen;

import static org.assertj.core.api.Assertions.*;
// 仅导入 HTTP 客户端断言实际使用的方法，避免与 MockMvc 的 content/jsonPath 静态方法冲突。
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.ExceptionHandlerAdvice;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 通过真实 HTTP 模拟管理 API 和 WHEP，验证共享拉流、独立观看与脱敏边界。 @author MoShangHuaKai */
class LiveStreamServiceTest {
  private static final String OFFER = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n";
  private static final String ANSWER =
      "v=0\r\nm=video 8000 UDP/TLS/RTP/SAVPF 96\r\na=ice-ufrag:test\r\na=fingerprint:sha-256 AA:BB\r\n";
  private HttpServer media;
  private ExhibitionProperties properties;
  private LiveStreamService service;
  private String stream;
  private boolean online = true;
  private boolean apiFailure;
  private boolean badAnswer;
  private String unsafeLocation;
  private int codec;
  private final AtomicInteger starts = new AtomicInteger();
  private final AtomicInteger stops = new AtomicInteger();
  private final AtomicInteger negotiations = new AtomicInteger();
  private final AtomicInteger releases = new AtomicInteger();
  private final List<Throwable> serverErrors = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    properties = new ExhibitionProperties();
    properties.getLive().setEnabled(true);
    properties.getLive().setRtspUrl("rtsp://camera-user:camera-password@127.0.0.1/live/0/MAIN");
    properties.getLive().setZlmSecret("private-media-secret");
    properties.getLive().setZlmSecretFile("./target/test-missing-media-secret.txt");
    properties.getLive().setStartupTimeoutSeconds(1);
    media = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    media.createContext(
        "/",
        exchange -> {
          try {
            handle(exchange);
          } catch (Throwable error) {
            serverErrors.add(error);
            exchange.close();
          }
        });
    media.start();
    properties.getLive().setZlmBaseUrl("http://127.0.0.1:" + media.getAddress().getPort());
    service = new LiveStreamService(properties);
  }

  @AfterEach
  void cleanup() {
    service.close();
    media.stop(0);
    assertThat(serverErrors).isEmpty();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/whep")) {
      assertThat(exchange.getRequestMethod()).isEqualTo("POST");
      assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
          .isEqualTo("application/sdp");
      assertThat(new String(read(exchange.getRequestBody()), StandardCharsets.UTF_8))
          .isEqualTo(OFFER);
      assertThat(exchange.getRequestURI().getQuery())
          .contains("stream=" + stream)
          .doesNotContain("secret");
      int number = negotiations.incrementAndGet();
      exchange
          .getResponseHeaders()
          .set(
              "Location",
              unsafeLocation != null
                  ? unsafeLocation
                  : properties.getLive().getZlmBaseUrl()
                      + "/index/api/delete_webrtc?id="
                      + number
                      + "&token=private-viewer-token");
      exchange.getResponseHeaders().set("Content-Type", "application/sdp");
      respond(exchange, 201, badAnswer ? "invalid" : ANSWER);
      return;
    }
    if (path.endsWith("/delete_webrtc")) {
      assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
      assertThat(exchange.getRequestURI().getQuery()).contains("token=private-viewer-token");
      releases.incrementAndGet();
      respond(exchange, 200, "success");
      return;
    }
    Map<String, String> form = new HashMap<>();
    for (String pair :
        new String(read(exchange.getRequestBody()), StandardCharsets.UTF_8).split("&")) {
      String[] parts = pair.split("=", 2);
      form.put(
          URLDecoder.decode(parts[0], "UTF-8"),
          parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "");
    }
    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    assertThat(exchange.getRequestURI().getQuery()).isNull();
    assertThat(form.get("secret")).isEqualTo("private-media-secret");
    String json = "{\"code\":0,\"data\":{\"flag\":true}}";
    if (apiFailure) json = "{\"code\":-1,\"msg\":\"camera-password private-media-secret\"}";
    else if (path.endsWith("addStreamProxy")) {
      starts.incrementAndGet();
      stream = form.get("stream");
      assertThat(form.get("url")).isEqualTo(properties.getLive().getRtspUrl());
      assertThat(form)
          .containsEntry("enable_rtsp", "1")
          .containsEntry("enable_hls", "0")
          .containsEntry("enable_audio", "0")
          .containsEntry("enable_mp4", "0");
      json = "{\"code\":0,\"data\":{\"key\":\"__defaultVhost__/exhibition/" + stream + "\"}}";
    } else if (path.endsWith("getMediaList")) {
      assertThat(form).containsEntry("schema", "rtsp");
      json =
          online
              ? "{\"code\":0,\"data\":[{\"stream\":\""
                  + stream
                  + "\",\"tracks\":[{\"codec_type\":0,\"codec_id\":"
                  + codec
                  + ",\"ready\":true,\"frames\":100}]}]}"
              : "{\"code\":0,\"data\":[]}";
    } else if (path.endsWith("delStreamProxy")) stops.incrementAndGet();
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    respond(exchange, 200, json);
  }

  private void respond(HttpExchange exchange, int status, String value) throws IOException {
    byte[] body = value.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private byte[] read(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;
    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
    return output.toByteArray();
  }

  @Test
  void simultaneousViewersShareOneProxyWithIndependentSessions() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) results.add(pool.submit(service::open));
      Set<String> sessions = new HashSet<>();
      for (Future<String> result : results) sessions.add(result.get(10, TimeUnit.SECONDS));
      assertThat(sessions).hasSize(8);
      assertThat(starts.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sameOriginEndpointsNegotiateAndReleaseWithoutExposingTokens() throws Exception {
    MockMvc mvc = mvc();
    String response =
        mvc.perform(get("/display/api/screen/live").contextPath("/display"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.protocol").value("webrtc"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("camera-password", "private-media-secret", "127.0.0.1");
    String url = new ObjectMapper().readTree(response).path("data").path("playUrl").asText();
    assertThat(url).startsWith("/display/api/screen/live/").endsWith("/offer");
    mvc.perform(post(url).contextPath("/display").contentType("application/sdp").content(OFFER))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/sdp"))
        .andExpect(content().string(ANSWER))
        .andExpect(header().doesNotExist("Location"));
    String base = url.substring(0, url.length() - "offer".length());
    mvc.perform(post(base + "heartbeat").contextPath("/display")).andExpect(status().isOk());
    mvc.perform(post(base + "close").contextPath("/display")).andExpect(status().isOk());
    mvc.perform(post(url).contextPath("/display").contentType("application/sdp").content(OFFER))
        .andExpect(status().isNotFound());
    assertThat(releases.get()).isEqualTo(1);
  }

  @Test
  void closingOneViewerDoesNotAffectAnother() {
    String first = service.open(), second = service.open();
    service.negotiate(first, OFFER);
    service.negotiate(second, OFFER);
    service.leave(first);
    service.leave(first);
    service.heartbeat(second);
    assertThat(releases.get()).isEqualTo(1);
    assertThat(stops.get()).isZero();
    assertStatus(HttpStatus.CONFLICT, () -> service.negotiate(second, OFFER));
  }

  @Test
  void invalidSdpAndUnsafeReleaseAddressAreRejected() {
    String session = service.open();
    assertStatus(
        HttpStatus.BAD_REQUEST, () -> service.negotiate(session, "v=0\r\nm=audio 9\r\na=recvonly"));
    assertStatus(
        HttpStatus.BAD_REQUEST, () -> service.negotiate(session, OFFER + "a=sendrecv\r\n"));
    assertThat(negotiations.get()).isZero();
    unsafeLocation = "http://untrusted.invalid/index/api/delete_webrtc?id=1&token=x";
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.negotiate(session, OFFER));
    assertThat(releases.get()).isZero();
    assertStatus(HttpStatus.NOT_FOUND, () -> service.heartbeat(session));
  }

  @Test
  void invalidAnswerReleasesAllocatedUpstreamConnection() {
    String session = service.open();
    badAnswer = true;
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.negotiate(session, OFFER));
    assertThat(releases.get()).isEqualTo(1);
  }

  @Test
  void idleLeaseAndShutdownReleaseConnectionsAndInvalidateSessions() {
    String session = service.open();
    service.negotiate(session, OFFER);
    Map<?, ?> viewers = (Map<?, ?>) ReflectionTestUtils.getField(service, "viewers");
    ReflectionTestUtils.setField(
        viewers.get(session), "touched", System.nanoTime() - TimeUnit.MINUTES.toNanos(2));
    ReflectionTestUtils.setField(
        service, "lastAccess", System.nanoTime() - TimeUnit.MINUTES.toNanos(2));
    service.leave("unknown"); // 未知关闭请求不能刷新空闲时间。
    service.releaseIdle();
    assertThat(stops.get()).isEqualTo(1);
    assertThat(releases.get()).isEqualTo(1);
    assertStatus(HttpStatus.NOT_FOUND, () -> service.heartbeat(session));
    assertThat(service.open()).isNotEqualTo(session);
    service.close();
    assertThat(stops.get()).isEqualTo(2);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, service::open);
  }

  @Test
  void offlineOrStalledStreamInvalidatesExistingSessions() {
    String session = service.open();
    service.negotiate(session, OFFER);
    online = false;
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.heartbeat(session));
    online = true;
    String next = service.open();
    assertThat(next).isNotEqualTo(session);
    assertStatus(HttpStatus.NOT_FOUND, () -> service.heartbeat(session));
    ReflectionTestUtils.setField(
        service, "lastFrameChange", System.nanoTime() - TimeUnit.SECONDS.toNanos(16));
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.negotiate(next, OFFER));
  }

  @Test
  void wrongCodecTimesOutAndReleasesProxy() {
    codec = 1;
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, service::open);
    assertThat(stops.get()).isEqualTo(1);
  }

  @Test
  void disabledAndInvalidConfigurationDoNotCreateProxy() {
    properties.getLive().setEnabled(false);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, service::open);
    properties.getLive().setEnabled(true);
    properties.getLive().setZlmSecret("");
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, service::open);
    assertThat(starts.get()).isZero();
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void connectionFailureSkipsDeletionAndReconnectsAfterMediaRecovers(CapturedOutput output) {
    RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "client");
    ClientHttpRequestFactory factory = client.getRequestFactory();
    try {
      // 三种异常均发生在连接建立前；只允许一次启动请求，意外删除会导致模拟服务校验失败。
      for (IOException failure :
          Arrays.asList(
              new ConnectException("camera-password private-media-secret"),
              new NoRouteToHostException("camera-password private-media-secret"),
              new UnknownHostException("camera-password private-media-secret"))) {
        MockRestServiceServer server = MockRestServiceServer.createServer(client);
        server
            .expect(requestTo(properties.getLive().getZlmBaseUrl() + "/index/api/addStreamProxy"))
            .andRespond(withException(failure));
        assertThatThrownBy(service::open)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("请先启动 ZLMediaKit 并检查管理端口");
        server.verify();
        assertThat(output).contains(failure.getClass().getSimpleName());
      }
      assertThat(output)
          .contains(properties.getLive().getZlmBaseUrl())
          .doesNotContain("媒体流释放失败", "camera-password", "private-media-secret");
    } finally {
      client.setRequestFactory(factory);
    }
    // 恢复真实 HTTP 后立即可重新拉流，失败状态不能影响播放器的下一次自动重连。
    assertThat(service.open()).isNotBlank();
    assertThat(starts.get()).isEqualTo(1);
  }

  @Test
  void readTimeoutStillReleasesPossiblyCreatedProxy() {
    RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service, "client");
    ClientHttpRequestFactory factory = client.getRequestFactory();
    try {
      // 读取超时无法判断远端是否已经创建代理，必须保留原有的补偿删除流程。
      MockRestServiceServer server = MockRestServiceServer.createServer(client);
      server
          .expect(requestTo(properties.getLive().getZlmBaseUrl() + "/index/api/addStreamProxy"))
          .andRespond(withException(new SocketTimeoutException("Read timed out")));
      server
          .expect(requestTo(properties.getLive().getZlmBaseUrl() + "/index/api/delStreamProxy"))
          .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
      assertStatus(HttpStatus.SERVICE_UNAVAILABLE, service::open);
      server.verify();
    } finally {
      client.setRequestFactory(factory);
    }
  }

  @Test
  void loadsSecretFileWhenIdeaStartsWithoutEnvironmentVariable(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path file = directory.resolve("media-secret.txt");
    Files.write(file, "private-media-secret\n".getBytes(StandardCharsets.UTF_8));
    properties.getLive().setZlmSecret("");
    properties.getLive().setZlmSecretFile(file.toString());
    assertThat(service.open()).isNotBlank();
    service.close();
    Files.write(file, new byte[0]);
    service = new LiveStreamService(properties);
    assertThatThrownBy(service::open).hasMessageContaining("密钥文件为空");
    assertThat(starts.get()).isEqualTo(1);
  }

  @Test
  void upstreamErrorsAreSanitized() throws Exception {
    apiFailure = true;
    String body =
        mvc()
            .perform(get("/api/screen/live"))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("camera-password", "private-media-secret", "rtsp://");
  }

  @Test
  void playerResourcesAreServedAndOldResourceRouteIsGone() throws Exception {
    mvc()
        .perform(get("/api/screen/live/player"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ExhibitionLivePlayer")));
    mvc()
        .perform(get("/api/screen/live/player.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("RTCPeerConnection")));
    mvc().perform(get("/api/screen/live/old/index.m3u8")).andExpect(status().isNotFound());
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new LiveStreamController(service))
        .setControllerAdvice(new ExceptionHandlerAdvice())
        .build();
  }

  private void assertStatus(HttpStatus status, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatus()).isEqualTo(status));
  }
}
