package com.moshanghuakai.exhibition.device;

import com.moshanghuakai.exhibition.common.BusinessException;

/** 区分设备明确拒绝与请求结果不确定，后者必须查询或补偿，不能盲目重发。 @author MoShangHuaKai */
public class DeviceSyncException extends BusinessException {
  private final boolean uncertain;

  public DeviceSyncException(String message, boolean uncertain) {
    super("设备同步失败：" + message);
    this.uncertain = uncertain;
  }

  public boolean isUncertain() {
    return uncertain;
  }
}
