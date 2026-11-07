package com.moshanghuakai.exhibition.common;

import java.util.UUID;

/** 使用稳定字符串主键，避免前端 JavaScript 数值精度和工号前导零丢失。 @author MoShangHuaKai */
public final class Ids {
  private Ids() {}

  public static String next() {
    return UUID.randomUUID().toString();
  }
}
