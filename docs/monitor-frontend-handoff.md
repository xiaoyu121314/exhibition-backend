# 大屏参观人数与工作人员展示交接

<!-- @author MoShangHuaKai -->

业务口径：展厅参观人数包含工作人员，同一个人同一天重复进入只计1人；工作人员信息在新识别发生后显示10秒。前端源码不在当前工程，本次只实现后端和提供接入模块。

2026-11-06已确认生产启用并重启。后台及大屏同源接口实测返回totalCount=3、guestCount=2、staffCount=1、personCountStatus=AVAILABLE，待处理及失败均为0；设备心跳当前离线，没有新的10秒人员卡片。页面应显示的参观人数为totalCount，而非guestCount。

## 已定位的页面问题

实际页面为 `http://192.168.0.66:8888/#/monitor`。读取其开发构建 `static/js/src_views_Monitor_vue.js` 及内联源码映射后确认：

- `Monitor.vue` 的“展厅参观人数”绑定 `overview.guestCount`，且 `getOverview()` 使用 `res.guestCount || 0`，会把不可用值显示为0。该业务字段应使用 `totalCount`，保留null的“暂无统计”含义。
- 下方 `currentUser` 来自写死的示例 `queue`，每3秒消费一条；页面没有读取 `latestStaff`。需移除示例队列和队列定时器，接入真实识别快照。

## 后端字段

`GET /api/screen/overview` 仍返回 `{code,message,data}`。建议串行每2秒请求一次。

| data字段 | 前端用途 |
| --- | --- |
| totalCount | 展厅参观人数，包含工作人员，按北京时间当日人脸去重 |
| guestCount | 已完成去重、尚未确认为工作人员的人数，不包含工作人员 |
| staffCount | 当天设备已确认的不同工作人员数量 |
| countMode | 本机特征服务启用后为PEOPLE_DAILY_DISTINCT，仅设备工作人员模式为STAFF_DAILY_DISTINCT |
| personCountStatus | AVAILABLE为当前任务全部完成；PROCESSING为尚有任务；PARTIAL为有失败样本、可能漏计；UNAVAILABLE为特征服务未启用；DISABLED为识别关闭 |
| personCountPending/personCountFailed | 尚待处理及达到重试上限的任务数，用于运维诊断，不是参观人数 |
| latestStaff | 当前10秒内最新有效工作人员快照；无新识别或已过期时为null |
| generatedAt | 服务端响应时间，用来计算卡片剩余寿命，避免客户端时钟差 |

`totalCount/guestCount` 仅在识别关闭或特征服务未启用时为null。启用后始终返回已完成的当天去重结果：新任务排队为PROCESSING，失败样本为PARTIAL，均不清空已有数字；失败样本不新增人数。因此PARTIAL可能漏计，不能理解为当天统计已经完整。前端继续展示数字，并可提示“更新中”或“部分抓拍未识别”，不要因为状态不是AVAILABLE就把人数清空。工作人员统计与设备识别继续独立提供。

`latestStaff.staff` 包含 `id,staffNo,name,position,department,avatarId`。照片通过 `/api/media/{id}` 获取：现场抓拍用 `latestStaff.imageId`，参考图优先 `recognizeImageId`，缺省使用 `staff.avatarId`。不要将数据库相对文件路径直接拼接为公网地址。

## 复用现成轮询模块

将 [monitor-client.mjs](examples/monitor-client.mjs) 复制为前端 `src/utils/monitor-client.mjs`。该模块已经处理请求不重叠、5秒超时、重复事件不延长展示时间、旧事件不覆盖新事件及断网时卡片独立清空。

```js
import { startMonitor } from '@/utils/monitor-client.mjs'

// mounted中调用；删除旧getOverview轮询、示例queue及queueTimer，避免两套状态互相覆盖。
this.stopMonitor = startMonitor({
  onConfig: data => {
    this.hallName = data.hallName
    this.welcomeMessages = [data.welcomeMessage]
  },
  onOverview: data => {
    this.overview = { totalCount: data.totalCount, staffCount: data.staffCount }
  },
  onStaff: card => {
    const staff = card && card.staff
    this.currentUser = staff ? {
      id: staff.id,
      name: staff.name,
      pos: staff.position,
      dept: staff.department,
      avatar: card.referenceUrl || card.captureUrl
    } : null
  }
})

// beforeDestroy中调用，data中提前声明stopMonitor: null。
if (this.stopMonitor) this.stopMonitor()

// statData参观人数项：
{ label: '展厅参观人数', value: this.formatCount(this.overview.totalCount) }

// formatCount保留未知值：
formatCount(value) { return value == null ? '暂无统计' : String(value) }
```

10秒寿命以 `displayUntil - generatedAt` 扣除请求耗时计算，不以每次轮询再计10秒。模块负责这些计时；`onStaff(null)` 时清空 `currentUser`。没有新识别时显示“无”符合当前确认的展示要求。

## 后端启用与故障处理

1. 准备Python环境和官方模型，运行 `scripts/setup-face-service.ps1`，启动 `scripts/start-face-service.ps1`，确认 `http://127.0.0.1:18090/health` 返回ready和预期模型。
2. 已有库执行 `sql/face-feature-upgrade.sql`，仅新增当日身份、特征任务两张表；新库只执行新版 `schema.sql`。
3. 核对新功能启用授权后设置 `exhibition.face-feature.enabled=true`，保留已经启用的 `recognition.enabled=true`，重启新版Java后台。当前现场已完成此步骤；迁移到新环境时同样须先准备新表与模型，不能直接使用已开启的现场配置。
4. Java先返回摄像头ACK，再异步处理已保存的有效FaceReco；启用前已有、发生在设备识别统计起点之后的事件也会分批补处理。模型版本和日期分别隔离，匿名身份不跨天跟踪。
5. 新识别在模型尚未完成时为PROCESSING，完成后AVAILABLE；同一人多次抓拍只关联同一个当天身份。匿名身份后来被设备确认为工作人员时会归并，不重复增加总数。
6. 抓拍详情 `GET /api/admin/captures/{id}` 的 `faceFeature` 提供固定错误码和重试状态，不提供特征向量。修复服务后调用 `POST /api/admin/recognition/face-features/retry` 重置失败任务；已成功的任务和ACK不受影响。

新表与抓拍共用现有保留期限，先清理特征任务和到期当日身份，再清理抓拍。当前实现沿用项目的单实例部署约定。

后端功能启用并验证后，前端仍须完成上述字段绑定；后台无法让一个未读取 `latestStaff` 的页面自动显示真实工作人员。

## 站到镜头下很久才显示：现场时效调整

2026-11-06 13:55（北京时间）已读取实际摄像头配置并调整。此时实际页面开发构建已接入 `latestStaff`，轮询间隔确为1000毫秒，人员姓名在概览返回后立即绑定，头像另行加载。当前本机概览接口连续5次耗时为7～11毫秒，经前端开发服务代理的5次请求为24～55毫秒。最近一条工作人员事件接收至分类完成约92毫秒；这些测量不包含人脸进入画面之前的等待，也不能作为完整入镜时延。

摄像头调整如下，写入后已通过设备接口回读确认：

| 设备字段 | 调整前 | 调整后 | 用途 |
| --- | --- | --- | --- |
| SnapshotMode | quality | fast | 改为快速抓拍，减少最佳图片筛选带来的等待 |
| MaxFilterDuration | 10000 | 1000 | 最大筛选时长由10秒改为1秒，设备字段单位为毫秒 |
| IsRepeatPush | false | true | 持续站在镜头内也允许再次上报，首次低分后可获取新样本 |
| RepeatPushInterval | 120 | 3 | 重复推送间隔为3秒；关闭重复推送时原120秒值不生效 |

设备自身的抓拍配置页面及配套脚本确认了上述字段、快速模式和时间单位。完整配置回读比对确认仅这四项变化；人脸质量、背景图、检测范围和布防时间表均保留原值。后端工作人员阈值仍为80分，低分事件仍进入REVIEW，不因加快展示而放宽身份判断。重复推送会增加抓拍事件及特征任务量，人员数量仍按现有当日身份去重。

可复用 `scripts/set-camera-latency.ps1` 在其他现场进行同样调整；凭据交互输入，不写入脚本或备份。`-Preview`只读取设备并输出变化，执行写入前会保存完整XML；HTTP成功后还会检查厂商业务码及实际回读值。

```powershell
$credential = Get-Credential -UserName 'admin' -Message '请输入摄像头管理凭据'
# 先读取并预览四项变更。
.\scripts\set-camera-latency.ps1 -DeviceUrl 'http://192.168.0.232' -Credential $credential -Preview
# 应用快速抓拍、1秒最大筛选、3秒重复推送，并输出原配置备份路径。
.\scripts\set-camera-latency.ps1 -DeviceUrl 'http://192.168.0.232' -Credential $credential
# 如需恢复，指定上一步实际输出的原配置备份文件；恢复前也会备份设备当前状态。
.\scripts\set-camera-latency.ps1 -DeviceUrl 'http://192.168.0.232' -Credential $credential -RestoreFrom '原配置备份文件的完整路径.xml'
```

本次原配置备份：`target/camera-latency/snapshot-20261106-135515-8aeabfffd39d44cea6ba1659342de824.xml`。这属于设备配置调整，后台及前端无需为本次变更重新启动。最大筛选1秒不等于入镜到显示总耗时1秒：设备检测、比对、回调传输和0～1秒轮询等待仍会叠加，真实端到端效果须现场重新进入镜头范围验收。

2026-11-06 13:59补充现场复测：用户反馈重新进入镜头后，人员卡片约3～5秒出现。最新5条工作人员回调的接收至分类完成耗时为85～97毫秒，均已确认为STAFF；设备事件时间至后台接收时间的差值为1.519～5.709秒。后者使用设备及服务器两端时钟，包含设备处理和上报等待，不能直接当作纯网络耗时。本次记录的真实入镜到显示效果以用户观察的3～5秒为准，保留当前已验证配置。
