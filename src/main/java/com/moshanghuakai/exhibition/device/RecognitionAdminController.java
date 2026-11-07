package com.moshanghuakai.exhibition.device;

import com.moshanghuakai.exhibition.common.ApiResult;
import com.moshanghuakai.exhibition.staff.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 运维核实入口；基线仅查询设备，身份映射仍以本地事务一致提交。 @author MoShangHuaKai */
@RestController
@RequestMapping("/api/admin/recognition")
@Tag(name = "07 识别接入核实", description = "真实固件契约核实及当前人员身份基线")
public class RecognitionAdminController {
  private final StaffService staff;
  private final DeviceFaceClient device;
  private final FaceFeatureService faceFeatures;
  public RecognitionAdminController(StaffService staff, DeviceFaceClient device, FaceFeatureService faceFeatures) {
    this.staff = staff; this.device = device; this.faceFeatures = faceFeatures;
  }
  @PostMapping("/baseline")
  @Operation(summary = "回查并建立工作人员设备身份基线", description = "先确认protocol-verified及身份来源。仅查询设备现有脸库，不上传照片或删除人员；全部核实成功后一次事务更新本地绑定和身份历史。")
  public ApiResult<Map<String, Integer>> baseline() {
    return ApiResult.ok(Collections.singletonMap("verifiedCount", staff.refreshDeviceBaseline()));
  }
  @GetMapping("/device-diagnostics")
  @Operation(summary = "只读核对设备识别能力及规则", description = "查询固定的能力、配置通道的识别基本配置、规则及抓拍配置；设备不支持项分别返回提示。不会写设备配置，不返回管理凭据。")
  public ApiResult<Map<String, String>> diagnostics() { return ApiResult.ok(device.recognitionDiagnostics()); }

  @PostMapping("/face-features/retry")
  @Operation(summary = "重试失败的人脸特征任务", description = "新功能默认关闭。启用并部署新表后，模型恢复时可重置目标设备的失败任务，随后异步重新提取和去重；不向设备写人员，不修改成功事件或ACK。")
  public ApiResult<Map<String, Integer>> retryFaceFeatures() {
    return ApiResult.ok(Collections.singletonMap("retriedCount", faceFeatures.retryFailed()));
  }
}
