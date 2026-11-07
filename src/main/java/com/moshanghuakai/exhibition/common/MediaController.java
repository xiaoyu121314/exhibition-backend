package com.moshanghuakai.exhibition.common;

import com.moshanghuakai.exhibition.domain.Models.Media;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 图片按媒体 ID 直接读取；导入源文件不属于图片，仍禁止从此入口下载。 @author MoShangHuaKai */
@RestController
@Tag(name = "05 图片访问", description = "按媒体 ID 访问工作人员照片或抓拍图片")
public class MediaController {
  private final StorageService storage;
  public MediaController(StorageService storage) {
    this.storage = storage;
  }

  @GetMapping("/api/media/{id}")
  @Operation(
      summary = "获取图片内容",
      description =
          "无需访问令牌，返回人员照片、抓拍或未绑定图片二进制，禁止缓存；导入源文件禁止下载。前端可直接使用 img 标签展示。")
  @ApiResponse(
      responseCode = "200",
      description = "图片二进制",
      content = {
        @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary"))
      })
  public ResponseEntity<byte[]> read(
      @Parameter(
              description = "媒体 ID，例如人员 avatarId、照片 imageId、抓拍 imageId/backgroundId",
              required = true)
          @PathVariable
          String id) {
    Media media = storage.require(id);
    // 只保留文件类型约束，避免把 Excel 导入源文件作为图片返回。
    if ("IMPORT".equals(media.getOwnerType()))
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(media.getContentType()))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(storage.read(id));
  }
}
