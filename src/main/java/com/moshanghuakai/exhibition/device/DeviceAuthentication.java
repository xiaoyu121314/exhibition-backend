package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.moshanghuakai.exhibition.common.BusinessException;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.Device;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 回调设备配置匹配与图片摘要工具；接口开放后不再校验 OAuth 令牌、签名或 Nonce。 @author MoShangHuaKai */
@Service
public class DeviceAuthentication {
  private final ExhibitionProperties properties;

  public DeviceAuthentication(ExhibitionProperties properties) {
    this.properties = properties;
  }

  /** 根据报文序列号选择设备业务配置，不将设备标识当作访问凭据。 */
  public Device resolve(JsonNode body) {
    if (body == null || !body.isObject()) throw new BusinessException("设备报文必须为 JSON 对象");
    String serialNumber = body.path("deviceInfo").path("serialNumber").asText();
    if (serialNumber.trim().isEmpty()) throw new BusinessException("设备序列号不能为空");
    return properties.getDevices().stream()
        .filter(device -> serialNumber.equals(device.getSerialNumber()))
        .findFirst()
        .orElseThrow(() -> new BusinessException("设备序列号未配置"));
  }

  /** 摘要仍用于图片完整性及事件幂等键，与接口鉴权无关。 */
  public static String hex(String algorithm, byte[] value) {
    try {
      byte[] hash = MessageDigest.getInstance(algorithm).digest(value);
      StringBuilder result = new StringBuilder();
      for (byte item : hash) result.append(String.format(Locale.ROOT, "%02x", item & 255));
      return result.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
