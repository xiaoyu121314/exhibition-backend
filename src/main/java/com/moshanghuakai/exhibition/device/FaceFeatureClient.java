package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.BusinessException;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.FaceFeature;
import java.net.URI;
import java.util.Arrays;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

/** 仅向本机提取服务发送已保存图片；校验模型及128维单位向量，不把特征返回大屏。 @author MoShangHuaKai */
@Service
public class FaceFeatureClient {
  private final ExhibitionProperties properties;
  private final ObjectMapper json;

  public FaceFeatureClient(ExhibitionProperties properties, ObjectMapper json) {
    this.properties = properties; this.json = json;
  }

  public void validate() {
    FaceFeature config = properties.getFaceFeature();
    try {
      URI uri = URI.create(config.getBaseUrl());
      if (!"http".equals(uri.getScheme()) || !Arrays.asList("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
        throw new IllegalArgumentException();
      if (config.getModelId() == null || config.getModelId().trim().isEmpty()
          || config.getModelId().length() > 64 || !Double.isFinite(config.getMinSimilarity())
          || config.getMinSimilarity() <= 0 || config.getMinSimilarity() >= 1
          || config.getConnectTimeoutMs() < 1 || config.getReadTimeoutMs() < 1
          || config.getBatchSize() < 1 || config.getBatchSize() > 100 || config.getMaxAttempts() < 1
          || config.getMaxAttempts() > 10 || config.getRetrySeconds() < 1)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new BusinessException("特征服务配置无效：仅允许本机HTTP地址，需配置模型、余弦阈值和正数超时");
    }
  }

  public double[] extract(byte[] image) {
    validate();
    FaceFeature config = properties.getFaceFeature();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(config.getConnectTimeoutMs()); factory.setReadTimeout(config.getReadTimeoutMs());
    // 本机模型不经过系统代理，避免启用设备HTTP代理后将人脸图片发送到代理服务。
    factory.setProxy(java.net.Proxy.NO_PROXY);
    RestTemplate client = new RestTemplate(factory);
    HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    try {
      String base = config.getBaseUrl().replaceAll("/+$", "");
      byte[] body = client.postForObject(base + "/feature", new HttpEntity<>(image, headers), byte[].class);
      JsonNode result = json.readTree(body), feature = result.path("feature");
      if (!config.getModelId().equals(result.path("model").asText()) || !feature.isArray() || feature.size() != 128)
        throw new ExtractionFailure("INVALID_FEATURE", false);
      double[] vector = new double[128]; double sum = 0;
      for (int i = 0; i < vector.length; i++) {
        if (!feature.get(i).isNumber()) throw new ExtractionFailure("INVALID_FEATURE", false);
        vector[i] = feature.get(i).asDouble(); sum += vector[i] * vector[i];
      }
      if (!Double.isFinite(sum) || sum <= 0) throw new ExtractionFailure("INVALID_FEATURE", false);
      double norm = Math.sqrt(sum);
      for (int i = 0; i < vector.length; i++) vector[i] /= norm;
      return vector;
    } catch (HttpClientErrorException error) {
      String code = "INVALID_IMAGE";
      try {
        String supplied = json.readTree(error.getResponseBodyAsByteArray()).path("error").asText();
        if (Arrays.asList("NO_FACE", "MULTIPLE_FACES", "INVALID_IMAGE", "INVALID_IMAGE_SIZE", "INVALID_FEATURE").contains(supplied))
          code = supplied;
      } catch (Exception ignored) { }
      throw new ExtractionFailure(code, false);
    } catch (ExtractionFailure error) {
      throw error;
    } catch (RestClientException error) {
      throw new ExtractionFailure("FEATURE_SERVICE_UNAVAILABLE", true);
    } catch (Exception error) {
      throw new ExtractionFailure("INVALID_FEATURE", false);
    }
  }

  /** 只传播固定业务错误和是否可重试，不传播HTTP报文、服务端路径或向量内容。 */
  public static class ExtractionFailure extends BusinessException {
    private final boolean retryable;
    public ExtractionFailure(String code, boolean retryable) { super(code); this.retryable = retryable; }
    public boolean isRetryable() { return retryable; }
  }
}
