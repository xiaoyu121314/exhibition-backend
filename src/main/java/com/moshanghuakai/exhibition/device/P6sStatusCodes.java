package com.moshanghuakai.exhibition.device;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * P6S 设备状态码的本地中文定义，查询、人员维护和导入共用，避免按接口分别解释同一个码值。
 * 来源：http://xrs-api.p6sai.com:8091/project/11074/interface/api/72673，核对日期：2026-11-06。
 * 仅整理原文的排版与明显笔误；例如 4112 的换行还原为 300×300，不能与 4106 的人脸尺寸混淆。
 *
 * @author MoShangHuaKai
 */
public final class P6sStatusCodes {
  private static final Map<String, String> DESCRIPTIONS;

  static {
    Map<String, String> codes = new HashMap<>();
    // 公共命令状态：只有 0 表示成功，“操作结束”仍须保留非零状态的失败语义。
    codes.put("0", "操作成功");
    codes.put("1", "操作失败");
    codes.put("2", "操作忙");
    codes.put("3", "操作结束");

    // CGI 层错误。负数与正数属于同一状态字段，不能按 HTTP 状态或现场现象重新定义。
    codes.put("-1", "失败");
    codes.put("-2", "编解码失败");
    codes.put("-3", "不支持");
    codes.put("-4", "通道错误");
    codes.put("-5", "参数错误");
    codes.put("-6", "查询日志忙");
    codes.put("-7", "查询日志失败");
    codes.put("-8", "调用 SDK 错误");
    codes.put("-9", "内存错误");
    codes.put("-10", "URL 错误");
    codes.put("-11", "用户溢出");
    codes.put("-12", "无效操作");
    codes.put("-13", "用户已存在");
    codes.put("-14", "声音已开启");
    codes.put("-15", "声音已关闭");
    codes.put("-16", "网络传输失败");
    codes.put("-17", "用户认证失败");
    codes.put("-18", "XML 节点错误");
    codes.put("-19", "Unicode 转 UTF-8 失败");
    codes.put("-20", "Unicode 长度为空");
    codes.put("-21", "base64 编码失败");
    codes.put("-22", "base64 解码失败");
    codes.put("-23", "UTF-8 转 Unicode 失败");
    codes.put("-24", "UTF-8 长度为空");
    codes.put("-25", "需要重置用户密码");
    codes.put("-26", "token 未找到");
    codes.put("-27", "token 解析失败");
    codes.put("-28", "用户名解析失败");
    codes.put("-29", "密码解析失败");
    codes.put("-30", "token 格式错误");
    codes.put("-31", "密码和旧密码一样");
    codes.put("-32", "写入失败");
    codes.put("-33", "取消写入");
    codes.put("-34", "写入内存满");
    codes.put("-35", "写入文件版本错误");
    codes.put("-36", "表单数据结束边界错误");
    codes.put("-37", "名称位图太大");
    codes.put("-38", "人物图片太大");
    codes.put("-39", "HTTP 请求太大");
    codes.put("-40", "MAC 地址和设备 MAC 不匹配");
    codes.put("-41", "GET 方法不支持");
    codes.put("-42", "PUT 方法不支持");
    codes.put("-43", "POST 方法不支持");
    codes.put("-44", "DELETE 方法不支持");
    codes.put("-45", "弱密码");
    codes.put("-46", "返回参数错误");

    // 人脸算法及照片错误；4101 是算法未初始化，安全码不匹配对应 4208。
    codes.put("4097", "图片格式错误，仅支持 jpg/jpeg");
    codes.put("4098", "图片中没有找到人脸");
    codes.put("4099", "图片中人脸数超过一个");
    codes.put("4100", "非法的人脸属性");
    codes.put("4101", "人脸算法未初始化");
    codes.put("4102", "创建脸库失败");
    codes.put("4103", "特征值版本不匹配");
    codes.put("4104", "脸库不存在");
    codes.put("4105", "脸库数量达到上限");
    codes.put("4106", "人脸太大，人脸尺寸上限为300×300");
    codes.put("4107", "人脸太暗");
    codes.put("4108", "面部角度偏移太大");
    codes.put("4109", "人脸太小");
    codes.put("4110", "人脸检测失败，请使用另一张图片重试");
    codes.put("4111", "人脸已存在");
    codes.put("4112", "图片像素过大，图片限制在300×300");

    // 脸库容量、人员身份与拥有者安全码错误。
    codes.put("4196", "脸库名已存在");
    codes.put("4197", "人名已存在");
    codes.put("4198", "脸库总数已达上限");
    codes.put("4199", "人员已达上限");
    codes.put("4200", "唯一 ID 已存在");
    codes.put("4201", "唯一 ID 和人员 ID 无效");
    codes.put("4202", "人员 ID 和唯一 ID 不匹配");
    codes.put("4203", "脸库 ID 无效");
    codes.put("4204", "人员 ID 无效");
    codes.put("4205", "唯一 ID 无效");
    codes.put("4206", "图像计算的md5与传入的不匹配");
    codes.put("4207", "未绑定脸库中人员不能修改图片");
    codes.put("4208", "安全码不匹配");

    // T5AI 中间层错误，同样可能通过统一 ResponseStatus 返回。
    codes.put("16001", "不支持的主命令字");
    codes.put("16002", "不支持的次命令字");
    codes.put("16003", "结构体无效");
    codes.put("16004", "结构体是只读的");
    codes.put("16005", "缓冲区太小");
    codes.put("16006", "系统不支持该结构体的设置或读取");
    codes.put("16007", "没有权限");
    codes.put("16008", "参数溢出");
    codes.put("16009", "调用 SDK 失败");

    // 厂商同页列出的升级错误仅用于解释响应，不增加任何设备升级或配置写入能力。
    codes.put("18001", "升级列表服务器配置无效，host ip无效");
    codes.put("18002", "升级列表服务器配置无效，端口无效");
    codes.put("18003", "升级列表服务器配置无效，不支持的下载协议");
    codes.put("18004", "升级列表服务器配置无效，用户名解密失败");
    codes.put("18005", "升级列表服务器配置无效，用户密码解密失败");
    codes.put("18006", "请先配置升级列表服务器地址");
    codes.put("18007", "获取升级列表失败，不支持的定制编号");
    codes.put("18008", "获取升级列表失败，不支持的设备版型");
    codes.put("18009", "获取升级列表失败，升级列表下载失败");
    codes.put("18010", "获取升级列表失败，升级列表保存失败");
    codes.put("18011", "获取升级列表失败，升级列表打开失败");
    codes.put("18012", "获取升级列表失败，升级列表解析失败");
    codes.put("18013", "操作冲突，其他用户正在执行升级");
    codes.put("18014", "操作冲突，其他升级方式正在进行");
    codes.put("18015", "升级过程凭证校验失败");
    codes.put("18016", "请先下载升级列表");
    codes.put("18017", "正在下载升级包，请勿重复操作");
    codes.put("18018", "升级包下载失败，升级包下载地址信息文件打开失败");
    codes.put("18019", "升级包下载失败，升级包下载地址信息文件解析失败");
    codes.put("18020", "升级包下载失败，下载地址host ip无效");
    codes.put("18021", "升级包下载失败，下载地址端口无效");
    codes.put("18022", "升级包下载失败，下载地址URL无效");
    codes.put("18023", "升级包下载失败，升级包大小异常");
    codes.put("18024", "升级包下载失败，用户名解密失败");
    codes.put("18025", "升级包下载失败，用户密码解密失败");
    codes.put("18026", "指定版本不存在，无法进行下载");
    codes.put("18027", "升级包下载失败，下载缓存准备失败");
    codes.put("18028", "升级包文件过大，缓冲区不够");
    codes.put("18029", "升级包下载失败，下载线程创建失败");
    codes.put("18030", "请先下载升级文件");
    codes.put("18031", "升级包下载还未完成，无法执行升级");
    codes.put("18032", "正在进行升级，完成前请勿执行其它操作");
    codes.put("18033", "升级启动失败，升级凭证保存失败");
    codes.put("18034", "升级包文件校验失败");
    codes.put("18035", "升级包下载失败，下载协议不支持");
    codes.put("18036", "电池电量不足");
    // 初始化后禁止修改，确保并发请求始终使用同一份厂商定义。
    DESCRIPTIONS = Collections.unmodifiableMap(codes);
  }

  private P6sStatusCodes() {}

  /**
   * 输入为调用方已清理的状态码文本。保留字符串键，避免未知十位整数转换时发生溢出。
   * 未收录的固件扩展码统一兜底，不从设备原始 message 猜测含义或透传其中的敏感内容。
   */
  public static String description(String code) {
    return DESCRIPTIONS.getOrDefault(code, "未知厂商错误");
  }
}
