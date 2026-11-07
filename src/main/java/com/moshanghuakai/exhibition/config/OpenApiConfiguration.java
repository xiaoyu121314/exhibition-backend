package com.moshanghuakai.exhibition.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger 文档公共配置。所有业务接口直接调试，不声明访问令牌或设备签名要求。
 *
 * @author MoShangHuaKai
 */
@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI exhibitionOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("展厅人员与摄像头管理后台接口")
                .version("1.0.0")
                .contact(new Contact().name("MoShangHuaKai"))
                .description(
                    "所有业务接口均列于下方。JSON 业务响应使用 code/message/data 包装，文件下载、视频资源和摄像头 ACK 除外。"
                        + "实时视频、管理、大屏、图片和摄像头回调接口无需访问令牌、账号密码或设备签名，可直接调用。"
                        + "本地识别已移除；大屏人数返回 null，抓拍记录不代表去重人数。"));
  }

  /** 复用错误说明，文件接口的失败也返回 JSON；不把业务异常误标为 Excel 或图片。 */
  @Bean
  public OperationCustomizer apiErrorResponses() {
    return (operation, handlerMethod) -> {
      operation
          .getResponses()
          .addApiResponse("400", error("参数、文件、业务校验失败或并发版本冲突", 400))
          .addApiResponse("403", new ApiResponse().description("图片不允许访问；响应体可为空"))
          .addApiResponse("409", error("工号等唯一标识冲突", 409))
          .addApiResponse("500", error("服务内部异常，请联系管理员查看日志", 500));
      return operation;
    };
  }

  private ApiResponse error(String description, int code) {
    Schema<?> schema =
        new Schema<>()
            .type("object")
            .addProperty("code", new Schema<>().type("integer").example(code))
            .addProperty("message", new Schema<>().type("string").description(description))
            .addProperty("data", new Schema<>().nullable(true).example(null));
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/json",
                    new io.swagger.v3.oas.models.media.MediaType().schema(schema)));
  }
}
