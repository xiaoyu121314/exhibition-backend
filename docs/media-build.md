# ZLMediaKit 本机构建记录

<!-- @author MoShangHuaKai -->

构建日期：2026-11-05。部署程序：target/media-server/MediaServer.exe。

## 来源与依赖

- 官方 ZLMediaKit/ZLMediaKit master 源码快照。
- 官方 ZLMediaKit/ZLToolKit master、ireader/media-server master、open-source-parsers/jsoncpp master。
- cisco/libsrtp v2.7.0，开启 OpenSSL 加密后端。
- OpenSSL 3.6.5，通过本次临时 vcpkg 的 x64-windows-static 构建。
- VS 2022 BuildTools、MSVC 14.44、Windows x64 Release；系统需要对应的 Visual C++ 运行库。本机已验证可以运行。

## 构建范围

启用 SERVER、PLAYER、OpenSSL、WebRTC、MP4 编码辅助支持及 RTPPROXY 编译依赖。上游当前 WebRTCTalk 依赖 RtpSender，需启用该编译选项；运行时关闭相关额外端口，不启用对讲。关闭 C API SDK、测试、SCTP、SRT、视频合成、外部转码库与运行上报。设置 /DNOMINMAX 解决 Windows 宏冲突。

本次裁剪了上游外部进程拉流与截图管理入口、对应源文件编译项和日志配置注册，只保留原生拉流和 WebRTC 播放所需调用链。协议配置禁用分片输出与录制。旧媒体程序已替换，没有备用播放路径。

先将加密库安装至临时 deps，然后向 ZLMediaKit CMake 指定 OPENSSL_ROOT_DIR、OPENSSL_USE_STATIC_LIBS、SRTP_INCLUDE_DIRS 和 SRTP_LIBRARIES。构建时检查输出明确包含 ENABLE_WEBRTC defined；运行后检查 getApiList 包含 /index/api/whep。编译警告不代表运行验收，实际 SDP、ICE、DTLS/SRTP 与浏览器解码验证另见 verification.md。

程序 SHA256：A7C06B9E5E603D4EEBD1D5F8EA3D7BE2A9FA89A92BD4E1CA32FFE66A402EB9BF。

许可证随程序放置：LICENSE、LICENSE-toolkit、LICENSE-media、LICENSE-json、LICENSE-srtp、LICENSE-openssl。OpenSSL 与 libSRTP 静态链接，不需要复制其 DLL。Java JAR 不包含媒体程序，部署必须携带 media-server 目录。临时下载、源码和编译中间目录完成后清理。

## 源码归档校验

| 官方归档 | SHA256 |
|---|---|
| ZLMediaKit master | E4AE406BF5214078020B6E329ACE2885973127D18B1CD1BD97AF1CE83A0FEC08 |
| ZLToolKit master | 566A7C1DC859839E3F79CC7471E17F457B18DD7FEF8F6C7784D88F89D17330B3 |
| media-server master | DB9828AD1D0B28836989FCD362408BD19144E0CD9BC76B87997B632AE7EFE52A |
| jsoncpp master | 841A032A729D4349A3F97C574CA5CFC11BDB0C6C23F84C96F2185B96471D74C1 |
| libSRTP v2.7.0 | 4E9947506D026E696D6688027590EDFDD40A7E6D784C35D0A03BA2635B8E3007 |
