package com.moshanghuakai.exhibition.domain;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 人员档案、设备身份及抓拍记录模型；档案更新使用版本号避免并发覆盖。 @author MoShangHuaKai */
public final class Models {
  private Models() {}

  @Data
  @Schema(description = "工作人员档案，不包含人脸特征")
  public static class Staff {
    @Schema(description = "工作人员 ID")
    private String id;

    @Schema(description = "后台从001起自动递增的唯一工号，修改时保持不变", example = "001")
    private String staffNo;

    @Schema(description = "姓名", example = "张三")
    private String name;

    @Schema(description = "性别：male 男、female 女", allowableValues = {"male", "female"})
    private String sex;

    @Schema(description = "职位", example = "讲解员")
    private String position;

    @Schema(description = "所属部门", example = "展厅")
    private String department;

    @Schema(description = "工作人员唯一头像媒体ID；通过 /api/media/{id} 访问")
    private String avatarId;

    // 列表查询按 avatarId 关联媒体表填充，原样返回实际保存路径；仅用于响应，不写入工作人员表。
    @Schema(description = "列表返回的头像保存位置，取自 exh_media.relative_path；无头像或媒体记录时为 null",
        example = "staff/2026-11-04/图片UUID.jpg", accessMode = Schema.AccessMode.READ_ONLY)
    private String avatarUrl;

    @Schema(description = "是否启用设备人员同步")
    private Boolean enabled = true;

    @Schema(description = "逻辑删除标记，正常查询不返回已删除档案")
    private Boolean deleted = false;

    @Schema(description = "乐观锁版本，修改档案时必须回传最新值")
    private Integer version = 0;

    @Schema(description = "建档时间，北京时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后修改时间，北京时间")
    private LocalDateTime updatedAt;
  }

  /** 保存设备实际生成的人脸ID，不以姓名或本地图片ID代替设备身份。 */
  @Data
  public static class StaffDeviceBinding {
    private String staffId, deviceSerialNumber, faceGroupId, faceUuid;
    private String numericGroupId, devicePersonId, deviceUniqueId, modelState;
    private String identityType, identityValue, callbackGroup;
    private LocalDateTime verifiedAt;
    // 有效时间仍按北京时间 DATETIME 保存；只读响应统一输出显式 +08:00。
    private LocalDateTime validFrom;
  }

  /** 同一设备身份在一个有效区间内只能指向一个工作人员；保留关闭区间以处理补传。 */
  @Data
  public static class DeviceIdentity {
    private String id, staffId, deviceSn, faceGroupId, identityType, identityValue;
    private LocalDateTime validFrom, validUntil;
  }

  @Data
  @Schema(description = "摄像头抓拍事件及处理结果")
  public static class Capture {
    private String hallId, eventType, matchStatus, matchReason;
    private Integer matchNumber, resolveAttempts;
    private java.math.BigDecimal similarity;
    private String devicePersonId, deviceUniqueId, callbackGroup, identityType, identityValue;
    private String staffId, staffNo, staffName, staffPosition, staffDepartment, staffAvatarId;
    private String recognizeImageId, pictureMd5;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String protocolJson, ackJson;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private LocalDateTime processedAt, nextResolveAt;
    @Schema(description = "后台抓拍记录 ID，用于查询抓拍详情")
    private String id;

    @Schema(description = "事件幂等摘要，防止设备重传重复写入；不作为人员 ID")
    private String eventKey;

    @Schema(description = "来源设备序列号")
    private String deviceSn;

    @Schema(description = "设备上报的事件编号")
    private String eventId;

    @Schema(description = "设备跟踪标记，仅辅助事件去重，不代表人员身份")
    private String trackId;

    @Schema(description = "抓拍人脸媒体 ID，过期报文可为 null")
    private String imageId;

    @Schema(description = "背景图媒体 ID，未上报背景图时为 null")
    private String backgroundId;

    @Schema(description = "接收状态：RECEIVED 已保存、EXPIRED 已过期", allowableValues = {"RECEIVED", "EXPIRED"})
    private String status;

    @Schema(description = "事件发生时间所属的北京时间自然日", example = "2026-10-28")
    private LocalDate businessDate;

    @Schema(description = "设备事件发生时间，转换为北京时间")
    private LocalDateTime eventTime;

    @Schema(description = "后台接收时间，北京时间")
    private LocalDateTime receivedAt;
  }

  @Data
  public static class Media {
    private String id, relativePath, contentType, ownerType;
    private LocalDateTime createdAt;
  }

  /** 每个展厅、北京时间自然日和模型版本独立去重；匿名身份不跨天跟踪。特征不进入公开接口。 */
  @Data
  public static class DailyPerson {
    private String id, hallId, modelId, staffId, featuresJson, mergedInto;
    private LocalDate businessDate;
    private LocalDateTime firstSeenAt, lastSeenAt;
  }

  /** 图片特征处理状态与设备比对状态分开，服务中断可以重试，不覆盖设备事件或 ACK。 */
  @Data
  public static class FaceFeatureEvent {
    private String captureId, modelId, personId, status, error;
    private Integer attempts;
    private LocalDateTime nextRetryAt, processedAt;
  }

  @Data
  public static class DailyPeopleCount {
    private long totalCount, guestCount;
  }

  @Data
  @Schema(description = "工作人员异步导入任务，使用 id 轮询任务接口")
  public static class ImportTask {
    @Schema(description = "导入任务 ID，即查询路径中的 taskId")
    private String id;

    @Schema(description = "导入源文件媒体 ID，不允许通过媒体接口下载")
    private String fileId;

    @Schema(
        description = "任务状态",
        allowableValues = {"PENDING", "PROCESSING", "SUCCESS", "FAILED"})
    private String status;

    @Schema(
        description = "处理阶段",
        allowableValues = {
          "UPLOADED", "RECOVERED", "VALIDATING", "SYNCING_DEVICE", "COMMITTING", "COMPENSATING",
          "COMPLETED", "ROLLED_BACK", "RECONCILIATION_REQUIRED"
        })
    private String phase;
    // 数据库保存 JSON 文本，接口通过 JsonRawValue 输出真正的 JSON 数组，文档也必须按数组描述。
    @ArraySchema(
        schema = @Schema(type = "object", description = "错误对象，包含 row 和 message；row=0 表示文件或提交阶段错误"))
    @com.fasterxml.jackson.annotation.JsonRawValue
    private String errors = "[]";

    @Schema(description = "待导入人员总行数，文件解析前为 0")
    private Integer totalRows = 0;

    @Schema(description = "已处理行数，包括资料或图片校验失败的行")
    private Integer processedRows = 0;

    @Schema(description = "最终成功新增人数；整批失败时为 0")
    private Integer createdCount = 0;

    @Schema(description = "最终成功更新人数；整批失败时为 0")
    private Integer updatedCount = 0;

    @Schema(description = "任务创建时间，北京时间")
    private LocalDateTime createdAt;

    @Schema(description = "任务最后更新时间，北京时间")
    private LocalDateTime updatedAt;
  }
}
