package com.moshanghuakai.exhibition.staff;

import com.moshanghuakai.exhibition.common.ApiResult;
import com.moshanghuakai.exhibition.domain.Models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.*;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 工作人员管理接口，不将人员特征向管理前端或大屏公开。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/admin/staff")
@Tag(name = "01 工作人员管理", description = "人员档案和识别照片维护，无需访问令牌")
public class StaffController {
  private final StaffService service;

  public StaffController(StaffService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(
      summary = "分页查询工作人员",
      description = "只返回未删除档案；工号、姓名支持模糊查询，部门精确匹配。data 包含 total 和 records；每条记录的 avatarUrl 原样返回头像媒体的 relative_path，无头像或媒体记录时为 null。不返回人脸特征。")
  @ApiResponse(
      responseCode = "200",
      description = "查询成功",
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      value =
                          "{\"code\":200,\"message\":\"success\",\"data\":{\"total\":0,\"records\":[]}}")))
  public ApiResult<Map<String, Object>> page(
      @Parameter(description = "工号，字符串保留前导零", example = "001") @RequestParam(required = false)
          String staffNo,
      @Parameter(description = "姓名关键字", example = "张三") @RequestParam(required = false) String name,
      @Parameter(description = "部门名称，精确匹配", example = "展厅") @RequestParam(required = false)
          String department,
      @Parameter(description = "是否启用；不传则不按启用状态过滤") @RequestParam(required = false) Boolean enabled,
      @Parameter(description = "页码，从 1 开始，最大 1000000", example = "1")
          @RequestParam(defaultValue = "1")
          int page,
      @Parameter(description = "每页条数，1～100", example = "20") @RequestParam(defaultValue = "20")
          int size) {
    return ApiResult.ok(
        service.page(staffNo, name, department, enabled, page, size));
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "获取工作人员详情",
      description =
          "data.staff 为档案，data.photos 为照片数组，data.deviceBinding 为设备身份、建模状态原值及回查时间。建模状态为空不表示成功。修改需携带 staff.version。")
  public ApiResult<Map<String, Object>> detail(
      @Parameter(description = "工作人员 ID", required = true) @PathVariable String id) {
    Map<String, Object> result = new LinkedHashMap<>();
    Staff staff = service.require(id);
    result.put("staff", staff);
    result.put("deviceBinding", service.deviceBinding(id));
    List<Map<String, Object>> photos = new ArrayList<>();
    // 单头像直接读取档案引用，展示照片不再依赖本地识别模板。
    if (staff.getAvatarId() != null) {
      Map<String, Object> photo = new LinkedHashMap<>();
      photo.put("imageId", staff.getAvatarId());
      photo.put("url", "/api/media/" + staff.getAvatarId());
      photo.put("primary", true);
      photos.add(photo);
    }
    result.put("photos", photos);
    return ApiResult.ok(result);
  }

  @PostMapping
  @Operation(summary = "新增工作人员", description = "先独立上传头像获得 imageId；新增必传 imageId 和 sex，工号由后台从001起自动递增生成，无需传入。启用人员先同步设备再写库，停用人员仅保存本地；新增无需 version。")
  public ApiResult<Staff> create(@Valid @RequestBody StaffRequest request) {
    return ApiResult.ok(service.create(request));
  }

  @PutMapping("/{id}")
  @Operation(summary = "修改工作人员", description = "提交最新 version 和 sex；工号保持不变，无需传入；imageId 省略时保留头像，提供时替换。先同步设备再写库；停用删除设备人员，启用重新添加；失败不修改本地档案。")
  public ApiResult<Staff> update(
      @Parameter(description = "工作人员 ID", required = true) @PathVariable String id,
      @Valid @RequestBody StaffRequest request) {
    return ApiResult.ok(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "删除工作人员", description = "先删除设备脸库人员，再本地逻辑删除并移出识别库；设备失败则本地不变，工号仍保留，不自动重算历史统计。")
  public ApiResult<Void> delete(
      @Parameter(description = "工作人员 ID", required = true) @PathVariable String id) {
    service.delete(id);
    return ApiResult.ok(null);
  }

  @PostMapping(value = "/face-images", consumes = "multipart/form-data")
  @Operation(
      summary = "独立上传工作人员头像",
      description =
          "multipart 字段 file，JPEG/PNG，单张最多5MB；只保存图片并返回 data.imageId，不关联人员或调用设备。新增修改接口通过 imageId 绑定唯一头像，保存时校验图片归属并同步设备；未引用图片满24小时后定时清理。")
  public ApiResult<Map<String, String>> upload(
      @Parameter(description = "工作人员照片，JPEG 或 PNG", required = true) @RequestParam("file")
          MultipartFile file)
      throws IOException {
    Media image = service.upload(file.getBytes());
    return ApiResult.ok(Collections.singletonMap("imageId", image.getId()));
  }
}
