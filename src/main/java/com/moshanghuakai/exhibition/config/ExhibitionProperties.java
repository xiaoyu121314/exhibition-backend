package com.moshanghuakai.exhibition.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 部署配置；展示文案由独立文件读取，设备凭据不会因文案热更新而变动。 @author MoShangHuaKai */
@Data
@ConfigurationProperties(prefix = "exhibition")
public class ExhibitionProperties {
  private String hallId = "first-floor";
  private String displayFile = "./config/exhibition-display.yml";
  private String storageDirectory = "./data";
  private boolean jobsEnabled = true;
  private int retentionDays = 30;
  private int maxImportRows = 500;
  private int maxExportRows = 500;
  private int heartbeatSeconds = 30;
  private Live live = new Live();
  private Recognition recognition = new Recognition();
  private FaceFeature faceFeature = new FaceFeature();
  private List<Device> devices = new ArrayList<>();

  /** 真机契约必须显式确认；空阈值和空身份配置不会被猜测成某个固件的默认值。 */
  @Data
  public static class Recognition {
    private boolean enabled = false;
    private boolean protocolVerified = false;
    private boolean ackVerified = false;
    private int channelId = 1;
    private String identityField;
    private String identitySource;
    private String groupField = "personType";
    private String groupValue;
    private java.math.BigDecimal minSimilarity;
    // 厂商 ACK 类型存在冲突，必须以实机取样选择 STRING 或 INTEGER。
    private String ackPersonIdType = "STRING";
    private String unmatchedPersonId = "0";
    private String unmatchedUniqueId = "";
  }

  /** 本机模型只提取特征；工作人员与访客的当日身份由 Java 事务统一维护。 */
  @Data
  public static class FaceFeature {
    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:18090";
    private String modelId = "opencv-sface-2021dec";
    // OpenCV SFace 官方示例的余弦阈值；与设备 0~100 的 match 分数互不换算。
    private double minSimilarity = 0.363;
    private int connectTimeoutMs = 1500;
    private int readTimeoutMs = 10000;
    private int batchSize = 10;
    private int maxAttempts = 3;
    private int retrySeconds = 30;
  }

  /** 实时预览独立配置；RTSP 地址和凭据仅供服务端使用。 */
  @Data
  public static class Live {
    private boolean enabled = false;
    private String rtspUrl;
    /** 媒体服务的后端专用 HTTP 地址，不向浏览器暴露。 */
    private String zlmBaseUrl = "http://127.0.0.1:9999";
    /** 媒体服务管理密钥，仅在后端请求中使用。 */
    private String zlmSecret;
    /** 未显式配置密钥时读取的文件，相对路径以部署目录为基准。 */
    private String zlmSecretFile = "./data/media/zlm-secret.txt";

    private int startupTimeoutSeconds = 15;
    private int idleTimeoutSeconds = 60;
  }

  @Data
  public static class Device {
    private String serialNumber;
    // 主动管理实体设备仍使用设备要求的 HTTP Basic 凭据；接收回调无需凭据。
    private String baseUrl;
    private String username;
    private String password;
    private String faceGroupId;
    private String ownerIdentifier;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;
  }
}
