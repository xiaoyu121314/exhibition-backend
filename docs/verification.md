# 当前版本验证记录

<!-- @author MoShangHuaKai -->

## 办公室人数虚高的只读核查（2026-11-06）

本机特征服务健康检查返回ready、模型opencv-sface-2021dec、128维；运行中的后台仍返回PEOPLE_DAILY_DISTINCT，上一轮PEOPLE_DAILY_CUMULATIVE方案未部署。15:43第一次数据库只读快照为371条READY抓拍、42条当日身份，其中6条已归并，剩余36条。随后快照增至378条READY、44条身份、6条已归并、38条有效身份；14条NO_FACE失败不计人数。说明异步提取和部分去重确实生效，不能据此证明38个身份等于38个真实人员。

发现一组相同原始图片MD5、相同设备事件时间的两条抓拍，分别在14:46:41和14:46:47收到，关联到两个不同且未归并的匿名身份，存在实际重复计人。33条早期匿名有效身份中，多数只有1个视角模板；代码仅在匹配分数至少0.65时补充匿名模板，识别阈值本身为0.363，已匹配的中等分数视角不会被学习。匿名身份之间没有后续归并流程，已拆开的身份不能自动收敛。这些是虚高风险，不能仅按用户估计人数降低阈值来证明修复。

另外只向现有本机特征服务重提取了163张设备已确认工作人员的历史抓拍，全部成功；这些样本与对应已保存工作人员模板的相似度均不低于0.363，最低约0.370，其他工作人员相似度最高约0.309。用全部工作人员历史样本作只读诊断时，有4条匿名身份超过0.363，其中最高约0.493；这提示5模板覆盖不足的风险，但没有人工标注，不能把这4条直接判定为工作人员或写回归并关系。

已撤回上一轮“归并记录继续计入totalCount”的源码、测试及说明，恢复真实身份去重口径；对应构建包重命名为`target/cumulative-count-fix/exhibition-backend-1.0.0.jar.withdrawn`，不得部署。强制数字不下降不能替代真实人数去重，否则会永久保留误计。当前未调整工作人员80分阈值、匿名0.363阈值、抓拍配置或业务数据，未替换或重启业务服务。匿名准确率及当天真实人数仍需标注样本核验，不能宣称已经修好现场虚高。

核查工具：`target/dedup-audit/DailyDedupAudit.java`；证据：`target/dedup-audit/daily-summary.json`、`daily-gallery-summary.json`、`duplicate-summary.json`。工具使用只读连接并回滚，只输出匿名统计和相似度，不保存人脸向量。

恢复源码后使用Java 8完整测试：共132项，131项通过、1项现场视频测试跳过，0失败、0错误，耗时49.500秒。记录为`target/dedup-audit/restored-verify.log`。该结果只验证代码恢复和既有功能，没有把模拟向量测试当作现场匿名去重准确率。

## 单帧失败保留既有统计（2026-11-06 13:21）

现场两条NO_FACE任务导致overview把全天totalCount/guestCount返回null。现改为始终返回已完成的当日去重结果，排队状态为PROCESSING，存在失败为PARTIAL，完整完成为AVAILABLE；识别关闭或未启用特征服务仍为null。失败样本不新增人数，也不删除原身份，PARTIAL明确表示可能漏计。

Java 8完整验证132项测试，131通过、1项实体视频测试跳过，0失败、0错误。新增回归覆盖已有工作人员和访客后再遇到排队、NO_FACE失败及重试恢复，始终保留2人的已形成统计。正式后台已备份JAR并重启，大屏同源代理实测totalCount=5、guestCount=4、staffCount=1、personCountStatus=PARTIAL、personCountFailed=2、personCountPending=0，设备心跳在线。

证据：`target/count-partial-fix-tests.log`、`target/count-partial-fix-production-verification.json`。切换前JAR和配置备份位于`target/count-partial-fix-backup-20261106-1320`。本次无数据库迁移，不重置失败记录，不调整识别阈值。

## 本机特征服务及所有人员当天去重（2026-11-06）

新增独立CPU模型服务、官方模型SHA-256校验、异步特征队列、北京时间当日匿名身份、工作人员归并、失败重试及明确的计数可用状态。设备ACK不等待模型；前端不接触特征向量。2026-11-06经用户确认后已执行生产启用：两张新表在MySQL5.7.44创建并核对，模型健康检查通过，新JAR已校验摘要并于12:11重启生效。

生产及大屏同源代理接口均返回PEOPLE_DAILY_DISTINCT、personCountStatus=AVAILABLE、totalCount=3、guestCount=2、staffCount=1；待处理和失败任务均为0。统计起点之后的8条有效抓拍均处理为READY。证据为`target/face-feature-production-verification.json`。切换前JAR、配置和数据库结构备份位于`target/face-feature-deployment-backup-20261106-1208`。核对时设备心跳离线，latestStaff为空；该状态未当成计数清零或填造新识别事件。

Java 8隔离完整构建通过：131项测试，130通过、1项实体视频测试跳过，0失败、0错误。覆盖同人重复/并发去重、不同工作人员隔离、访客归并为工作人员、跨日重置、服务失败、事务回滚、异步入队、诊断详情和Swagger映射。Python6项测试及前端轮询模块3项测试通过。日志：`target/face-feature-java-tests.log`、`target/face-feature-python-tests.log`。

模型HTTP服务已在127.0.0.1:18090验证ready。使用真实已保存抓拍及登记照，提取128维单位向量；同一工作人员抓拍与登记照余弦为0.478，高于参考阈值0.363。10:51:53未确认抓拍与前一抓拍余弦为0.360，处于阈值附近；用户确认现场出现过其他人，但尚未完成多人标注精度评估，不能据此宣称现场识别准确率。

另启动仅监听127.0.0.1:18080、仅使用H2内存库的隔离后端，将真实照片作为匿名测试回调输入。实际模型与完整HTTP链路得到当日特征身份数1→1→1→2→2，重复照片及同人不同照片未重复增加身份。证据：`target/face-feature-http-smoke-results.json`。这些结果属于隔离验证，不是生产访客人数；隔离进程验证后关闭。

独立构建产物为`target/face-feature-staging/exhibition-backend-1.0.0.jar`，SQL为`sql/face-feature-upgrade.sql`，模型安装/启动及前端字段说明见[交接文档](monitor-frontend-handoff.md)。现场页面已确认仍绑定guestCount和示例queue，前端代码不在当前工作区，本次未修改页面。

## 工作人员设备比对接入（2026-11-05）

已实现FaceReco接收与专用可配置ACK、原图摘要及去图片协议、当前设备身份与历史有效区间、重传幂等、本地重新关联、工作人员快照、日去重、大屏10秒卡片和抓拍详情。默认识别关闭，未核实ACK时保存诊断后返回503。没有引入本地人脸算法、每日累加计数或旧抓拍回填。

### 验证结果

| 层次 | 结果与证据 |
|---|---|
| Java 8 + 隔离MySQL 5.7.44 | 完整Maven verify：88项，87通过、1项现场视频测试跳过，0失败/错误；44.800秒。target/recognition-mysql-final-verify.log |
| Java 8 + H2 MySQL模式 | 完整Maven verify：88项，87通过、1项现场视频测试跳过，0失败/错误。target/recognition-h2-final-verify.log |
| 前端模块 | node语法检查及3项时序测试通过。target/recognition-frontend-verify.log；执行node --test src/test/js/monitor-client.test.mjs |
| 显式结构SQL | 独立升级测试库以调整前等价结构保存哨兵档案和旧抓拍后执行SQL；档案保留、旧抓拍FaceSnapshot/NOT_COMPARED，字段类型/可空性差异0，缺少索引0。target/recognition-upgrade-verify.log |
| OpenAPI | 25个业务操作与运行映射一致；包含FaceReco模拟示例及ACK未核实503说明。target/swagger-openapi.json |

新增测试覆盖：有效身份和日去重；未匹配、未知身份、多候选、低分和异常分数；最多3次重试及身份提交唤醒；删除后补传及头像引用保护；跨日/过期不重弹；未核实ACK保留样本；并发重传一条事件及一组媒体；首次启用时间不被重启重置；仅目标设备心跳；提交阶段失败和第二张图写入失败时文件及元数据回滚；抓拍与识别分别保存；更早补传不覆盖最新卡；STAFF快照不被重新关联改写；基线不写设备；身份历史不一致及零阈值拒绝启用。

前端时序覆盖：同一事件重复响应不延时、后续断网仍清空、null人数保留、新人替换、旧事件保护、同人新事件更新、慢请求不重叠、页面卸载取消请求及轮询。使用虚拟时钟验证，不依赖真实浏览器等待。

数据库验证全部使用本轮创建的回环13357端口、工作区target下独立数据目录，不访问业务库；隔离MySQL完成后关闭。正常业务服务未重启，数据库未自动迁移。

### 真机证据与剩余验收

只读查询证据target/recognition-device-readonly.json：识别基本配置开启；规则CompareLimit=1、Push=true、AllDay=true，但FaceGroupList.ControlPersonnelType=None、未选中工作人员库；目标库数字GroupID=0、GroupThresholdValue=0、有效人员1。未改变真机规则、阈值或脸库。

尚未获得真实FaceReco命中与未匹配样本，未确认personId/uniqueId与人员列表身份关系、未验收ACK字段类型及停止重传，因此保持enabled/protocol-verified/ack-verified默认false。docs/examples中的两个JSON明确为SYNTHETIC_ONLY，不能作为真机验收依据。识别准确率、真机ACK耗时及后端提交到页面更新P95≤3秒尚未验收。

交付入口：[接口与前端接入说明](staff-recognition-integration.md)、[轮询模块](examples/monitor-client.mjs)、sql/schema.sql、sql/recognition-upgrade.sql。构建会将说明、示例、SQL与外部配置复制到target同级部署目录，JAR为target/exhibition-backend-1.0.0.jar。

以下为此前各阶段记录，其中“当前接口行为”等描述仅针对对应历史阶段；本次设备比对行为以上文和接入说明为准。

## WebRTC 迁移（2026-11-05）

已实现摄像头 H.264 MAIN → ZLMediaKit → WebRTC。Java 同源代理 WHEP 信令并维护独立观看会话，多人共享一路 RTSP，移除旧分片转发、Cookie、资源路径映射和旧播放示例。每 15 秒心跳、60 秒租约、10 秒巡检；退出独立释放，关闭或空闲回收代理。媒体程序已替换，HTTP 9999 管理入口仅允许回环，WebRTC UDP/TCP 8000，公告 LAN IP 192.168.2.100。密钥继续使用现有文件，IDEA 读取方式保留。脚本使用 UTF-8 BOM，保证 Windows PowerShell 5.1 能解析中文。

### 构建与测试

Java 8 完整 Maven verify 通过：74 项测试全部通过，0 失败、0 错误、0 跳过，耗时约 65 秒。本轮显式开启 LiveStreamCameraTest，使用实际摄像头及 Chrome 生成的 recvonly offer，验证真实 H.264 轨道、WHEP answer 和独立释放。普通环境不设置实体测试变量时为 73 通过、1 跳过。证据：target/rtc-verify.log、target/surefire-reports。

模拟测试覆盖并发观看共享、独立会话、同源 context-path、原文 SDP、Location 地址限制、非法 offer、无效 answer 回收、关闭不影响他人、重复协商、过期、帧停滞、断流、密钥文件及错误脱敏。Swagger 共 21 个操作，与运行映射一致。

### 实际浏览器验证

Browser plugin not available，使用临时 Playwright + 本机 Chrome headless。为避免影响 IDEA 和业务数据库，启动独立 18080 端口的 Java 8 后端，数据源使用内存 H2。实体设备网络恢复后执行：

- 页面身份、有效 DOM、无框架错误覆盖层、无 JavaScript pageerror；1365×900 桌面和 390×844 手机尺寸无横向溢出。停止后显示“播放已停止”，重新连接后恢复。
- 两个浏览器观看会话均 connected，实际 videoWidth=2592、videoHeight=1944，稳定阶段约 25 fps。关闭其中一个后另一个 framesDecoded 从 8 增至 122、bytesReceived 继续增长，0 丢帧；重连获得新会话。
- 只删除验收后端对应的原生拉流代理，播放器自动重新获取会话并恢复解码；这是服务端拉流中断模拟，没有断开现场物理网络。
- 新会话连续播放 65 秒后仍 connected，framesDecoded=1634，心跳未使其过期。停止播放约 60 秒后 getMediaList 中该代理消失。
- 真实 API 配置确认 HTTP 9999、回环 IP 白名单、rtc.externIP=192.168.2.100、UDP/TCP 8000。API 列表包含 WHEP。
- 新构建日志中设备密码与管理密钥命中数为零，接口不返回远端释放 token。JAR 无旧播放资源或旧工具条目，项目文本及媒体程序的旧工具标记为零；临时原生源码、下载和旧视频日志已移除。

浏览器证据暂存于 Codex visualization 工作目录：rtc-camera-results.json、rtc-lifecycle-results.json、rtc-camera-playing.png、rtc-camera-mobile.png。验收临时进程完成后关闭，正式访问需重启 IDEA 后端加载新代码。

### 待现场确认的范围

设备 GET /Streams/1/1 回读 FrameRate=2500、keyFrameInterval=25；稳定运行的媒体轨道实际 fps=25、gop_size=100、gop_interval_ms 约 3989，说明关键帧参数回读与实际输出仍有差异。本轮没有重启摄像头。WebRTC 不等待分片，实际画面已通过，但未使用同步实物时钟测量端到端延迟，不能宣称固定毫秒数。

尚未验证远端大屏浏览器、跨公网、长时间运行和 Windows 防火墙跨机可达性。远端大屏须连接媒体电脑 UDP/TCP 8000，IP 变化后重新运行 start-media.ps1 -RtcAddress 实际IP；Java HTTP 入口仍是 8080。仓库没有原大屏前端工程，已提供内置播放页和复用组件，需要在实际大屏接入该组件或 iframe。

验证日期：2026-11-03。本次移除本地人脸服务及全部调用链，保留工作人员维护、照片存储、Excel 导入导出、实体设备同步、摄像头心跳和抓拍接收。

## 清理范围

- 删除 Python 推理服务、模型、虚拟环境、依赖清单、模型下载与启动脚本，以及依赖本地识别的 HTTP 冒烟脚本和缓存。
- 删除 Java 特征客户端、向量匹配、识别任务和日内身份去重实现。
- 删除本地识别配置、人员建模状态、头像相似度预检、抓拍识别重试与身份分类筛选。
- 从初始化建表脚本移除特征模板、每日身份、每日汇总三张表，以及抓拍记录中的特征分数、身份关联和重试字段；不执行旧库迁移。
- Maven 部署文件不再包含 Python 服务及启动脚本，已清理此前的对应构建副本。

## 当前接口行为

- 人员详情的照片列表直接读取档案 avatarId，不再查询识别模板。
- 单条维护和整批导入继续先同步设备再提交数据库；设备失败或数据库提交失败仍执行原有补偿。
- 抓拍保存后为 RECEIVED；超过保留期限的补传为 EXPIRED，不保存图片。相同报文重传不会重复入库。
- 大屏 recognitionStatus 为 DISABLED、countMode 为 NONE；人数、统计更新时间和最近工作人员为 null。保留摄像头心跳在线状态和最近抓拍时间。
- Swagger 当前共15个业务操作，运行时映射与文档逐一核对；不再提供抓拍识别重试接口。

## 已完成验证

使用 Java 8 和 Maven 执行 scripts/build.ps1，47项测试通过，0失败、0错误、0跳过；包含38项后端集成测试、3项运行目录测试、6项设备安全码测试。完整验证与打包耗时46.522秒。

测试覆盖人员及图片维护、Excel整批导入导出、设备离线/鉴权/超时/业务拒绝、设备及数据库失败补偿、抓拍幂等、RECEIVED最终状态、大屏无统计来源时返回null，以及应用不再注册本地识别组件。

证据：target/remove-local-face-verify.log、target/surefire-reports/、target/swagger-openapi.json。构建产物：target/exhibition-backend-1.0.0.jar。

本轮使用H2与HTTP模拟设备；未修改运行中的业务数据库、调用现场设备或重启运行服务。已有数据库应按新版schema初始化，本次不处理旧数据兼容。

## 工作人员新增连接中断排查（2026-11-03）

现场日志显示查询成功，照片新增接口在58ms后发生SocketException，随后查询成功但无法确认新增身份。设备只读检查确认目标脸库、拥有者配置和时钟一致；额外使用无效字段及空文件探测上传入口，没有提交真实人员照片，不能据此认定新增成功或原接口不受支持。

照片上传调整为浏览器FormData的分段头格式，保留UTF-8文本、JPEG文件和请求总长度，不改变业务字段、接口、身份关联及补偿规则。强制Connection: close在Java 8本机模拟测试中引发查询断连，最终实现未采用该设置。

Java 8下53项测试通过：44项后端集成测试、3项运行目录测试、6项安全码测试。新增覆盖严格multipart格式下中文姓名及照片新增/替换，以及设备已写入后断开响应时只查询补偿、不重发新增。证据：target/staff-multipart-build.log及target/surefire-reports/。现场实际照片新增仍待重新编译、重启IDEA应用后复测。

## 工作人员设备照片规格修正（2026-11-03）

现场再次出现76ms断连，说明multipart格式调整未解决现场问题。只读检查实际上传文件，最近两种尺寸为3840×2160和1280×1706。厂商脸库指导正文要求YUV 4:2:0、像素不超过300×300；原StorageService只转JPEG并保留原尺寸，缺少设备照片缩放。

工作人员单张上传和Excel预检现共用normalizeStaffImage，等比缩小到最长边300像素，小图不放大、不裁剪；抓拍和背景图保留原分辨率。设备发送前校验尺寸，超规格旧imageId明确要求重新上传。发送和落盘复用同一份JPEG字节。

Java 8完整构建成功，54项测试通过，0失败、0错误、0跳过，耗时48.906秒。新增测试覆盖大图单张上传、Excel导入、设备与本地照片字节一致、实际JPEG 4:2:0采样、抓拍分辨率保持，以及旧大图不向设备发送。证据：target/staff-photo-size-build.log、target/surefire-reports/。尚未使用缩放后的实际工作人员照片完成现场新增，不能将本机测试写成现场问题已解决；需重启应用并重新上传照片复测。

## 工作人员上传请求头修正（2026-11-04）

先复现查询约5秒断连：默认网络路径经FlClash TUN失败，curl绑定WLAN后首页90ms、脸库查询145ms成功。临时直连路由命令因Windows管理员权限不足未执行成功，未修改系统路由；随后默认访问及应用查询自行恢复，新增仍在34ms断连，属于另外一个上传问题。

使用无效安全码、不存在的脸库及100×100无人物测试JPEG，保持同一份multipart二进制请求体不变，只修改外层Content-Type：`multipart/form-data;boundary=...`得到HTTP 415（http upload failed），`multipart/form-data; boundary=...`得到HTTP 200和业务码4208。确认固件对boundary前的分隔空格存在解析限制；此前仅调整分段头和图片尺寸没有覆盖这个外层头问题。

客户端在FormHttpMessageConverter序列化后通过拦截器补齐该空格，保留原boundary、请求体及补偿策略，不增加写请求重试。模拟设备严格模式同步复现缺少空格即返回415，现有中文姓名、照片新增及替换测试覆盖修正后的实际请求头。

修复前实际Java 8 DeviceFaceClient使用诊断数据得到HTTP 415；修复后抓取本机真实请求头确认分隔空格存在，随后用正确拥有者及不存在的脸库请求现场设备，正常收到HTTP 200及业务码4098。此为无效脸库诊断，未新增真实工作人员，仍需重启IDEA应用完成实际人员新增验收。

Java 8完整构建成功，总计65项测试，64项通过、1项现场视频测试跳过，0失败、0错误，耗时50.183秒。证据：target/staff-multipart-boundary-build.log、target/surefire-reports/、target/p6s-header-no-space.txt及target/p6s-header-with-space.txt。构建产物：target/exhibition-backend-1.0.0.jar。

## 设备原生人员空FaceUUID处理（2026-11-04）

现场查询成功但被人员解析拦截。只读核对XML及用户截图：1条记录具有PersonID=0、数字GroupID、FaceVersion=0，FaceUUID为空。原实现要求列表每条人员均有FaceUUID，导致设备已有原生人员时无法新增后台人员。

查询快照现仅收集真实FaceUUID。FaceUUID为空时，原生PersonID必须为数字且GroupID与目标脸库一致，并且跨页不重复；符合条件的原生记录保留在设备上但不参加UUID关联。数字PersonID不替代FaceUUID，原生记录不被修改或删除；新增仍只能绑定创建前不存在的唯一FaceUUID。缺少有效身份、重复原生ID或重复FaceUUID继续中止维护。分页仍按原始记录数判断结束，不能按过滤后的UUID数量提前结束。

Java 8完整构建成功，总计70项测试，69项通过、1项现场视频测试跳过，0失败、0错误，耗时56.605秒。新增覆盖原生记录存在时的新增、原生记录在数据库失败补偿后保留，以及无效身份和重复ID在设备写入前被拦截。另对第一页100条原生记录、第二页存在UUID人员的场景加强定向分页验证。证据：target/staff-native-identity-build.log、target/staff-native-pagination-test.log。

修正后用实际Java 8 DeviceFaceClient只读查询现场设备成功，UUID快照为空；该设备原有1条无FaceUUID的原生记录未被写入或删除。本轮未执行真实工作人员新增，需重启IDEA应用后继续人员新增验收。

## 手机JPEG的EXIF方向修正（2026-11-06）

现场上传收到HTTP 200及业务码4098。重新读取厂商错误码说明，4098明确为图片中未找到人脸，4099才是多张人脸。用户原图1192678字节，原始像素3088×2316，EXIF Orientation=5；浏览器显示正向，而此前后台转码忽略方向并去除元数据，实际存储JPEG为横置图片。

StorageService在解码重编码前读取JPEG APP1的EXIF主目录方向，覆盖1至8全部旋转和镜像形式，方向变换与缩放合并绘制一次，仍输出RGB JPEG并移除其他元数据。解析先检查字节序、TIFF头、目录偏移、条数及字段类型，对损坏和越界EXIF保持普通图片处理，不读取段外数据。单张上传和Excel共用同一处理，既有已去除EXIF的imageId不自动改写。

Java 8完整构建成功，总计94项测试，93项通过、1项现场视频测试跳过，0失败、0错误，耗时48.126秒。新增StorageServiceTest通过四色JPEG实际像素验证8种方向及大小端字节序、方向5缩放、重复转码不重复旋转，以及缺失/非法/越界EXIF。证据：target/staff-exif-orientation-build.log。

使用生产normalizeStaffImage方法本地处理用户原图，结果225×300、13519字节，已目视确认正向，与浏览器中的原图方向一致。验证图片位于target/IMG_2060-normalized.jpg。本轮未向设备写入或删除人员，设备是否接受正向照片仍需重新上传后验证。

## 厂商状态码中文原因返回（2026-11-06）

已登录读取[厂商错误码说明](http://xrs-api.p6sai.com:8091/project/11074/interface/api/72673)，新增P6sStatusCodes本地不可变映射，覆盖成功码0及123个非零状态码。范围为1～3、-1～-46、4097～4112、4196～4208、16001～16009、18001～18036；静态检查确认124个码值无缺失、无重复。修正厂商原文的明显笔误和4112图片尺寸的断行，保持原始含义。

DeviceFaceClient统一返回中文原因、厂商状态码和本次固定接口路径，沿用BusinessException的HTTP400、code=400、data=null响应；工作人员维护继续追加本地提交/补偿结果及操作ID，Excel导入错误行保留相同原因。只读诊断逐项保留DeviceSyncException中的具体错误。未知整数保留码值并显示“未知厂商错误”，非法及超长码值显示“未知”；不拼接设备原始message或requestURL。

厂商定义明确：-2为编解码失败，4098为图片中没有找到人脸，4099为图片中人脸数超过一个，4101为人脸算法未初始化，4112为图片像素过大，4208为安全码不匹配。模拟设备安全码拒绝改为4208、人员ID无效改为4204，原有负数码注释同步纠正。

Java 8完整Maven verify通过：总计114项测试，113项通过、1项现场视频测试按默认条件跳过，0失败、0错误，耗时47.033秒。新增20项测试场景覆盖公共命令非零码、负数码、人脸错误、中间层错误、升级错误、未知整数及超过int上限的码值、非法/超长码值过滤、HTTP400响应、逐项诊断、缺失/空白状态码及无效XML的结果不确定标志；同时加强删除失败不提交本地、导入错误行及整批补偿的断言。既有成功写入、超时、断连和补偿测试一并通过。

证据：target/p6s-status-codes-build.log、target/surefire-reports/。构建产物：target/exhibition-backend-1.0.0.jar。本轮使用H2内存数据库和本机HTTP模拟设备，未调用现场设备或业务数据库，未重启运行中的业务服务。
