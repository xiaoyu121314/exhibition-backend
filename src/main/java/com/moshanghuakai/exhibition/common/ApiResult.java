package com.moshanghuakai.exhibition.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 业务 JSON 返回体；设备回调及文件下载不使用此包装。 @author MoShangHuaKai */
@Data
@AllArgsConstructor
public class ApiResult<T> {
  @Schema(description = "业务状态码，成功 200，错误时与 HTTP 状态对应", example = "200")
  private int code;

  @Schema(description = "成功提示或可读错误原因", example = "success")
  private String message;

  @Schema(description = "业务结果；无返回内容时为 null")
  private T data;

  public static <T> ApiResult<T> ok(T data) {
    return new ApiResult<>(200, "success", data);
  }
}
