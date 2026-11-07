# P6S 人员同步、识别回传与客流统计接口参数清单

<!-- @author MoShangHuaKai -->

核对日期：2026-11-04。范围为当前项目及上一份方案分析涉及的接口，不包含厂商整个产品线的全部 API。区分当前代码已调用、方案待接入、设备初始化及可选诊断；本文不表示已修改设备或实现待接入功能。

## 1. 调用方向、地址和身份字段

| 方向 | 基础地址 | 数据格式 | 当前认证方式 |
| --- | --- | --- | --- |
| Java 后台 → 设备管理接口 | `http(s)://设备IP[:端口]` | XML；照片为 multipart/form-data | HTTP Basic，使用设备管理账号密码；脸库写操作另带安全码 |
| 设备 → Java 后台 | `http(s)://后台IP:8080/api/device/p6s/events` | application/json | 项目当前不校验 OAuth/Authorization，按 serialNumber 匹配配置 |
| 管理前端/大屏 → Java 后台 | `http(s)://后台IP:8080` | JSON、multipart、图片/Excel 二进制 | 项目当前无需访问令牌 |

厂商文档站账号只用于读取文档，不是现场设备账号，也不是后台接口账号。

| 名称 | 含义及来源 | 不应混用 |
| --- | --- | --- |
| `staffId` / 后台路径 `id` | 本地工作人员 UUID | 不是设备 FaceUUID |
| `staffNo` | 后台分配的工号，文本保留前导零 | 不是人员身份映射键 |
| `imageId` / `avatarId` | 本地媒体 ID | 不是设备人员 ID |
| `GroupID` | 设备数字脸库编号，查询接口使用 | 不等于字符串 GroupID2 |
| `GroupID2` | 创建脸库时的字符串标识 | 对应配置 face-group-id |
| `FaceGroupID` | 人员写接口中的目标脸库/组织字符串标识 | 当前项目使用 GroupID2 |
| `FaceUUID` | 设备人员身份，项目保存于 exh_staff_device | 与 HTTP 回调身份关系需真机确认 |
| `personId` / `uniqueId` | FaceReco 协议中的设备身份/平台身份 | 不直接假定等于 FaceUUID |
| `Ownner`（拥有者接口） | 设置并保存的拥有者原值，拼写为两个 n | 不是设备密码或回调 Token |
| `Ownner`（脸库写接口） | 由拥有者原值与时间计算的安全码 | 不能直接填拥有者原值 |

## 2. 当前后台实际调用的 5 个设备接口

依据 `DeviceFaceClient.java`。除照片外，XML 根节点和字段名区分大小写。

### 2.1 查询所有脸库

`PUT /FaceGroups/QueryAll`，无请求体参数。来源：[厂商参数页](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227008)。

返回根节点 `FaceGroups`，包含多个 `FaceGroup`。字段包括 `GroupID`、`GroupID2`、`GroupName`、`CreateTime`、`PersonInfoCount`、`AvailableStatus`、`ValidPersonCount`、`InvalidPersonCount`、`GroupThresholdValue`；实际示例还包含 `Version`。

当前项目用 GroupID2 选择目标库，再取数字 GroupID 查询人员。文档对 AvailableStatus 的文字解释与常见语义不一致，应核对实机；当前代码不依据该字段判断成功。

### 2.2 查询脸库人员列表

`PUT /FaceGroup/QueryPersonInfoList`，XML 根节点 `PersonInfoFilter`。来源：[厂商参数页](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227018)。

以下为当前代码发送的全部字段：

| 字段 | 类型 | 当前发送值/约束 |
| --- | --- | --- |
| GroupID | integer | 目标脸库数字编号，由 QueryAll 获取 |
| Name | string | 空字符串，查询全部姓名；设备姓名查询为精确匹配 |
| Sex | string | 空字符串，不筛选 |
| CertificateType | string | 空字符串，不筛选 |
| CertificateID | string | 空字符串，不筛选 |
| Country | string | 空字符串，不筛选 |
| Address | string | 空字符串，不筛选 |
| StartBirthday | string | 空字符串，不筛选 |
| StopBirthday | string | 空字符串，不筛选 |
| ModelState | string | 空字符串，不筛选 |
| OrganizationID | string | 空字符串，不筛选组织 |
| Pagesize | integer | 100 |
| Pagenum | integer | 从 1 开始，逐页查询 |

当前期待响应根节点 `PersonInfoList`，每个 `PersonInfo` 读取 `FaceUUID`、`Name`、`Sex`、`CertificateType`、`CertificateID`、`Country`、`Address`、`Birthday`。厂商还列出 `GroupID`、`PersonID`、`FaceImageID`、`ModelState`、`PassPermissionEnable`、`ValidPeriod`、`FaceVersion`、`Group` 等返回字段；当前代码未保存建模状态。

厂商 XML、字段表和示例对部分筛选字段、空值要求存在差异。这里明确列的是项目现有报文，不将其等同于全部固件均已验证的请求模板。

### 2.3 新增人员或更新照片

`POST /FaceGroup/UpdatePersonInfoAndFaceImage`，`multipart/form-data`。来源：[详细参数](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227016)、[指导文档](http://docs.p6sai.com/docs/cn/device/facemanage.html)。

| 字段 | 类型 | 必填 | 含义/项目用法 |
| --- | --- | --- | --- |
| Name | string | 是 | UTF-8 姓名；文档最大长度 32 |
| Sex | string | 是 | male / female |
| Ownner | string | 是 | 本次请求安全码 |
| FaceGroupID | string | 是 | 配置脸库的 GroupID2 |
| FaceUUID | string | 是，新增空值有文档冲突 | 项目新增传空；更新传已有设备 FaceUUID |
| SystemTime | string | 是 | `yyyy-MM-dd'T'HH:mm:ss`，与安全码使用同一时间字符串 |
| file | binary | 是 | JPEG 文件，当前文件名 face.jpg |

当前项目登记照片最长边最多 300 像素，JPEG 4:2:0；文本部分用 UTF-8，不单独添加 Content-Type/Content-Length，外层表单有完整 Content-Length。boundary 由客户端生成。

当前期望 `ResponseStatus.statusCode=0`；文档示例还包含 `requestURL` 和 `MD5`。此接口的成功响应不直接提供当前代码所需的新 FaceUUID，项目通过新增前后列表差异确认。空 FaceUUID 的适用性必须真机验证。

### 2.4 修改人员资料

`PUT /FaceGroup/ModifyPersonInfo`，XML 根节点 `PersonInfo`。来源：[厂商参数页](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227015)。

| 字段 | 类型 | 当前用法 |
| --- | --- | --- |
| Ownner | string | 本次请求安全码 |
| SystemTime | string | 与安全码配套的时间 |
| FaceGroupID | string | 目标 GroupID2 |
| FaceUUID | string | 已关联人员设备身份 |
| Name | string | 后台维护的姓名，非空 |
| Sex | string | male / female |
| CertificateType | string | 保留设备原值；为空时填 none |
| CertificateID | string | 保留设备原值，可为空 |
| Country | string | 保留设备原值，可为空 |
| Address | string | 保留设备原值，可为空 |
| Birthday | string | 保留设备原值，可为空 |

以上均由代码发送。厂商还列有 `PersonID`，但标为新版本不使用、可不传；当前代码不发送。返回 `ResponseStatus`，字段为 `requestURL`、`statusCode`、`message`，项目要求 statusCode=0。

### 2.5 从目标脸库移除人员

`PUT /FaceGroup/DeletePersonList`，XML 层级 `PersonInfoList/PersonInfo`，可包含多个人员节点；项目逐人发送。来源：[厂商参数页](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227014)。

| 字段 | 类型 | 当前用法 |
| --- | --- | --- |
| Ownner | string | 本次请求安全码 |
| SystemTime | string | 与安全码配套的时间 |
| FaceGroupID | string | 仅操作配置的目标脸库 |
| GroupID | integer | 查询目标脸库得到的数字 ID |
| PersonID | integer | 新版按 FaceUUID 定位，传 0 以满足现场固件解析 |
| FaceUUID | string | 要移除的设备人员身份 |
| IsDeletePicture | boolean | false，仅移出指定脸库 |

虽然厂商参数页将 `GroupID`、`PersonID` 标为新版本可不传，现场固件对缺少这两个字段的删除请求返回 HTTP 200、业务码 -2；补齐目标数字 `GroupID` 和 `PersonID=0` 后返回业务码 0。返回 `ResponseStatus`，项目要求 statusCode=0。厂商说明 true 可能涉及该人员在其他脸库中的关联，因此保持 false。

## 3. 设备初始化及功能配置接口

这些接口可用于部署配置或后续开发，当前人员同步代码不会自动设置拥有者、创建脸库或修改识别规则。已有有效配置可先查询核对。

### 3.1 能力查询

| 接口 | 请求参数 | 需关注的返回能力 |
| --- | --- | --- |
| `GET /System/DeviceCap` | 无 | 通道人脸识别 FaceReco、脸库配置版本及相关设备能力 |
| `GET /System/AICap` | 无 | AI 通道 FaceSnap、人脸比对、特征推送及客流能力，具体结构随设备能力版本变化 |

来源：[能力定义](http://cap.p6sai.com/DeviceCap.htm)、[抓拍配置中的 AI 能力说明](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227114)。能力树是返回内容，不是请求参数。

### 3.2 读取/设置脸库拥有者

`GET /System/FrontDeviceOwnnerInfo`：无请求体；响应根节点 `FrontDeviceOwnnerInfo`，字段包括 `Ownner`、`UpdateFlag`、`WholeVersion`、`WholeGroupUUID`。

`PUT /System/FrontDeviceOwnnerInfo`：根节点 `FrontDeviceOwnnerInfo`，只写入 `Ownner:string`（必填，拥有者原值）。字段表上限 64，而 XML 注释写 32，长度有冲突，应以固件确认值为准。来源：[GET](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227003)、[PUT](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227004)。

### 3.3 创建/修改脸库

| 接口 | XML 根节点 | 参数 |
| --- | --- | --- |
| `PUT /FaceGroups/Create` | FaceGroup | GroupName:string 必填；GroupID2:string 必填；Ownner:string 必填安全码；SystemTime:string 必填；GroupThresholdValue:float 可选，0～100 |
| `PUT /FaceGroups/Modify` | FaceGroup | GroupName、GroupID2、Ownner、SystemTime；另有旧数字 GroupID，文档标为设备不处理 |

创建响应包含 `GroupID2`，实机示例还包含数字 `GroupID`。修改响应使用 `ResponseStatus`。指导文档提到修改阈值，但当前详细修改页没有完整列出 GroupThresholdValue 参数；修改阈值前需核对实际固件契约。当前项目不调用这两个接口。来源：[创建](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227005)、[修改](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227006)。

### 3.4 安全码公共参数

```text
SystemTime = 同一请求使用的北京时间字符串 yyyy-MM-dd'T'HH:mm:ss
Ownner安全码 = reverse(lowercaseHex(MD5(拥有者原值 + SystemTime))[0:8])
```

脸库写接口中的 Ownner 传安全码；拥有者设置接口中的 Ownner 传原值。项目由 `DeviceFaceClient.authorized()` 调用安全码工具，不使用文档示例中的模拟序列号生成设备请求。来源：[脸库指导](http://docs.p6sai.com/docs/cn/device/facemanage.html)。

### 3.5 AI 事件上传开关

`GET /System/AIEventCfg` 无请求体。`PUT /System/AIEventCfg` 使用根节点 `AIEventCfg`，参数 `Enable:boolean` 必填、`HttpType:string` 必填（http / https）。文档字段表将 Enable 描述成电子围栏功能，与页面总述不一致，应核对设备。来源：[AI 事件配置](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Configuration/AIEventConfigurationDescription.html)。

设备网页还需设置：启用 HTTP 平台、协议、后台服务器 IP/域名、端口、URL 路径 `/api/device/p6s/events`、认证方式和超时时间。当前后台对应“无”认证；Token/密钥无需填写。本文已查到的说明采用网页配置，未据此虚构一个平台服务器配置 CGI 路径。来源：[无认证配置说明](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Configuration/NoAuth.html)。

### 3.6 人脸识别基本配置

`GET/PUT /FaceReco/{ChannelID}/BaseConfig`，ChannelID 为实际通道，文档示例为 1；GET 无请求体，PUT 根节点为 `FaceBaseConfig`。来源：[读取](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227022)、[写入](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227023)。

| 字段 | 类型 | 范围/作用 |
| --- | --- | --- |
| EnableRecognition | boolean | 人脸识别总开关，本方案需要开启 |
| OpenDoorMode | integer | 0～8，门禁相关模式，按设备原配置保留 |
| OverlayHumanBox | integer | 0 不支持、1 启用、2 禁用 |
| Senstive | integer | 原拼写；灵敏度 0～100，0 可表示不支持 |
| EnableForceOutCardNumber | boolean | 强制输出卡号开关 |
| SyncPictureOpt | integer | 0 关闭、1 一直拍照、2 开门时拍照、3 不开门时拍照 |
| OpenDoorDelay | integer | 开门延时，毫秒，范围需设备确认 |

PUT 参数表均标为必填。应先 GET 并保留适用字段，再修改所需项；不要只凭本表生成不完整设备配置。

### 3.7 人脸抓拍配置

`GET/PUT /AI/FaceSnapshotCfg`，GET 无请求体，PUT 根节点 `AIFaceSnapshotCfg`。来源：[读取](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227113)、[写入](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227114)。

| 字段/结构 | 类型 | 作用和范围 |
| --- | --- | --- |
| Enable | boolean | 开启抓拍 |
| BreathMaskDetectEnable | boolean | 口罩检测 |
| SnapshotMode | string | quality / fast；GET 页另列 custom，需核实固件 |
| FaceQuality | integer | 20、40、60、80、100 |
| ShowFaceFrame | boolean | 显示人脸框 |
| ShowSchedFrame | boolean | 显示布防框 |
| IsCaptureBackground | boolean | 是否附带背景图 |
| MaxPitchAngle / MaxYawAngle / MaxRollAngle | integer | 各 0～90，自定义模式相关 |
| MaxAmbiguity | integer | 0～100，最大模糊度 |
| MaxFilterDuration | integer | 0～30000 毫秒，最大筛选时长 |
| MinFaceSize | integer | 20～500，最小人脸尺寸 |
| IsPushFaceFeatures | boolean | 可选，特征推送；不能据此认定获得可比较向量 |
| Polygon.Point[] | object | 可选；ID:integer 0～7，PosX/PosY:integer 各 0～10000 |
| VoiceAlarm | object | 可选；VoiceType:string、BroadcastCount:integer 1～10、BroadcastVolume:integer 0～100、VoiceAlarmSchedule、EventVoiceAlarmTimeSlotList |
| Trigger | object | 联动结构，见 3.8 公共联动字段；本页还列 FaceMask.Enable、LightAlarm.Enable |
| Schedule | object | AllDay 与 TimeBlockList.TimeBlock_0～TimeBlock_6，见 3.8 |
| EventTimeSlotList.SchedTimeSlot[] | object | ID、Enable、StartTime、EndTime、Weekday；见 3.8 |

除 IsPushFaceFeatures 及可选结构外，PUT 页主要字段标为必填。VoiceAlarmSchedule 可含 DayMode、AllDay 和 TimeBlockList；其替代时间段列表路径为 `VoiceAlarm.EventVoiceAlarmTimeSlotList.SchedTimeSlot[]`，各元素字段与普通时间段一致。声音类型取设备支持的字符串枚举，本方案不需要启用声音联动。

### 3.8 人脸识别规则及公共时间/联动结构

`GET/PUT /FaceReco/{ChannelID}/RecoRuleList`，GET 无请求体，PUT 根节点 `RecoRuleList`，子节点 `RecoRule` 可重复。来源：[读取](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227024)、[写入](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227025)。

| 字段路径（相对 RecoRule） | 类型 | 说明 |
| --- | --- | --- |
| Enable | boolean | 实际 GET 示例包含，部分字段表省略；保留实际节点并确认 |
| RecognitionRule | string | Comparison pass / Comparison fail |
| CompareLimit | integer | 0～5，比对候选上限，不是人数统计 |
| FaceGroupList.ControlPersonnelType | string | None / Stranger / Everyone / OrganizationMember；示例存在 EveryOne 大小写差异 |
| FaceGroupList.FaceGroup[].Enable | boolean | 是否选中该脸库项 |
| FaceGroupList.FaceGroup[].GroupID | integer | 旧数字字段，详细表称新版本不使用 |
| FaceGroupList.FaceGroup[].GroupName | string | 脸库名称 |
| Schedule.DayMode | string | disable / day / evening；部分页面还列 unknown |
| Schedule.AllDay | boolean | 全天布防；仅在相应 DayMode 下有效 |
| Schedule.TimeBlockList.TimeBlock_0～TimeBlock_6 | string | 每项 48 位 0/1，周日至周六，每位半小时 |
| EventTimeSlotList.SchedTimeSlot[].ID | integer | 时间段编号 |
| EventTimeSlotList.SchedTimeSlot[].Enable | boolean | 时间段是否启用 |
| EventTimeSlotList.SchedTimeSlot[].StartTime / EndTime | string | 字段表为 hh:mm；XML 注释存在日期时间示例差异 |
| EventTimeSlotList.SchedTimeSlot[].Weekday | string | 前 7 位对应周日至周六，第 8 位保留；长度需按设备确认 |
| Trigger.NotifyAMS.Enable | boolean | 报警中心 |
| Trigger.BeepAlert.Enable | boolean | 声音警告 |
| Trigger.FullScreen.Enable | boolean | 屏幕显示 |
| Trigger.Mail.Enable | boolean | 邮件联动 |
| Trigger.Ftp.Enable | boolean | FTP 联动 |
| Trigger.Push.Enable | boolean | 告警推送，不能未经验证就视为 HTTP 上传总开关 |
| Trigger.AlarmOut.Enable / AlarmOutMask | boolean / 位掩码 | 报警输出 |
| Trigger.Snapshot.Enable / SnapshotMask | boolean / 位掩码 | SD 抓图 |
| Trigger.Record.Enable / RecordMask | boolean / 位掩码 | 录像 |
| Trigger.PTZ.Enable | boolean | 云台联动 |
| Trigger.PTZ.PTZActionList.PTZAction[].ChannelID | integer | 云台通道 |
| Trigger.PTZ.PTZActionList.PTZAction[].ActionName | string | none / preset / pattern / patrol |
| Trigger.PTZ.PTZActionList.PTZAction[].ActionNum | integer | 0～255，动作编号 |
| Trigger.FaceMask.Enable | boolean | GET/相关配置页出现，PUT 示例不完整，需固件确认 |
| Trigger.LightAlarm.Enable | boolean | 灯光报警，部分 XML 示例省略 |

文档对掩码的 string/integer 表述不统一，不能统一强制转换。两种布防计划并非自动同步；需要按设备当前使用的结构维护。完整规则字段很多，但本方案应重点确认识别对象、目标脸库、全天布防及所需事件能够回传。

## 4. 设备事件共用的一个后台入口

所有事件均向 `POST /api/device/p6s/events` 发送 JSON，使用 operator 区分业务，不是为每个事件分别新增一个 URL。

| operator | 当前项目状态 | 用途 |
| --- | --- | --- |
| heartbeat | 已实现 | 心跳及接收后台策略 |
| FaceSnapshot | 已实现 | 抓拍图片入库 |
| FaceReco | 已实现接收，默认关闭，待真机契约验收 | 人脸比对身份结果 |
| PassengerFlowStatics | 可选，待实现 | 时间段进出流量 |
| PeopleNumberStatistics | 可选，待实现 | 区域人数快照 |

### 4.1 公共事件信封

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| operator | string | 必填，严格使用上表拼写 |
| deviceInfo.serialNumber | string | 必填，匹配后台已配置设备 |
| deviceInfo.platformId | string | 普通事件文档可选；心跳示例写 platformID，大小写不同 |
| info.eventId | integer（厂商） | 必填；当前代码也接受数字字符串，不能单独作全局身份 |
| info.time | string | 必填，事件发生时间；以北京时间归属业务日 |

当前代码支持 ISO 带偏移时间、`yyyyMMdd'T'HHmmssX`，以及无偏移的 ISO/紧凑时间；无偏移按北京时间。未来超过 5 分钟的报文会被拒绝。当前总 JSON 请求最大 4MB，operator 最大 64 字符、序列号最大 128、事件编号最大 64、时间最大 64。

### 4.2 heartbeat 请求和 ACK

除公共字段外，厂商心跳文档列出：

| 字段 | 类型 | 文档要求/当前处理 |
| --- | --- | --- |
| deviceInfo.IP | string | 文档必填；当前未使用 |
| deviceInfo.MAC | string | 文档必填；当前未使用 |
| deviceInfo.DID | string | 文档可选 |
| deviceInfo.SN | string | 示例包含，参数表未单独定义 |
| deviceInfo.deviceType | string | 文档必填；当前未使用 |
| deviceInfo.deviceVersion | string | 文档必填；当前未使用 |
| info.strategyVersion | string | 文档必填；当前未使用 |

ACK 为 `operator=heartbeat-Ack`，`info.eventId` 回显，`info.time` 为后台时间，`info.eventSendMode` 为 realTime 或 retransmission，`info.strategy` 为策略对象，`result.errorNo=0`、`result.description=ok`。

strategy 的字段如下；当前代码完整发送这些开关。来源：[心跳文档](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/HeartbeatEvent.html)。

| 参数 | 类型 | 当前值 | 方案用法 |
| --- | --- | --- | --- |
| heartBeatInterval | integer | 默认 30 | 秒，来自 heartbeat-seconds 配置 |
| passengerStaticsInterval | integer | 1 | 小时；非秒级间隔 |
| isSnapshotEnable | boolean | true | 允许抓拍 |
| isPersonInfoEnable | boolean | false | 接入 FaceReco 后才开启 |
| isPassengerFlowStaticsEnable | boolean | false | 接入进出流量时开启 |
| isPeopleNumberStatisticsEventEnable | boolean | false | 接入区域人数时开启 |

其他当前发送 false 的全部开关为：

```text
isEnableElectronicDefence
isCrossBorderDetectEnable
isOffDutyDetectEnable
isCryScreamDetectEnable
isPetDetectEnable
isFallDetectEnable
isPersonDetectEnable
isCarLicenseSnapshotEnable
isCarDetectEnable
isTimedSnapshotEnable
isGroundLockStatusChangedEnable
isStartupReportEnable
isAbnormalEventEnable
isNonWhitelistCarEnable
isMotionDetectEnable
isTrafficStatisticsEnable
isTrafficStatisticsCarShapeEnable
isKey2CallEnable
isLicensePlateSurveillanceEnable
isFireDetectEventEnable
isVideoCoverEventEnable
isElectricBikeEventEnable
isGasContainerEventEnable
isElectronicDefenceV2EventEnable
isPeopleNumberOverLimitEventEnable
```

### 4.3 FaceSnapshot 请求和 ACK

除公共字段外的 info 参数：

| 字段 | 类型 | 文档要求/当前处理 |
| --- | --- | --- |
| direction | string | 文档必填 enter / exit / unknown；当前未保存 |
| trackId | integer/设备原标记 | 文档必填；当前可缺省为空，最长 64 字符，不是全天身份 |
| faceNumber | integer | 文档必填；当前未使用 |
| faceFeature | object | 属性结构，见 4.5；当前未保存 |
| CaptureImage | object | 当前必须有可解析 picture，图片结构见 4.6 |
| BackgroundImage | object | 当前可选；存在非空 picture 才保存 |

ACK 精确结构为 `operator=FaceSnapshot-Ack`，`info.eventID` 回显请求的 info.eventId，注意大写 D；result.errorNo=0、description=ok。来源：[抓拍文档](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/FaceSnapshotEvent.html)。

### 4.4 FaceReco 请求和 ACK（已实现可配置接收，待真机验收）

除公共字段外的 info 参数：

| 字段 | 类型 | 文档要求/含义 |
| --- | --- | --- |
| direction | string | 必填，文档固定 none |
| matchNumber | integer | 必填，0 为未匹配，正数为候选数量，不是进入人数 |
| faceFeature | object | 必填属性结构，见 4.5 |
| CaptureImage | object | 必填抓拍图，见 4.6 |
| personInfo | object/空节点 | 文档必填；陌生人时为空，实际空值形态需取样 |
| recognizeImage | object | 可选，匹配库照片；注意小写 r |
| BackgroundImage | object | 可选背景图 |

personInfo 内的全部字段：

| 字段 | 类型 | 文档要求 | 含义 |
| --- | --- | --- | --- |
| name | string | 是 | 姓名，文档写最大 32 字节 |
| sex | string | 否 | male / female / unknown |
| cardType | string | 否 | IC / passport / custom 等 |
| cardId | string | 否 | 证件号；文档称实际仅支持 19 字节 |
| country | string | 否 | 国家 |
| address | string | 否 | 地址 |
| birthday | string | 否 | 生日时间，默认 +08 时区 |
| match | string | 是 | 0～100 的匹配分数，浮点数字符串 |
| serial | integer | 是 | 候选匹配序号 |
| groupId | string | 是 | 文档描述为组织标识 |
| groupName | string | 是 | 组织名称 |
| personId | string | 是，身份字段有互斥说明 | 单设备人员身份 |
| uniqueId | string | 是，身份字段有互斥说明 | 平台管理的人员身份 |
| personType | string | 是 | 文档说明实际表示脸库标识 |

ACK 文档为 `operator=FaceReco-Ack`，info 包含 `personId`、`uniqueId`、`pictureMd5`；result.errorNo=0、description=ok。请求 personId 为 string，ACK 表却写 integer，并存在新增人员措辞，必须实机确认，尤其是陌生人 ACK。不能照搬 FaceSnapshot-Ack，也不能将 ACK 中人员编号当成自动创建人员授权。来源：[识别文档](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/FaceRecognitionEvents.html)。

### 4.5 faceFeature 公共属性

下列是厂商 HTTP 示例的全部属性，不能等同于完整可比较的人脸向量；当前后台未处理。字段类型/枚举在文档和示例间有拼写或 unknown 类型差异，应保留实际报文并验证。

| 字段 | 类型/常见值 | 含义 |
| --- | --- | --- |
| age | integer，文档也允许 unknown | 年龄 |
| sex | string，male/female/unknown | 性别 |
| hat | string，true/false/unknown | 帽子 |
| hair | string，normal/long/short/unknown | 头发 |
| hairColor | string | 发色 |
| eye | string，normal/close/mask/unknown | 眼睛状态 |
| glasses | string，true/false/unknown | 眼镜 |
| beard | string，true/false/unknown | 胡须 |
| mask | string | 口罩，文档存在 fase 拼写 |
| skin | string | 肤色，文档存在 yello 拼写 |
| smile | string | 微笑属性，示例和枚举不完全一致 |
| human | string | 活体属性，示例可出现 live，字段表为 true/false/unknown |
| quality | string | 质量 0～100 |
| yaw | string | 姿态角度 |
| roll | string | 姿态角度 |
| pitch | string | 姿态角度 |

来源：[抓拍字段](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/FaceSnapshotEvent.html)、[识别字段](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/FaceRecognitionEvents.html)。

### 4.6 公共图片对象

CaptureImage、BackgroundImage、recognizeImage、imageInfo 根据事件使用以下字段；匹配库图的文档只列长度、图片和 MD5，不要求尺寸/坐标。

| 字段 | 类型 | 含义/当前校验 |
| --- | --- | --- |
| leftTop | string | 左上角坐标，主要出现在抓拍图 |
| rightBottom | string | 右下角坐标，主要出现在抓拍图 |
| width | integer | 像素宽度 |
| height | integer | 像素高度 |
| pictureLength | integer | 原始图片字节数 |
| picture | string | 图片 Base64，当前支持纯 Base64 和 JPEG/PNG Data URL |
| base64PicLength | integer | 编码后字节数 |
| pictureMd5 | string | 原始图片 MD5；当前存在且非空时校验 |

当前抓拍解码后单图不超过 768KB，Base64 字符串限制约 1MB；厂商推荐原图不宜超过 512KB。当前代码不依据 width/height/pictureLength 校验文件，而是解析实际图片。厂商对各事件 MD5、坐标和背景图必填标记不同，不能混成同一份严格必填规则。

### 4.7 PassengerFlowStatics（可选，待实现）

公共字段之外，info 中以下全部字段为文档必填：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| staticInterval | number | 统计间隔，小时 |
| staticBegainTime | string | 开始时间，原拼写 Begain，例 202611040900 |
| staticEndTime | string | 结束时间，同上紧凑格式 |
| outNumber | integer | 区间出去人数 |
| inNumber | integer | 区间进入人数 |

ACK 为 `PassengerFlowStatics-Ack`，info.eventId 回显，result.errorNo=0、description=ok。这不是日去重接口。来源：[客流统计](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/PassengerFlowStatisticsEvents.html)。

### 4.8 PeopleNumberStatistics（可选，待实现）

| info 字段 | 类型 | 含义 |
| --- | --- | --- |
| ValidAreaCount | integer | 有效区域数，文档最多 5 个 |
| AreaInfoList | array | 区域列表 |
| AreaInfoList[].AreaNum | integer | 区域编号，从 1 开始 |
| AreaInfoList[].PersonNumber | integer | 区域人数 |
| StartTime | string | 统计开始时间，例 20261104T090000+08 |
| EndTime | string | 统计结束时间 |
| hasImage | 文档示例为 string | true/false，是否有图片 |
| imageInfo | object | 图片对象，见 4.6；无图分支需核对实际形态 |

ACK 为 `PeopleNumberStatistics-Ack`，info.eventId 回显，result.errorNo=0、description=ok。注意字段首字母大小写，这些字段不表示全天累计人数。来源：[区域人数](http://docs.p6sai.com/docs/cn/device/Event/http/httphttps/Event/EventNotify/PeopleNumberStatisticsEvent.html)。

## 5. 当前项目全部 15 个业务接口

### 5.1 接口总表

表中路径参数必填。Query 表示 URL 查询参数；JSON 表示请求体；multipart 文件字段名均为 file。

| 方法 | 路径 | 请求参数 | 返回核心内容 |
| --- | --- | --- | --- |
| GET | /api/admin/staff | Query: staffNo、name、department、enabled、page、size | data.total、data.records |
| GET | /api/admin/staff/{id} | Path: id | data.staff、data.photos |
| POST | /api/admin/staff | JSON: name、sex、imageId、position、department、enabled；version 新增不需要 | 新建 Staff |
| PUT | /api/admin/staff/{id} | Path: id；JSON: name、sex、imageId、position、department、enabled、version | 更新后的 Staff |
| DELETE | /api/admin/staff/{id} | Path: id；无请求体 | data=null |
| POST | /api/admin/staff/face-images | multipart: file | data.imageId |
| GET | /api/admin/staff/import-template | 无 | XLSX 文件 |
| POST | /api/admin/staff/import | multipart: file | ImportTask，data.id 用作 taskId |
| GET | /api/admin/staff/import-tasks/{taskId} | Path: taskId | 导入状态、阶段、进度、errors |
| POST | /api/admin/staff/export | 可省略 JSON: staffNo、name、department、enabled | XLSX 文件，无分页参数 |
| POST | /api/device/p6s/events | JSON 设备原始报文，见第 4 节 | 厂商 ACK |
| GET | /api/admin/captures | Query: date、deviceSn、status、eventType、matchStatus、staffId、page、size | data 数组，无总数 |
| GET | /api/screen/config | 无 | hallName、welcomeMessage |
| GET | /api/screen/overview | 无 | 设备状态、日去重及10秒最新人员卡，见5.5 |
| GET | /api/media/{id} | Path: 媒体 id | 图片二进制 |

### 5.2 工作人员列表与导出筛选参数

| 参数 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| staffNo | string | 否 | 模糊查询，保留前导零 |
| name | string | 否 | 姓名模糊查询 |
| department | string | 否 | 部门精确匹配 |
| enabled | boolean | 否 | 不传/导出 null 时不过滤 |
| page | integer | 否，仅列表 | 默认 1，1～1000000 |
| size | integer | 否，仅列表 | 默认 20，1～100 |

### 5.3 新增/修改工作人员 JSON

| 参数 | 类型 | 新增 | 修改 | 约束 |
| --- | --- | --- | --- | --- |
| name | string | 必填 | 必填 | 非空，最多 32 字符 |
| sex | string | 必填 | 必填 | male / female |
| imageId | string | 必填 | 可省略保留头像 | 来自独立上传，最多 36 字符，不能占用他人头像 |
| position | string | 当前实际应传非 null | 当前实际应传非 null | 最多 100；虽无 @NotBlank，当前服务直接 trim，省略会异常；空串与非空校验未统一 |
| department | string | 可选 | 可选 | 默认空串，最多 100；省略/空串会清空，而非保留 |
| enabled | boolean | 可选，默认 true | 可选，默认 true | 显式 null 不允许；修改时应传预期状态 |
| version | integer | 不需要 | 必填 | 使用详情 data.staff.version，防止覆盖他人修改 |

不传 staffNo，由后台新增分配；修改不是 PATCH，需要提交本次完整资料。示例：

```json
{
  "name": "张三",
  "sex": "male",
  "imageId": "独立上传返回的imageId",
  "position": "讲解员",
  "department": "展厅",
  "enabled": true
}
```

修改时 URL 加后台人员 id、JSON 增加最新 version；保留头像可省略 imageId。

### 5.4 文件上传、Excel 与抓拍查询参数

| 接口/字段 | 约束 |
| --- | --- |
| face-images.file | 必填，JPEG/PNG，单张最多 5MB；只上传不自动关联人员；后台统一压缩工作人员照片 |
| import.file | 必填，XLSX，默认最多 50MB、500 人 |
| Excel 固定列 | 工号、姓名、职位、部门、状态、性别、头像；新增工号留空；更新填已有工号；状态为启用/停用；性别 male/female；头像为嵌入图片 |
| import-tasks.taskId | 必填，取导入返回 data.id；不是另一个 data.taskId 字段 |
| export | 默认最多 500 人，按业务配置可调整，不接受 page/size 作为分页 |
| captures.date | 可选，yyyy-MM-dd，事件归属北京时间日期 |
| captures.deviceSn | 可选，设备序列号精确匹配 |
| captures.status | 可选，业务约定 RECEIVED / EXPIRED；当前控制器未强制枚举校验 |
| captures.page / size | 可选，默认 1/20，范围同工作人员列表 |
| media.id | 必填，本地媒体 ID；IMPORT 类型文件不会通过图片接口下载 |

### 5.5 大屏与公共返回

`GET /api/screen/config` 无请求参数，返回 `hallName:string`、`welcomeMessage:string`。

GET /api/screen/overview已实现工作人员日去重和10秒人员卡；默认关闭。启用后STAFF_DAILY_DISTINCT，按展厅、北京时间事件日、不同staffId查询；guestCount/totalCount仍null。详见[接入契约与前端示例](staff-recognition-integration.md)。

普通后台 JSON 包装为 `{"code":200,"message":"success","data":...}`。设备回调 ACK、图片和 Excel 下载不使用该包装。Staff 返回字段为 id、staffNo、name、sex、position、department、avatarId、enabled、deleted、version、createdAt、updatedAt。导入任务还返回 status、phase、totalRows、processedRows、createdCount、updatedCount、errors 等；errors 为数组，每项包含 row 和 message。

## 6. 可选诊断接口及参数

这些不是当前 5 个主动管理接口的一部分，按设备能力决定是否使用。

| 接口 | 参数与返回 | 注意 |
| --- | --- | --- |
| `PUT /FaceGroup/QueryPersonInfoListCount` | PersonInfoFilter：GroupID、Name、Sex、CertificateType、CertificateID、Country、Address、StartBirthday、StopBirthday、ModelState，组织字段文档写 OrginationID；返回 PersonCount | 与列表查询字段存在空值/拼写差异，尤其 ModelState 要求非空，不能直接复用列表请求 |
| `PUT /FaceRecognition/QueryRecordList` | FaceRecognitionFilter：GroupID、StartTime、StopTime、Pagesize、Pagenum、IsHasPath、RecognitionResultType、PeopleName、RuleID | 具体硬件是否存储并可查询需确认 |
| `GET/PUT /System/FaceRecognitionIntervalCfg` | GET 无体；PUT 根节点 FaceRecognitionIntervalCfg，FaceRecognitionInterval:integer，2～15 秒 | 面板机相关能力，不是全天去重 |
| `GET/PUT /System/EventPushMode` | GET 无体；PUT 根节点 EventPushMode，MQTTPushMode:boolean | 人脸事件合并推送配置，不能当成 HTTP 日去重开关 |

历史查询中：GroupID=-1 表示所有库；Pagenum 从 1 开始；IsHasPath=0/1；RecognitionResultType 为 all、Comparison pass、Comparison fail、Failure of biopsy；RuleID=-1 表示所有规则，其他取值按设备说明。StartTime/StopTime 文档存在带偏移和不带偏移格式差异。返回节点 RecognitionRecordList/RecognitionRecord，主要字段为 LogID、GroupID、PersonID、GroupName、Time、Pass、SnapshotPath、Similar、RuleID、PeopleName、RecoValid、UniqueID，其他可选字段随面板机功能变化。

来源：[数量查询](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227017)、[历史查询](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227020)、[识别间隔](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149226679)、[推送模式](http://p6scgi-api.p6sai.com:4999/web/#/663863654/149230105)。

## 7. 部署配置参数（不是 HTTP 请求参数）

位于 `exhibition.devices[]` 的项目配置：serial-number、base-url、username、password、face-group-id、owner-identifier、connect-timeout-ms（默认 3000）、read-timeout-ms（默认 15000）。主动同步要求且仅允许一个 base-url 非空的目标。

其他相关配置：`exhibition.hall-id` 默认 first-floor，`exhibition.heartbeat-seconds` 默认 30，`exhibition.retention-days` 默认 30，导入/导出条数由 max-import-rows/max-export-rows 控制。设备服务器的 URL 路径需与 Java 回调入口一致。

## 8. 日去重内部数据与尚不存在的接口

工作人员日去重由后台保存的hallId、businessDate、staffId和有效FaceReco事件计算，不需要新增厂商统计URL，不建每日计数表。身份映射、重试、接口字段及上线核实见staff-recognition-integration.md。

现有厂商文档没有明确提供“陌生访客当天稳定身份”或“今日去重访客总数”的专用 HTTP 接口；本文不为此编造路径、请求参数或已实现状态。若厂商提供特征或匿名 ID，应补充该实际契约后再设计访客日去重。

本文只新增接口文档，没有调用设备写接口、修改业务代码或运行写数据库的测试。
