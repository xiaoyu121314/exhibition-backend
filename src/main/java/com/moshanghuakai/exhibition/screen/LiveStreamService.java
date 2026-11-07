package com.moshanghuakai.exhibition.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.config.ApplicationPaths;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 多个 WebRTC 观看会话共享一路原生 RTSP 拉流；Java 只代理信令，视频由媒体服务直接发送。 每个观看者单独保存 WHEP 释放地址，过期或退出只释放本人的连接，不影响其他观看者。
 *
 * @author MoShangHuaKai
 */
@Slf4j
@Service
public class LiveStreamService {
  private final ExhibitionProperties.Live config;
  private final RestTemplate client;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, Viewer> viewers = new LinkedHashMap<>();
  private String stream;
  private String proxyKey;
  private String apiSecret;
  private long lastAccess;
  private long lastFrameCount = -1;
  private long lastFrameChange;
  private boolean closed;

  private static class Viewer {
    private long touched = System.nanoTime();
    private URI releaseUri;
  }

  public LiveStreamService(ExhibitionProperties properties) {
    config = properties.getLive();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(7000);
    client = new RestTemplate(factory);
  }

  /** 每次获取入口分配独立观看标识；只等待 H.264 轨道，不等待文件生成。 */
  public synchronized String open() {
    validate();
    if (closed) throw unavailable("实时预览服务正在关闭");
    pruneViewers();
    if (viewers.size() >= 32)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "实时观看会话已达上限");
    if (stream != null && !ready()) stop();
    if (stream == null) start();
    String id = UUID.randomUUID().toString();
    viewers.put(id, new Viewer());
    lastAccess = System.nanoTime();
    return id;
  }

  private void start() {
    stream = UUID.randomUUID().toString();
    proxyKey = "__defaultVhost__/exhibition/" + stream;
    boolean success = false;
    try {
      MultiValueMap<String, String> form = streamForm();
      form.add("url", config.getRtspUrl());
      form.add("rtp_type", "0");
      form.add("retry_count", "0");
      form.add("timeout_sec", "5");
      // WebRTC 从内部 RTSP 媒体源读取 RTP；无需开放 RTSP 服务端监听端口。
      form.add("enable_rtsp", "1");
      form.add("enable_hls", "0");
      form.add("enable_hls_fmp4", "0");
      form.add("enable_mp4", "0");
      form.add("enable_rtmp", "0");
      form.add("enable_ts", "0");
      form.add("enable_fmp4", "0");
      form.add("enable_audio", "0");
      form.add("add_mute_audio", "0");
      form.add("auto_close", "1");
      if (!proxyKey.equals(api("addStreamProxy", form).path("data").path("key").asText()))
        throw unavailable("媒体服务未返回有效拉流标识");
      long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(config.getStartupTimeoutSeconds());
      while (System.nanoTime() < deadline) {
        if (ready()) {
          success = true;
          return;
        }
        Thread.sleep(200);
      }
      throw unavailable("摄像头 H.264 视频未就绪，请检查设备连接与编码设置");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("视频启动等待已中断");
    } catch (ResourceAccessException e) {
      Throwable cause = e.getMostSpecificCause();
      // 连接根本未建立时，媒体服务不可能创建代理，无需再发送删除请求制造二次告警。
      // 读取超时等结果不确定的情况仍保留 key，由 finally 回收可能已经创建的远端代理。
      if (cause instanceof ConnectException
          || cause instanceof NoRouteToHostException
          || cause instanceof UnknownHostException) proxyKey = null;
      // 仅记录管理地址和根因类型，不打印可能包含 RTSP 凭据、管理密钥的原始异常。
      log.warn(
          "媒体服务访问失败，地址={}，原因={}，请检查 ZLMediaKit 进程及管理端口",
          config.getZlmBaseUrl(),
          cause.getClass().getSimpleName());
      throw unavailable("媒体服务访问失败，请先启动 ZLMediaKit 并检查管理端口");
    } catch (RestClientException e) {
      throw unavailable("媒体服务不可用，请检查 ZLMediaKit 及摄像头连接");
    } finally {
      if (!success) stop();
    }
  }

  /** 浏览器提交接收视频的 offer；上游会话地址仅保存于服务端，不接受客户端指定 URL。 */
  public synchronized String negotiate(String id, String offer) {
    Viewer viewer = requireViewer(id);
    if (offer == null
        || offer.length() > 65536
        || !offer.startsWith("v=0")
        || !offer.contains("m=video ")
        || !offer.contains("a=recvonly")
        || offer.contains("m=audio ")
        || offer.contains("m=application ")
        || offer.contains("a=sendrecv")
        || offer.contains("a=sendonly"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交仅接收视频的有效 SDP，最大 64KB");
    if (viewer.releaseUri != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该观看会话已协商，请重新获取播放入口");
    if (!ready()) {
      stop();
      throw unavailable("摄像头视频已中断，请重新连接");
    }
    URI endpoint =
        UriComponentsBuilder.fromHttpUrl(config.getZlmBaseUrl())
            .path("/index/api/whep")
            .queryParam("app", "exhibition")
            .queryParam("stream", stream)
            .queryParam("type", "play")
            .queryParam("vhost", "__defaultVhost__")
            .build()
            .toUri();
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.parseMediaType("application/sdp"));
      ResponseEntity<String> response =
          client.exchange(
              endpoint, HttpMethod.POST, new HttpEntity<>(offer, headers), String.class);
      // 先保存可安全释放的地址，即使 answer 无效也能回收已分配的远端会话。
      viewer.releaseUri = releaseUri(response.getHeaders().getLocation());
      String answer = response.getBody();
      if (response.getStatusCode() != HttpStatus.CREATED
          || answer == null
          || !answer.startsWith("v=0")
          || !answer.contains("m=video ")
          || !answer.contains("a=ice-ufrag:")
          || !answer.contains("a=fingerprint:")) throw unavailable("媒体服务返回了无效 WebRTC 协商结果");
      viewer.touched = lastAccess = System.nanoTime();
      return answer;
    } catch (RestClientException e) {
      viewers.remove(id);
      release(viewer);
      throw unavailable("WebRTC 协商失败，请检查媒体服务是否支持 WHEP 及设备编码");
    } catch (ResponseStatusException e) {
      viewers.remove(id);
      release(viewer);
      throw e;
    }
  }

  /** 观看连接建立后每 15 秒保活；不以 HTTP 分片请求判断 WebRTC 是否仍在观看。 */
  public synchronized void heartbeat(String id) {
    Viewer viewer = requireViewer(id);
    if (viewer.releaseUri == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成播放协商");
    if (!ready()) {
      stop();
      throw unavailable("摄像头视频已中断，请重新连接");
    }
    viewer.touched = lastAccess = System.nanoTime();
  }

  public synchronized void leave(String id) {
    Viewer viewer = viewers.remove(id);
    if (viewer != null) {
      release(viewer);
      if (viewers.isEmpty()) lastAccess = System.nanoTime();
    }
  }

  private Viewer requireViewer(String id) {
    pruneViewers();
    Viewer viewer = viewers.get(id);
    if (viewer == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "观看会话已过期，请重新获取播放入口");
    return viewer;
  }

  /** 上游只能返回本机 WHEP 释放入口，防止响应中的 Location 变成任意网络访问。 */
  private URI releaseUri(URI location) {
    URI origin = URI.create(config.getZlmBaseUrl());
    if (location == null) throw unavailable("媒体服务未返回 WebRTC 会话标识");
    URI uri = origin.resolve(location);
    if (!Objects.equals(origin.getScheme(), uri.getScheme())
        || !Objects.equals(origin.getAuthority(), uri.getAuthority())
        || !"/index/api/delete_webrtc".equals(uri.getPath())
        || uri.getFragment() != null
        || uri.getRawQuery() == null
        || uri.getRawQuery().length() > 2048) throw unavailable("媒体服务返回了无效 WebRTC 会话地址");
    MultiValueMap<String, String> query =
        UriComponentsBuilder.fromUri(uri).build().getQueryParams();
    if (!query.keySet().equals(new HashSet<>(Arrays.asList("id", "token")))
        || query.get("id").size() != 1
        || query.get("token").size() != 1
        || !StringUtils.hasText(query.getFirst("id"))
        || !StringUtils.hasText(query.getFirst("token")))
      throw unavailable("媒体服务返回了无效 WebRTC 会话标识");
    return uri;
  }

  private boolean ready() {
    if (stream == null) return false;
    try {
      MultiValueMap<String, String> form = streamForm();
      form.add("schema", "rtsp");
      for (JsonNode source : api("getMediaList", form).path("data")) {
        if (!stream.equals(source.path("stream").asText())) continue;
        for (JsonNode track : source.path("tracks")) {
          if (track.path("codec_type").asInt(-1) != 0
              || track.path("codec_id").asInt(-1) != 0
              || !track.path("ready").asBoolean()) continue;
          long frames = track.path("frames").asLong(-1);
          if (frames < 1) continue;
          if (frames != lastFrameCount) {
            lastFrameCount = frames;
            lastFrameChange = System.nanoTime();
          }
          return System.nanoTime() - lastFrameChange < TimeUnit.SECONDS.toNanos(15);
        }
      }
      return false;
    } catch (RestClientException | ResponseStatusException e) {
      return false;
    }
  }

  private MultiValueMap<String, String> streamForm() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("vhost", "__defaultVhost__");
    form.add("app", "exhibition");
    form.add("stream", stream);
    return form;
  }

  private JsonNode api(String operation, MultiValueMap<String, String> form) {
    form.add("secret", apiSecret);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    String body =
        client.postForObject(
            config.getZlmBaseUrl() + "/index/api/" + operation,
            new HttpEntity<>(form, headers),
            String.class);
    try {
      JsonNode result = mapper.readTree(body);
      if (result.path("code").asInt(-1) != 0) throw unavailable("媒体服务操作失败，请检查配置与设备状态");
      return result;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | NullPointerException e) {
      throw unavailable("媒体服务返回数据无效");
    }
  }

  private void release(Viewer viewer) {
    if (viewer.releaseUri == null) return;
    try {
      client.delete(viewer.releaseUri);
    } catch (HttpClientErrorException.NotFound ignored) {
      /* 浏览器已关闭，远端可能先释放。 */
    } catch (RestClientException e) {
      log.warn("WebRTC 会话释放失败，类型={}", e.getClass().getSimpleName());
    }
  }

  private void pruneViewers() {
    Iterator<Viewer> iterator = viewers.values().iterator();
    while (iterator.hasNext()) {
      Viewer viewer = iterator.next();
      if (System.nanoTime() - viewer.touched
          > TimeUnit.SECONDS.toNanos(config.getIdleTimeoutSeconds())) {
        iterator.remove();
        release(viewer);
      }
    }
  }

  @Scheduled(fixedDelay = 10000)
  public synchronized void releaseIdle() {
    pruneViewers();
    if (stream != null
        && viewers.isEmpty()
        && System.nanoTime() - lastAccess
            > TimeUnit.SECONDS.toNanos(config.getIdleTimeoutSeconds())) stop();
  }

  private void stop() {
    viewers.values().forEach(this::release);
    viewers.clear();
    if (proxyKey != null) {
      try {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", proxyKey);
        api("delStreamProxy", form);
      } catch (RuntimeException e) {
        log.warn("媒体流释放失败，类型={}", e.getClass().getSimpleName());
      }
    }
    stream = null;
    proxyKey = null;
    lastAccess = 0;
    lastFrameCount = -1;
    lastFrameChange = 0;
  }

  private void validate() {
    if (!config.isEnabled()) throw unavailable("摄像头实时预览未启用");
    try {
      URI rtsp = URI.create(config.getRtspUrl());
      if (!"rtsp".equalsIgnoreCase(rtsp.getScheme()) || rtsp.getHost() == null)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw unavailable("实时预览 RTSP 地址无效，请检查设备视频地址配置");
    }
    try {
      URI zlm = URI.create(config.getZlmBaseUrl());
      if (!"http".equalsIgnoreCase(zlm.getScheme())
          || zlm.getHost() == null
          || zlm.getUserInfo() != null
          || zlm.getQuery() != null
          || zlm.getFragment() != null
          || !"".equals(zlm.getPath())) throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw unavailable("ZLMediaKit 地址无效，应为 http://主机:端口，末尾不要加斜杠或路径");
    }
    if (config.getStartupTimeoutSeconds() < 1
        || config.getStartupTimeoutSeconds() > 60
        || config.getIdleTimeoutSeconds() < 10)
      throw unavailable("实时预览超时配置无效，启动等待应为 1–60 秒，空闲时间至少 10 秒");
    apiSecret = resolveSecret();
  }

  /**
   * 显式密钥优先；IDEA 直接启动时也能读取媒体脚本生成的文件，无需复制密钥到源码。 JAR 使用同级 data/media；IDE 使用项目
   * target/data/media，与媒体启动脚本保持一致。
   */
  private String resolveSecret() {
    if (config.getZlmSecret() != null && !config.getZlmSecret().trim().isEmpty())
      return config.getZlmSecret().trim();
    try {
      Path directory = ApplicationPaths.directory();
      if (Files.isRegularFile(directory.resolve("pom.xml")))
        directory = directory.resolve("target");
      Path configured = Paths.get(config.getZlmSecretFile());
      Path file = configured.isAbsolute() ? configured : directory.resolve(configured).normalize();
      String secret = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
      if (secret.isEmpty()) throw unavailable("媒体密钥文件为空，请先运行 start-media.ps1");
      return secret;
    } catch (IOException | IllegalArgumentException | NullPointerException | SecurityException e) {
      throw unavailable("未配置媒体密钥或密钥文件不可读，请先运行 start-media.ps1，或设置 zlm-secret");
    }
  }

  @PreDestroy
  public synchronized void close() {
    closed = true;
    stop();
  }

  private ResponseStatusException unavailable(String message) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }
}
