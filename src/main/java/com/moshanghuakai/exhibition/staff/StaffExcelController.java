package com.moshanghuakai.exhibition.staff;

import com.moshanghuakai.exhibition.common.ApiResult;
import com.moshanghuakai.exhibition.domain.Models.ImportTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 模板、导入、导出入口；先完成文件生成再写响应，防止出错时返回残缺 Excel。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/admin/staff")
@Tag(name = "02 工作人员导入导出", description = "Excel 模板、异步导入任务及带头像导出，无需访问令牌")
public class StaffExcelController {
  private final StaffExcelService service;

  public StaffExcelController(StaffExcelService service) {
    this.service = service;
  }

  @GetMapping("/import-template")
  @Operation(
      summary = "下载工作人员导入模板",
      description = "下载 XLSX 二进制文件，包含工作人员及填写说明两个工作表；头像需使用 Excel 插入图片并锚定到人员行。")
  @ApiResponse(
      responseCode = "200",
      description = "Excel 模板文件",
      content =
          @Content(
              mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              schema = @Schema(type = "string", format = "binary")))
  public ResponseEntity<byte[]> template() {
    return download(service.template(), "工作人员导入模板.xlsx");
  }

  @PostMapping(value = "/import", consumes = "multipart/form-data")
  @Operation(
      summary = "导入工作人员 Excel",
      description =
          "multipart 字段 file，默认最多500人/50MB，必填性别；新增工号留空自动生成，填写工号只更新已有人员；返回 data.id 作为taskId。整份资料及图片校验通过后逐人同步设备，再整批写库；失败逆向补偿，需人工核对时阶段为RECONCILIATION_REQUIRED。")
  public ApiResult<ImportTask> upload(
      @Parameter(description = "按下载模板填写的 XLSX 文件", required = true) @RequestParam("file")
          MultipartFile file)
      throws IOException {
    return ApiResult.ok(service.submit(file.getBytes()));
  }

  @GetMapping("/import-tasks/{taskId}")
  @Operation(
      summary = "查询导入任务进度和结果",
      description = "返回任务状态、VALIDATING/SYNCING_DEVICE/COMMITTING/COMPENSATING 等阶段和 errors 数组；RECONCILIATION_REQUIRED 表示设备需人工核对。错误行号0表示文件或提交阶段错误。")
  public ApiResult<ImportTask> task(
      @Parameter(description = "导入接口返回的 data.id", required = true) @PathVariable String taskId) {
    return ApiResult.ok(service.requireTask(taskId));
  }

  @PostMapping("/export")
  @Operation(
      summary = "导出工作人员 Excel",
      description = "请求体可省略；按工号、姓名、部门、启用状态筛选，不分页。默认最多 500 人，包含展示头像，可按同一模板再次导入。")
  @ApiResponse(
      responseCode = "200",
      description = "工作人员 Excel 文件",
      content =
          @Content(
              mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              schema = @Schema(type = "string", format = "binary")))
  public ResponseEntity<byte[]> export(
      @RequestBody(required = false) StaffExcelService.Filter filter) {
    return download(
        service.export(filter == null ? new StaffExcelService.Filter() : filter), "工作人员.xlsx");
  }

  private ResponseEntity<byte[]> download(byte[] bytes, String name) {
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(bytes);
  }
}
