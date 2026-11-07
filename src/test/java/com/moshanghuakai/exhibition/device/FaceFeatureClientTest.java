package com.moshanghuakai.exhibition.device;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.BusinessException;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** 本机特征HTTP契约：模型隔离、维度、归一化及失败语义，不依赖外部模型环境。 @author MoShangHuaKai */
class FaceFeatureClientTest {
  private HttpServer server;
  private final ExhibitionProperties properties = new ExhibitionProperties();
  private FaceFeatureClient client;
  private String response;
  private int responseStatus;
  private byte[] received;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
    server.createContext("/feature", exchange -> {
      received = org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody());
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(responseStatus, body.length);
      exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    properties.getFaceFeature().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    client = new FaceFeatureClient(properties, new ObjectMapper());
    responseStatus = 200; response = vectorResponse("opencv-sface-2021dec", 128);
  }

  @AfterEach
  void stop() { server.stop(0); }

  private String vectorResponse(String model, int dimensions) throws Exception {
    double[] vector = new double[dimensions]; vector[0] = 2;
    Map<String, Object> result = new LinkedHashMap<>(); result.put("model", model); result.put("feature", vector);
    return new ObjectMapper().writeValueAsString(result);
  }

  @Test
  void sendsOriginalImageAndNormalizesExpectedModelVector() {
    byte[] image = {1, 2, 3, 4};
    double[] feature = client.extract(image);
    assertThat(received).containsExactly(image);
    assertThat(feature).hasSize(128); assertThat(feature[0]).isEqualTo(1);
  }

  @Test
  void rejectsOtherModelsWrongDimensionsAndZeroVectors() throws Exception {
    response = vectorResponse("another-model", 128);
    assertThatThrownBy(() -> client.extract(new byte[] {1})).hasMessage("INVALID_FEATURE");
    response = vectorResponse("opencv-sface-2021dec", 127);
    assertThatThrownBy(() -> client.extract(new byte[] {1})).hasMessage("INVALID_FEATURE");
    response = vectorResponse("opencv-sface-2021dec", 128).replace("2.0", "0.0");
    assertThatThrownBy(() -> client.extract(new byte[] {1})).hasMessage("INVALID_FEATURE");
  }

  @Test
  void missingFaceIsPermanentButUnavailableServiceCanRetry() {
    responseStatus = 422; response = "{\"error\":\"NO_FACE\"}";
    assertThatThrownBy(() -> client.extract(new byte[] {1})).isInstanceOfSatisfying(
        FaceFeatureClient.ExtractionFailure.class, error -> {
          assertThat(error.getMessage()).isEqualTo("NO_FACE"); assertThat(error.isRetryable()).isFalse();
        });
    responseStatus = 503; response = "service unavailable";
    assertThatThrownBy(() -> client.extract(new byte[] {1})).isInstanceOfSatisfying(
        FaceFeatureClient.ExtractionFailure.class, error -> assertThat(error.isRetryable()).isTrue());
  }

  @Test
  void refusesNonLocalUrlsAndInvalidThresholdsBeforeSendingImages() {
    properties.getFaceFeature().setBaseUrl("http://192.168.0.66:18090");
    assertThatThrownBy(client::validate).isInstanceOf(BusinessException.class);
    properties.getFaceFeature().setBaseUrl("http://127.0.0.1:18090");
    properties.getFaceFeature().setMinSimilarity(Double.NaN);
    assertThatThrownBy(client::validate).isInstanceOf(BusinessException.class);
    assertThat(received).isNull();
  }
}
