package com.moshanghuakai.exhibition.common;

/** 明确可向管理端展示的业务校验错误。 @author MoShangHuaKai */
public class BusinessException extends RuntimeException {
  public BusinessException(String message) {
    super(message);
  }
}
