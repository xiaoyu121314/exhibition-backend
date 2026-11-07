package com.moshanghuakai.exhibition.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.*;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 回调无需令牌或签名；逐块读取并限制大小，避免 chunked 上传绕过长度限制。 @author MoShangHuaKai */
@RestController
@Tag(name = "06 摄像头回调", description = "P6S 心跳、抓拍及设备比对上报，无需访问令牌或签名")
public class DeviceController {
  private final DeviceAuthentication authentication;
  private final DeviceEventService service;
  private final ObjectMapper json;

  public DeviceController(
      DeviceAuthentication authentication, DeviceEventService service, ObjectMapper json) {
    this.authentication = authentication;
    this.service = service;
    this.json = json;
  }

  @PostMapping(value = "/api/device/p6s/events", consumes = "application/json")
  @Operation(
      summary = "接收摄像头心跳、抓拍和人脸比对",
      description =
          "接受 heartbeat、FaceSnapshot、FaceReco，原始请求体上限4MB。持久化后返回厂商ACK。FaceReco的ACK未验收时保留诊断事件后返回503；生产识别默认关闭。设备序列号须与配置一致。",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description =
                  "设备原始 JSON。info.eventId 区分大小写；抓拍 ACK 按协议回显为 info.eventID。CaptureImage 必填，BackgroundImage 可选；图片为 JPEG/PNG 的 Base64，pictureMd5 可选。",
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(type = "object"),
                      examples = {
                        @ExampleObject(
                            name = "heartbeat",
                            summary = "设备心跳",
                            value =
                                "{\"operator\":\"heartbeat\",\"deviceInfo\":{\"serialNumber\":\"替换为设备序列号\"},\"info\":{\"eventId\":\"1\",\"time\":\"2026-10-28T10:00:00+08:00\"}}"),
                        @ExampleObject(
                            name = "FaceReco",
                            summary = "模拟比对事件，身份及ACK须先真机核实",
                            value = "{\"operator\":\"FaceReco\",\"deviceInfo\":{\"serialNumber\":\"替换为设备序列号\"},\"info\":{\"eventId\":\"3\",\"time\":\"2026-11-05T16:00:00+08:00\",\"matchNumber\":1,\"personInfo\":{\"personId\":\"123\",\"uniqueId\":\"已核实身份\",\"personType\":\"已核实库\",\"match\":\"88.083099\"},\"CaptureImage\":{\"picture\":\"替换为实际图片Base64\"}}}"),
                        @ExampleObject(
                            name = "FaceSnapshot",
                            summary = "人脸抓拍，替换图片占位符",
                            value =
                                "{\"operator\":\"FaceSnapshot\",\"deviceInfo\":{\"serialNumber\":\"替换为设备序列号\"},\"info\":{\"eventId\":\"2\",\"time\":\"2026-10-28T10:00:00+08:00\",\"trackId\":\"1\",\"CaptureImage\":{\"picture\":\"替换为实际图片Base64\"}}}")
                      })))
  @ApiResponse(
      responseCode = "200",
      description = "厂商 ACK：持久化成功后应答，重复抓拍幂等返回相同事件 ACK",
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      value =
                          "{\"operator\":\"FaceSnapshot-Ack\",\"info\":{\"eventID\":\"2\"},\"result\":{\"errorNo\":0,\"description\":\"ok\"}}")))
  @ApiResponse(responseCode = "503", description = "FaceReco诊断已提交，但ACK尚未验证或字段类型不符；不返回成功ACK，生产识别应保持关闭")
  public ResponseEntity<byte[]> event(HttpServletRequest request) throws IOException {

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int length;
    while ((length = request.getInputStream().read(chunk)) != -1) {
      if (output.size() + length > 4 * 1024 * 1024) throw new BusinessException("设备报文超过4MB");
      output.write(chunk, 0, length);
    }
    byte[] raw = output.toByteArray();
    // 只解析一次报文，按序列号匹配业务配置；不再读取或校验任何鉴权请求头。
    JsonNode body = json.readTree(raw);
    com.moshanghuakai.exhibition.config.ExhibitionProperties.Device device =
        authentication.resolve(body);
    Map<String, Object> ack;
    try {
      ack = service.receive(device, body);
    } catch (DuplicateKeyException e) {
      // 两个相同事件同时进入时，唯一键输掉的一方已回滚；再次查询并返回相同 ACK。
      ack = service.receive(device, body);
    }
    if (ack == null) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "比对事件已保存，ACK契约尚未确认或身份类型不符");
    // 先完成厂商 ACK 的 UTF-8 序列化，再按实际字节数声明长度，避免容器使用 chunked 分块响应。
    // 长度不能使用字符串字符数；直接返回字节数组，也避免 JSON 被再次序列化为带引号的字符串。
    byte[] response = json.writeValueAsBytes(ack);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .contentLength(response.length)
        .body(response);
  }
}
