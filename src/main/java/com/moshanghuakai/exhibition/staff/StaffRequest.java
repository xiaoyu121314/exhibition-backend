package com.moshanghuakai.exhibition.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.*;
import lombok.Data;

/** 新增与修改共用字段契约；工号由后台分配，不接受编辑；修改必须携带最新 version。 @author MoShangHuaKai */
@Data
@Schema(description = "工作人员新增/修改参数；修改时必须提交最新 version")
public class StaffRequest {
  @Schema(description = "工作人员姓名", example = "张三")
  @NotBlank(message = "姓名不能为空")
  @Size(max = 32, message = "姓名不能超过32字符")
  private String name;

  @Schema(description = "性别，与设备一致：male 男、female 女", allowableValues = {"male", "female"})
  @NotBlank(message = "性别不能为空")
  @Pattern(regexp = "male|female", message = "性别必须为 male 或 female")
  private String sex;

  @Schema(description = "独立上传接口返回的图片ID；新增必填，修改省略时保留原头像")
  @Size(max = 36, message = "图片ID不能超过36字符")
  private String imageId;

  @Schema(description = "职位，大屏识别后展示", example = "讲解员")
  @Size(max = 100, message = "职位不能超过100字符")
  private String position;

  @Schema(description = "所属部门；空字符串表示清空部门", example = "展厅")
  @Size(max = 100, message = "部门不能超过100字符")
  private String department = "";

  @Schema(description = "是否启用；停用后不参与工作人员识别，历史统计保留", example = "true")
  @NotNull(message = "启用状态不能为空")
  private Boolean enabled = true;

  @Schema(description = "乐观锁版本，新增无需填写；修改必须使用详情返回的最新值", example = "0")
  private Integer version;
}
