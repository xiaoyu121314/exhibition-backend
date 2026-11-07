# 展厅人员与摄像头管理后台

<!-- @author MoShangHuaKai -->

维护者：MoShangHuaKai。目标环境为 **Java 8、MySQL 5.7.44**。仅后端，提供人员维护、含头像的 Excel 导入导出、摄像头抓拍与设备比对结果接入、工作人员日去重和大屏接口。

## 1. 项目结构与依赖

- Java：Spring Boot 2.7.18、MyBatis Starter 2.3.2、Connector/J 8.0.33、Apache POI 5.5.1。
- `src/main/java`：common/config/staff/device/screen/persistence/domain。
- `sql/schema.sql`：MySQL 5.7 建表脚本，InnoDB、utf8mb4_unicode_ci，不含 MySQL 8 专有语法。
- `src/main/resources/application.yml`：公共配置，通过 profiles 加载数据源、业务及 Swagger 三份配置。
- `src/main/resources/application-datasource.yml`：MySQL连接配置。
- `src/main/resources/application-exhibition.yml`：展厅业务、图片存储和摄像头配置。
- `src/main/resources/application-swagger.yml`：Swagger 文档地址、扫描范围及页面配置，构建后位于 JAR 同级 `config/`。
- `config/exhibition-display.yml`：外部展示文案，每5秒热更新。
- `scripts/`：Windows 构建及启动入口。

## 2. 初始化数据库

在自己的 MySQL **5.7.44** 中新建独立数据库，使用具备该库建表权限的账号执行：

```sql
CREATE DATABASE exhibition CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE exhibition;
SOURCE E:/idea_workSpace/TY/Exhibition stand/exhibition-backend/sql/schema.sql;
```

路径含空格时，可在 Navicat/IDEA 中打开 `sql/schema.sql`，选择新建的 exhibition 数据库执行。应用不会自动修改生产库表结构。启用严格 SQL 模式，连接与会话时区使用北京时间。

## 3. 配置与启动

Windows 跨机部署清单：[本地开发 → 另一台 Windows 部署](docs/windows-deployment-checklist.md)，覆盖当前视频、工作人员识别与本机人脸去重，包含交付包整理、全部现场配置、联网/离线安装、自启和验收步骤。

综合部署手册：[JAR 打包与 Windows / Linux 部署](docs/deployment-windows-linux.md)。其中部分早期说明尚未覆盖当前人脸服务，Windows 部署优先参考上述专用清单。

先在开发项目根目录使用Java8构建：

```powershell
.\scripts\build.ps1 -Jdk8Home 'D:\Programming\Java\JDK-8\jdk1.8.0_131'
```

打包自动在 `target/` 中准备 JAR 和同级外部文件。部署时只需将下面这些内容复制到同一个运行目录，**不需要复制src、测试文件、Maven缓存或整个target目录**：

```text
运行目录/
├── exhibition-backend-1.0.0.jar
├── config/
│   ├── application.yml
│   ├── application-datasource.yml
│   ├── application-exhibition.yml
│   └── exhibition-display.yml
├── scripts/                  # start.ps1、runtime.ps1
├── media-server/             # 独立媒体程序及许可证
└── data/                     # 运行后自动创建
```

`src/main/resources/` 中的文件仅作为打包模板。部署后修改**运行目录config中的文件**。参考 tengyuan-admin，通过 `spring.profiles.active: datasource,exhibition` 加载分拆配置，业务参数填写 YAML；媒体密钥支持通过启动脚本或环境变量注入。

| 文件 | 需填写或调整的内容 |
|---|---|
| `config/application.yml` | 服务端口、上传限制、任务调度和MyBatis公共设置 |
| `config/application-datasource.yml` | `spring.datasource.url/username/password` |
| `config/application-exhibition.yml` | 摄像头序列号及设备管理凭据、图片目录和保留期限 |
| `config/exhibition-display.yml` | 展厅名称和欢迎语，自动热更新 |

所有后台业务接口直接访问，无需账号密码、Authorization 访问令牌或摄像头 OAuth 签名；不创建用户账号、默认密码或登录会话。实时视频的播放地址、列表和分片也无需访问密钥。主动调用实体摄像头仍使用设备要求的 RTSP 凭据或设备管理用户名、密码和拥有者安全码。

**展示文件、图片目录的相对路径均以JAR所在目录为基准，不受执行命令时的工作目录影响；绝对路径保持原值。**例如 `./config/exhibition-display.yml` 指向JAR同级config内的文件，`./data` 指向JAR同级data目录。

启动 Java 后台。可以从任意目录通过JAR绝对路径运行：

```powershell
java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar 'D:\exhibition\exhibition-backend-1.0.0.jar'
```

也可使用运行目录内的 `scripts/start.ps1 -Jdk8Home 'Java8目录'`。目录内有多个JAR时通过 `-JarFile` 显式指定。开发项目中的同名脚本会定位已构建的target/JAR及同级外部文件，部署脚本则直接定位上一级目录的JAR。

`build.ps1` 显式使用指定的Java8启动Maven，在项目 `target/maven-repository` 使用独立缓存，不读取或改写JAVA_HOME。实时视频、管理、大屏、图片和摄像头回调均无需接口访问令牌，HTTP Basic和表单登录已关闭。前后端可通过 HTTPS 同源反向代理部署。

默认端口：Java 8080。当前图片目录配置为 `E:/tengyuan/uploadPath`，工作人员图片保存到其 `staff/日期/图片ID.jpg`；可通过 `exhibition.storage-directory` 修改，重启生效。

### 工作人员同步设备

在运行目录 `config/application-exhibition.yml` 的现有 `exhibition.devices` 节点中配置：

```yaml
exhibition:
  devices:
    - serial-number: "设备实际序列号"
      base-url: http://192.168.0.232
      username: "设备管理用户名"
      password: "设备管理密码"
      face-group-id: "已有脸库的GroupID2"
      owner-identifier: "已设置到设备的Ownner原值"
      connect-timeout-ms: 3000
      read-timeout-ms: 15000
      # 原有抓拍回调 token、secret 继续保留，和上述管理账号密码相互独立。
```

目前仅支持一台主动人员同步设备；仅为一项设备配置 `base-url`。用户名、密码、脸库 ID、拥有者在配置模板中留空，补齐后才能同步。应用不会自动修改设备拥有者或创建脸库。安全码使用拥有者原值与同一请求的北京时间计算，不能用回调 Token、设备密码或设备序列号替代拥有者原值。

维护顺序为 **完整校验 → 设备同步 → 本地数据库事务提交**。设备离线、鉴权失败、XML 状态码非0或响应异常时，本地工作人员和照片绑定均不变。停用和删除将人员移出配置的设备脸库；重新启用重新添加。仅更新设备支持的姓名、性别、照片，工号、职位、部门仍由后台维护。新增停用人员只保存本地，不向设备添加。所有单条维护、批量导入及图片清理共用单实例维护锁。

设备成功、本地提交失败时按相反顺序补偿设备；新增撤销、修改恢复资料和头像、删除恢复原 FaceUUID。若设备不支持恢复原身份或发生补偿失败，返回“本地未提交，设备状态需核对”及操作 ID，不能当作设备已恢复。超时后先查询，不自动重发新增。此流程不提供跨系统事务；进程退出、断电和外部设备管理操作可能需要人工核对，不设后台同步重试。

新增失败时先查看同一时段的“调用人员同步设备 / 人员同步设备通信失败”日志，核对具体接口、异常类型和耗时；连接失败、地址无法解析、连接中断及请求超时分别返回明确提示。`/FaceGroups/QueryAll` 和 `/FaceGroup/QueryPersonInfoList` 是查询，`/FaceGroup/UpdatePersonInfoAndFaceImage` 是照片新增或更新。补偿日志只描述补偿结果，原始失败原因见“人员维护失败”及此前的设备调用日志。日志不输出设备地址、账号密码、拥有者、安全码或照片。IDEA 运行需重新编译并重启当前应用，JAR 部署需替换并重启后才能使用新诊断。

照片 multipart 按浏览器 FormData 格式发送 UTF-8 文本和 JPEG，文本部分不附加 Content-Type，每个部分不附加 Content-Length，外层仍提供完整表单的总 Content-Length。该调整用于减少固件表单解析差异；本机模拟设备通过不能代替现场新增验收。遇到连接中断仍查询并补偿，不能自动切换新增接口或重发照片，否则可能重复创建人员。

外层Content-Type必须发送为`multipart/form-data; boundary=...`，保留分号后的空格。现场固件对完全相同的请求体，使用Spring默认的`;boundary=`会返回HTTP 415，而`; boundary=`可正常进入业务处理。客户端在表单序列化后补齐该空格；只调整请求头，不重写boundary、不更改照片字节、不重发新增请求。

查询列表可能同时包含设备原生人员（有数字PersonID、FaceUUID为空）和有FaceUUID的人员。原生记录须属于当前数字GroupID且PersonID有效、未重复，保留在设备上但不纳入UUID同步快照；后台新增只关联设备新生成且创建前不存在的真实FaceUUID。分页继续按原始响应条数读取，数字PersonID不替代FaceUUID。缺少可核对身份、跨页重复原生ID或重复FaceUUID仍中止维护，不修改原生资料。

设备新增使用应用生成的FaceUUID发送，再通过创建前后目标脸库差异和人员回读确认真实身份；不会仅按同名建立关联。不同固件对FaceUUID的要求存在差异，人员同步及回调身份关系仍须真机验收。

厂商脸库指导文档要求YUV 4:2:0照片不超过300×300。工作人员独立上传和Excel导入共用等比缩放：最长边最多300像素，小图不放大，不裁剪；RGB JPEG编码的4:2:0采样由Java 8集成测试读取实际JPEG元数据验证。本地存储和设备同步使用同一份缩放后的字节。抓拍及背景图保留原分辨率。此前已上传的大图不会自动转换原imageId，需重新上传获取新imageId；发送前发现超规格图片会明确提示重新上传，不再直接向设备发送。

手机JPEG可能依靠EXIF方向标记显示正向。转码读取EXIF的8种方向（含镜像），将方向直接落实到输出像素，再移除原图附加信息，避免浏览器预览正常而设备实际收到横置照片。单张上传和Excel导入共用该处理；之前已经转码丢失EXIF的imageId无法自动恢复原方向，修正后需重新上传原图。设备状态码4098表示未检测到人脸，不能只凭原图文件大小判断原因。

初始化数据库使用新版 `sql/schema.sql`，增加工作人员 `sex` 和 `exh_staff_device` 身份关联表；本次识别调整提供显式的一次性结构脚本sql/recognition-upgrade.sql，不自动修改运行中的数据库；新库只使用schema.sql。

直接 `java -jar` 同样自动优先读取JAR同级config，无需指定源码位置。普通配置修改后重启生效，展厅名称和欢迎语自动热更新。另置配置目录时，可通过Java脚本的 `-ConfigDirectory` 或命令行 `--spring.config.additional-location=file:/绝对目录/` 指定。

大屏可通过普通 `<img src>` 直接展示头像，无需携带 Authorization 或先请求 Blob；人员照片、抓拍及未绑定图片也可按媒体 ID 读取，Excel 导入源文件仍不支持从图片入口下载。前后端可通过同源反向代理部署。

## 4. 接口清单

启动 Java 应用后，默认 Swagger 地址为 **[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)**，会跳转到 `/swagger-ui/index.html`。远程访问时将 localhost 替换为 JAR 所在服务器 IP；若修改 server.port 或配置 context-path，访问地址也需对应调整。

OpenAPI JSON：[http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)；YAML：[http://localhost:8080/v3/api-docs.yaml](http://localhost:8080/v3/api-docs.yaml)。当前 25 个业务操作均包含中文说明、参数及响应说明，按工作人员、导入导出、抓拍、大屏、图片、摄像头回调分组。

文档页面及业务接口无需账号密码或访问令牌，可直接点击 Try it out 调试；实时视频接口也无需填写额外请求头。摄像头回调无需填写 OAuth 签名头，报文中的设备序列号仍需与配置一致。应用需填写有效数据库及业务配置后启动。

使用兼容当前 Spring Boot 2 / Java 8 的 springdoc-openapi-ui 1.8.0，依赖及地址约定见[官方文档](https://springdoc.org/v1/)。普通 Swagger 配置修改后重启生效，外部配置不依赖源码目录或环境变量。

业务返回体：`{"code":200,"message":"success","data":...}`。业务校验或并发版本冲突 HTTP400，工号等唯一标识冲突 HTTP409，图片不允许访问 HTTP403。设备成功返回厂商 ACK；文件下载返回二进制。

设备同步失败同样返回 HTTP400、`code=400`、`data=null`，`message` 按[厂商错误码说明](http://xrs-api.p6sai.com:8091/project/11074/interface/api/72673)返回中文原因、厂商状态码及本次设备接口路径。例如：

```json
{"code":400,"message":"设备同步失败：图片中没有找到人脸；状态码 4098；接口=/FaceGroup/UpdatePersonInfoAndFaceImage；本地未提交，设备补偿完成；操作ID=本次操作ID","data":null}
```

其中4099表示图片中人脸数超过一个，4112表示图片像素超过300×300；-2表示编解码失败，4101表示人脸算法未初始化，4208表示安全码不匹配。人员维护和Excel导入共用同一解释；导入失败行的`errors[].message`保留相同原因。未知整数码显示“未知厂商错误”并保留码值，非法或超长码值显示“未知”。设备原始`message`及`requestURL`不直接展示。只读诊断仍逐项返回结果，失败项保留具体错误原因、码值和固定接口路径。

| 方法 | 地址 | 说明 |
|---|---|---|
| GET | `/api/admin/staff` | `staffNo/name/department/enabled/page/size` 查询；每页最多100 |
| GET | `/api/admin/staff/{id}` | 人员、照片和只读deviceBinding建模及核实信息 |
| POST | `/api/admin/staff` | JSON新增，必传sex、imageId；启用时先同步设备再写库 |
| PUT | `/api/admin/staff/{id}` | JSON修改，必传sex、最新version；imageId省略保留头像，传入则替换 |
| DELETE | `/api/admin/staff/{id}` | 先移出设备脸库，再本地逻辑删除，工号保留 |
| POST | `/api/admin/staff/face-images` | 独立上传，multipart字段file，返回imageId，不关联人员或同步设备 |
| GET | `/api/admin/staff/import-template` | 下载含填写说明的 `.xlsx` 模板 |
| POST | `/api/admin/staff/import` | multipart字段file，返回taskId及初始状态 |
| GET | `/api/admin/staff/import-tasks/{taskId}` | 进度、最终新增/更新数及errors数组 |
| POST | `/api/admin/staff/export` | JSON筛选条件与列表相同，忽略分页，导出展示头像 |
| POST | `/api/device/p6s/events` | JSON心跳、抓拍及FaceReco；无需令牌或签名 |
| GET | `/api/admin/captures` | date/deviceSn/status/eventType/matchStatus/staffId/page/size查询 |
| GET | `/api/admin/captures/{id}` | 完整比对详情、候选、图片及ACK诊断 |
| POST | `/api/admin/captures/{id}/resolve` | 未关联事件的本地重新关联 |
| GET | `/api/admin/recognition/device-diagnostics` | 设备能力、配置通道规则的只读诊断 |
| POST | `/api/admin/recognition/baseline` | 只读核实设备，事务建立本地身份基线 |
| GET | `/api/screen/config` | hallName、welcomeMessage |
| GET | `/api/screen/overview` | 目标摄像头状态、工作人员日去重及10秒最新人员卡；现场配置已启用识别和上报 |
| GET | `/api/screen/live` | 创建独立 WebRTC 观看会话，共享摄像头拉流 |
| GET | `/api/media/{id}` | 按媒体 ID 直接读取人员照片、抓拍或未绑定图片 |

查询 `GET /api/admin/staff` 时，`data.records[].avatarUrl` 返回头像媒体表 `exh_media.relative_path` 的原值，例如 `staff/2026-11-04/图片UUID.jpg`。无头像引用或媒体记录时返回 `null`；保存路径中的目录、日期和文件名均以数据库记录为准。

先调用 `POST /api/admin/staff/face-images` 上传 JPEG/PNG，得到 `{"code":200,"message":"success","data":{"imageId":"图片UUID"}}`，再提交人员新增请求：

```json
{"name":"张三","sex":"male","imageId":"上传返回的图片UUID","position":"讲解员","department":"展厅","enabled":true}
```

一人一张头像；新增必须提供有效 `imageId`，姓名最多32字符，性别仅支持 `male`（男）、`female`（女）。修改请求增加 `version`，`imageId` 省略时保留头像、传入时替换。图片必须来自独立上传且未被其他工作人员使用，保存人员时校验图片归属并同步设备。原带人员 ID 的照片上传入口和单张照片删除入口已移除。工号由后台从 `001` 起自动递增（`001`、`002`、…、`999`、`1000`），新增和修改均无需传入 `staffNo`，修改保留原工号，已删除工号不复用。单条与Excel新增共用维护锁及数值最大值分配规则；失败且未提交的编号不占用。当前为单实例服务，工号分配依赖进程内维护锁。工号始终为字符串，未绑定图片保留满24小时后在每日清理中回收。

## 5. Excel 模板、导入和导出

模板包含“工作人员”“填写说明”工作表。固定列为：**工号、姓名、职位、部门、状态、性别、头像**。

- 新增人员工号留空，由后台自动生成；更新人员填写已有工号，必须为文本并保留前导零。姓名、职位、状态、性别必填，姓名最多32字符，性别为 male 或 female。
- 状态填写“启用”或“停用”。部门空值在更新时清空部门。
- 使用 Excel 标准“插入图片”，将图片左上角锚定到该人员行的头像列，并设置随单元格移动和缩放。一行最多一张图。
- 不支持 IMAGE/DISPIMG公式、外部图片链接、分组图片或单元格富数据图片；发现后明确报错，不能静默当成空头像。
- 新增人员必须有头像；已有工号无头像则保留，有头像则替换唯一头像。
- 非空工号只允许更新已有人员；工号不存在、单文件重复工号、已删除工号及图片损坏均报错。多个新增行可同时留空工号，按行顺序连续分配。本地不再校验单人脸或比较头像身份，照片由设备处理。
- 默认单次500人、文件50MB、单张5MB。
- 后台任务先整份人员资料及图片验证，全部通过再逐人同步设备，最后整批事务提交；任一设备操作或提交失败，逆向补偿此前操作，本地整批不更新。任务阶段包括 VALIDATING、SYNCING_DEVICE、COMMITTING、COMPENSATING；RECONCILIATION_REQUIRED 表示补偿失败或在设备操作期间中断，需要人工核对。
- 只有校验阶段中断的任务可在重启后恢复；设备同步/补偿/提交阶段中断的任务标记失败，不自动重发。失败结果行号0表示文件级或提交阶段错误，其他行号与Excel可见行号一致。
- 导出同样列和嵌入图片，可再次导入；超限要求缩小筛选范围，不静默截断。空模板不含示例人员。
- 导入源文件和结果在结束24小时后的每日清理中删除；导出不含人脸特征及抓拍图片。

## 6. 摄像头协议与大屏状态

### 摄像头实时视频

实时预览使用 **摄像头 H.264 MAIN → 独立 ZLMediaKit → 浏览器 WebRTC**。Java 只负责同源信令和观看生命周期，视频直接从媒体服务发给浏览器。不转码、不录制、无音频。设备设置 H.264、25 fps、关键帧间隔 25 帧并保存，实际输出需要以媒体轨道核实。

#### 1. 启动与地址配置

ZLMediaKit 是独立程序，Java 和媒体服务都必须运行。当前本机部署：Java HTTP 8080，媒体管理 API 9999，WebRTC UDP/TCP 8000。浏览器访问后端 8080 和媒体 8000；后端访问媒体 9999；媒体服务访问摄像头 192.168.0.232:554。媒体 HTTP 白名单限制为回环地址，浏览器不直接调用管理接口。

1. 使用带 OpenSSL、libSRTP、WebRTC/WHEP 支持的 MediaServer.exe，部署到 JAR 同级 media-server 目录，附带许可证。当前构建信息见 docs/media-build.md。
2. 在后端目录构建后，执行：

~~~powershell
# RtcAddress 是运行媒体服务这台电脑的局域网 IP，不是摄像头 IP。
.\target\scripts\start-media.ps1 -RtcAddress '192.168.2.100'
~~~

示例 IP 必须按实际网络修改。仅有一张有效物理网卡时可以省略参数，由脚本选择；多网卡时要求显式填写。脚本生成 target/data/media/config.ini 和随机密钥文件，运行中请保留窗口。长期运行应分别将媒体服务和 Java 配置为系统服务。不要同时启动两份占用同一端口的媒体服务。

3. Java 的 exhibition.live.zlm-base-url 保持 http://127.0.0.1:9999。IDEA 可直接运行；未设置 ZLM_SECRET 时自动读取 target/data/media/zlm-secret.txt。通过脚本启动 JAR 则读取 JAR 同级 data/media。修改 Java 配置或代码后需要重启 IDEA 运行的后端。
4. Windows 防火墙允许 MediaServer 在所用局域网接收 UDP 8000 和 TCP 8000；不要向局域网开放管理 API。启动脚本中的 RtcAddress 会写入 rtc.externIP，必须能从大屏浏览器访问。跨公网还需要公网地址、NAT 同端口映射及安全的部署方案；当前配置面向局域网。
5. 用 Chrome 或 Edge 打开 http://localhost:8080/api/screen/live/player；远端大屏把 localhost 换为后端电脑局域网 IP。WebRTC 接收视频不申请摄像头或麦克风权限。若企业策略要求安全上下文，使用可信 HTTPS。

#### 2. 前端直接使用

完整接入文档：[大屏前端 WebRTC 视频接入指南](docs/frontend-webrtc-integration.md)，含 Vue 3 完整示例、接口契约、开发代理、生产部署及验收清单。

仓库未包含原大屏前端工程，后端已提供完整播放页和零外部依赖组件。可用 iframe 嵌入播放页，也可以复用组件：

~~~html
<video id="camera" autoplay muted playsinline controls></video>
<script src="/api/screen/live/player.js"></script>
<script>
  const player = new ExhibitionLivePlayer(document.getElementById('camera'), {
    endpoint: '/api/screen/live',
    onState: (state, message) => console.log(state, message)
  });
  player.start();
  // Vue/React 页面卸载时调用 player.destroy()。
</script>
~~~

组件创建 recvonly 视频连接、优先 H.264、等待完整 ICE 候选地址后提交 SDP；每 15 秒保活，网络失败按 1–10 秒退避重连。停止、页面退出或销毁时关闭 PeerConnection 并通知后端释放本人会话。每次重连获取全新会话；其他观看者不受影响。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | /api/screen/live | 创建观看会话 |
| POST | /api/screen/live/{session}/offer | application/sdp 视频协商 |
| POST | /api/screen/live/{session}/heartbeat | 每 15 秒保活 |
| POST | /api/screen/live/{session}/close | 退出时独立释放 |
| GET | /api/screen/live/player | 内置播放页 |
| GET | /api/screen/live/player.js | 可复用浏览器组件 |

GET /api/screen/live 返回 protocol=webrtc、session、playUrl、heartbeatUrl、closeUrl。playUrl 是 **SDP POST 协商地址**，不能赋给 video.src；组件已经实现全部交互。后端 context-path 会体现在返回地址中。远端媒体会话 token、设备 RTSP 地址和管理密钥均不返回浏览器。

#### 3. 生命周期与故障排查

同一 Java 实例最多 32 个观看会话，共享一路原生 RTSP 拉流。会话 60 秒无保活后释放，每 10 秒巡检；无人观看约 60–70 秒回收共享代理。媒体服务也开启无人观看回收。关闭一个观看者只 DELETE 本人的 WHEP 连接。摄像头离线、轨道无效或 15 秒帧计数不增长时返回脱敏 503；旧会话返回 404，重复协商返回 409。

- 获取入口就返回 503：检查媒体进程、9999、密钥文件、设备 RTSP 连通性和 H.264。
- 页面持续“媒体连接超时”：检查 rtc.externIP 是否为媒体电脑实际 LAN IP、UDP/TCP 8000、防火墙和浏览器到媒体电脑的网络。
- 连接成功但没有画面：用浏览器 WebRTC 统计检查 inbound-rtp 的 bytesReceived、framesDecoded 是否持续增长；核查设备编码 profile、分辨率和关键帧。优先使用正常支持 H.264 的 Chrome/Edge。
- 仍有延迟：WebRTC 消除了分片等待，但设备编码、B 帧、网络丢包和浏览器解码仍影响延迟。用同一画面实物时钟拍照比较，不能只凭“connected”判定延迟合格。

普通 Java 8 Maven verify 使用本地 HTTP 模拟媒体服务，覆盖共享拉流、SDP 协商、独立释放、过期、断流和错误脱敏，不依赖设备。实体测试显式设置 TEST_LIVE_RTSP、TEST_ZLM_URL、TEST_ZLM_SECRET 后运行 LiveStreamCameraTest；可选 TEST_RTC_OFFER_FILE 指向浏览器生成的视频 recvonly offer 文件，追加实际 WHEP 验证。完整画面、多人播放、自动重连和延迟仍须实际浏览器验收。最新结果见 docs/verification.md。

### 心跳与抓拍回调

使用 `heartbeat`、`FaceSnapshot`、`FaceReco`，设备网页设置局域网服务器地址、8080端口及路径 `/api/device/p6s/events`。后台无需 OAuth 令牌或签名；设备若仍发送这些请求头，后台会忽略。必须正确响应心跳并启用 `isSnapshotEnable`，才能收到抓拍。

回调只需提交 JSON，无需 `Authorization`、`OAuth-Token`、`OAuth-Signature` 等请求头。`deviceInfo.serialNumber` 必须与配置设备一致，用于选择业务配置；仍保留报文大小、时间、图片及事件幂等校验。重复心跳可正常应答，重复抓拍不会重复入库。

设备“测试”会发送真实心跳并校验 ACK。成功响应使用 `application/json`，先序列化 UTF-8 字节并设置 `Content-Length`，避免分块传输；`info.time` 按厂商示例返回秒级北京时间，如 `20261103T131155+08`。若仍提示 `502 / http invalid response`，需核对同一时刻后端日志及实际 HTTP 状态；设备界面错误码不能直接当作服务端 HTTP 状态码。还应确认已保存设备配置、Token/密钥与当前运行配置相同，序列号一致、双方时钟同步；测试超时时间可临时调为 5 秒排除网络和数据库首次连接耗时。

摄像头回调不打印入口、结束状态、耗时及控制器接收日志，避免高频心跳和抓拍刷屏。若设备测试失败，先检查当前启动的服务版本、设备保存的目标地址/端口/路径、网络及 Windows 防火墙，再结合实际 HTTP 状态、请求方法及 Content-Type 检查路由匹配。

抓拍图片在 `info.CaptureImage.picture` 的Base64中，背景图可选。验证图片MD5（若提供），单图Base64约1MB内，整份请求4MB内。持久化成功后返回 `FaceSnapshot-Ack`，按文档回显 `info.eventID`。真实固件如果采用不同大小写或时间格式，需要先留取脱敏真实报文核对，不能声称通用文档已证明该淘宝SKU可接入。

抓拍只保留事件和图片，正常记录状态为 `RECEIVED`，超过保留期限的补传为 `EXPIRED`。同一报文重传不重复入库；事件幂等不等于人员去重。

大屏overview设备模式返回STAFF_DAILY_DISTINCT，按展厅、北京时间发生日和不同staffId统计工作人员。本机特征服务启用后返回PEOPLE_DAILY_DISTINCT：totalCount包含工作人员，guestCount不含已确认工作人员；始终返回已完成的当天去重人数，排队或失败不清空已有数字。personCountStatus为PROCESSING表示仍有待处理任务，PARTIAL表示失败样本未计入、可能漏计，AVAILABLE表示当前任务全部完成；只有识别关闭或特征服务未启用时人数为null。代码默认关闭，现场已确认启用，新环境须先准备两张新表和本机模型。最新工作人员仍在事件发生10秒后清空，旧补传不重弹。前端须读取totalCount及latestStaff，详见[大屏前端交接说明](docs/monitor-frontend-handoff.md)和[工作人员设备接入](docs/staff-recognition-integration.md)。

## 7. 展示配置及保存期限

外部展示文案：

```yaml
exhibition:
  name: 一楼展厅
  welcome-message: 欢迎莅临参观指导
```

每5秒重新读取，校验成功原子替换；启动缺失报错，运行中写坏保留最后有效文案。名称最多100字符、欢迎语最多500字符，作为普通文本返回。

每天北京时间03:15清理：抓拍记录保留30天，导入文件及结果在结束后保留24小时。工作人员头像随档案维护；无引用旧照片延迟清理，文件删除失败下轮重试。超过30天的补传标记EXPIRED且不保存图片。

## 8. 验证与证据范围

普通 `mvn test` 使用 H2 MySQL模式和HTTP模拟设备，验证业务、事务和接口约束，**不代表真实设备或识别准确率**。

隔离 MySQL 5.7.44 集成测试必须指向专门测试库；测试会清空该测试库的 `exh_*` 业务表：

```powershell
New-Item -ItemType Directory -Force '.\target' | Out-Null
Copy-Item '.\src\test\resources\application-test-mysql.example.yml' '.\target\application-test-mysql.yml'
# 在 target/application-test-mysql.yml 中填写隔离测试库账号和密码后执行。
.\scripts\build.ps1 -Jdk8Home 'D:\Programming\Java\JDK-8\jdk1.8.0_131' -TestDatasourceConfig '.\target\application-test-mysql.yml'
```

测试配置仅允许本机隔离 `exhibition_test` 库。直接使用Maven时可通过 `-Dtest.datasource.config=配置文件绝对路径` 指定同一文件。实际测试结果记录在 `docs/verification.md`，尚未验证的现场设备行为不应当写为通过。
