# 本地开发 → 另一台 Windows 部署操作清单

<!-- @author MoShangHuaKai -->

核对日期：2026-11-06。本文根据当前 `pom.xml`、配置模板、PowerShell 脚本、SQL 和 Java/Python 实现整理，适用于 **Windows x64、Java 8、MySQL 5.7.44，Java / 媒体 / 人脸特征服务同机的局域网部署**。

开发机负责构建和制作交付包；目标 Windows 负责安装运行环境、填写现场配置并启动服务。目标机不需要 IDEA、Maven 或 Java 源码。完整大屏前端需要从其独立工程另行打包。

## 1. 先明确需要部署什么

| 组件 | 当前作用 | 目标机准备方式 | 是否随 JAR 内置 |
|---|---|---|---|
| Java 后端 | 人员管理、回调接收、图片、统计、播放信令、Swagger | 安装 Java 8，复制可执行 JAR | Java 依赖在 JAR 内 |
| MySQL | 业务数据、设备身份、抓拍与去重状态 | 安装 MySQL 5.7.44 或连接现有实例 | 否 |
| ZLMediaKit | 摄像头 RTSP 转 WebRTC | 复制 `media-server/`，独立启动 | 否 |
| 本机人脸特征服务 | OpenCV 提取向量，Java 完成当日人员去重 | 安装 Python，重建虚拟环境，准备依赖和模型 | 否 |
| 大屏 / 管理前端 | 页面和播放器调用 | 从独立前端工程构建并部署静态文件 | 仅独立播放页和播放器脚本随 JAR 提供 |

**当前需要运行 MySQL，以及媒体、人脸特征、Java 三个独立进程。`start.ps1` 只启动 Java，不会顺带启动另外两个服务。** 当前设计按单 Java 实例、单台工作人员主动同步设备运行，计划任务和手动窗口不要重复启动。

### 1.1 当前源码中的现场值，发布前必须核对

| 位置 | 当前模板状态 | 跨机部署动作 |
|---|---|---|
| 数据源 | `localhost:3307/exhibition`，开发账号 | 改为目标数据库地址、实际端口及应用账号 |
| 图片根目录 | `E:/tengyuan/uploadPath` | 确认目标机存在此盘符，或改为 `./data/uploads` / 现场绝对路径 |
| 摄像头地址 | `192.168.0.232` | 同时核对 HTTP 管理地址和 RTSP URL，不能只改其中一个 |
| 设备身份、脸库和凭据 | 已填写开发现场值 | 按实际设备填写；本手册不复写现有明文密码 |
| `recognition` | YAML 中三个启用/验收开关均为 `true` | 判断是否仍使用同一已验收设备和固件；新设备先按第 7 节核实 |
| `face-feature.enabled` | YAML 中为 `true` | 必须交付 Python 服务、两个模型、依赖和完整表结构 |
| `live.enabled` | YAML 中为 `true` | 必须交付媒体程序并配置服务器实际 IP |
| 媒体程序 | 项目 `media-server/` 中已存在 | Maven 不复制它，制作发布包时单独加入 |
| 模型 | 当前开发机 `face-service/models/` 中已存在 | 随包携带两个 ONNX 文件和对应 LICENSE；源码获取后未必已有模型 |

以**外部 YAML 的实际值**判断部署功能，不要把 Java 配置类中 `enabled=false` 的默认值当成当前发布配置。旧版综合部署手册未完整覆盖本机人脸服务，Windows 跨机部署以本文清单为准。

## 2. 部署前填写现场信息

建议先填写这张表，再开始安装与发布。

| 信息 | 示例 / 约定 | 现场填写 |
|---|---|---|
| Windows 版本及架构 | Windows x64；确认所选 Python、VC++ 运行库支持该系统 | __________ |
| 运行根目录 | `D:/exhibition` | __________ |
| Java 8 安装目录 | `D:/Java/jdk8` | __________ |
| Python 实际可执行文件 | `C:/Python314/python.exe`，不要使用应用商店占位入口 | __________ |
| 服务器固定 LAN IPv4 | 浏览器和摄像头可达的地址 | __________ |
| 数据库 IP / 端口 / 库名 | `127.0.0.1 / 3306 / exhibition`，开发机当前为 3307 | __________ |
| 数据库账号 / 密码 | 独立应用账号；密码通过受控方式交付 | __________ |
| Java HTTP 端口 | `8080` | __________ |
| 摄像头 IP / 管理端口 | 实际 HTTP / HTTPS 地址 | __________ |
| 摄像头 RTSP | `554`、实际码流路径、H.264 | __________ |
| 设备序列号 / 账号 / 密码 | 与实际设备一致 | __________ |
| 工作人员脸库 | 字符串 `GroupID2`，不是数字 `GroupID` | __________ |
| 拥有者原值 | 设备已设置的 `Ownner` 原值 | __________ |
| 展厅标识 / 名称 / 欢迎语 | `hall-id`、展示文件内容 | __________ |
| 图片存储目录 / 保留天数 | 建议 `./data/uploads`，当前保留 30 天 | __________ |
| 前端地址及服务器 | 同机代理或独立前端服务器 | __________ |
| 允许访问的来源 | 摄像头 IP、大屏 IP、管理端 IP / 网段 | __________ |
| 网络条件 | 可联网安装 / 完全离线；是否需要安装代理 | __________ |
| 固定运行账号 | 可读配置和模型，可写图片、数据、日志 | __________ |

## 3. 开发机：构建和整理交付包

### 3.1 构建前准备

- 确认代码及配置模板已保存，`mvn.cmd` 在 PATH 中，准备真实 JDK 8 路径。
- 构建机需要获取 Maven 依赖，或已有项目 `target/maven-repository` 缓存。
- 使用项目现有 `build.ps1`：脚本显式选择 Java 8，不依赖也不改写 `JAVA_HOME`。
- 生产配置和生产数据放在目标运行目录，开发 `target/` 中的配置会被下一次构建的模板覆盖。
- 媒体程序、现有数据和密钥不要只保存在 `target/`；项目发布整理不需要执行 `clean`。

```powershell
# 在开发机执行；按本机安装位置修改 JDK 参数。
Set-Location -LiteralPath 'E:/idea_workSpace/TY/Exhibition stand/exhibition-backend'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/build.ps1' -Jdk8Home 'D:/Java/jdk8'
if ($LASTEXITCODE -ne 0) { throw '构建失败，停止制作部署包。' }
```

必须看到 `BUILD SUCCESS`，使用 `target/exhibition-backend-1.0.0.jar`。不要使用 `.jar.original`。默认执行 `verify`，测试使用隔离 H2 和模拟设备；`-SkipTests` 会跳过测试，不作为正常发布流程。若使用 `-TestDatasourceConfig`，只能填写专用隔离测试库，集成测试会清表，不能指向业务库。

### 3.2 Maven 会自动准备哪些内容

| 产物 | 当前 `verify` 行为 |
|---|---|
| `target/exhibition-backend-1.0.0.jar` | 生成可执行 Spring Boot JAR |
| `target/config/` | 复制 `application*.yml`、展示文案、媒体 INI 模板 |
| `target/scripts/` | 复制启动、公共定位、人脸安装/启动、码流延迟调整脚本 |
| `target/sql/` | 复制 SQL；新库使用 `schema.sql` |
| `target/face-service/` | 复制 Python、依赖清单、模型清单、README，以及源码目录中已有的模型 |
| `target/docs/`、`target/README.md` | 当前 POM 已自动复制 |
| `media-server/MediaServer.exe` 及许可证 | **不自动复制**，需要另行加入发布包 |
| Python `.venv`、解释器、pip 依赖 | **不打包**，目标机重建环境 |
| MySQL、真实图片、完整前端 | **不打包**，分别准备 |

### 3.3 按白名单制作发布目录

先完成构建，再运行以下示例。它创建全新发布目录，不把 `target` 内的测试文件、缓存、历史日志或运行中的虚拟环境整体带走。

```powershell
# 在开发机、pom.xml 所在目录执行。只整理文件，不启动服务。
$ErrorActionPreference = 'Stop'
$projectRoot = (Get-Location).Path
$buildRoot = Join-Path $projectRoot 'target'
$releaseId = Get-Date -Format 'yyyyMMdd-HHmmss'
$releaseRoot = Join-Path $projectRoot "release/exhibition-windows-$releaseId"
if (Test-Path -LiteralPath $releaseRoot) { throw '发布目录已存在，请重新生成目录名。' }

$required = @(
    'target/exhibition-backend-1.0.0.jar',
    'target/config', 'target/scripts', 'target/sql', 'target/docs',
    'target/face-service/models', 'media-server/MediaServer.exe'
)
foreach ($relativePath in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $relativePath))) {
        throw "缺少交付内容：$relativePath。请先构建并准备模型/媒体程序。"
    }
}

New-Item -ItemType Directory -Path $releaseRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $buildRoot 'exhibition-backend-1.0.0.jar') -Destination $releaseRoot
Copy-Item -LiteralPath (Join-Path $buildRoot 'README.md') -Destination $releaseRoot
foreach ($directoryName in @('config', 'scripts', 'sql', 'docs')) {
    Copy-Item -LiteralPath (Join-Path $buildRoot $directoryName) -Destination $releaseRoot -Recurse
}

# 媒体程序取项目明确维护的 media-server 目录，不依赖旧 target 的残留文件。
$releaseMedia = Join-Path $releaseRoot 'media-server'
New-Item -ItemType Directory -Path $releaseMedia | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'media-server/MediaServer.exe') -Destination $releaseMedia
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'media-server') -Filter 'LICENSE*' -File |
    Copy-Item -Destination $releaseMedia

# 只选 Python 服务所需文件，避免带走 .venv、测试和 __pycache__。
$releaseFace = Join-Path $releaseRoot 'face-service'
New-Item -ItemType Directory -Path $releaseFace | Out-Null
foreach ($fileName in @('app.py', 'download_models.py', 'requirements.txt', 'models.json', 'README.md')) {
    Copy-Item -LiteralPath (Join-Path $buildRoot "face-service/$fileName") -Destination $releaseFace
}
Copy-Item -LiteralPath (Join-Path $buildRoot 'face-service/models') -Destination $releaseFace -Recurse

# 在交付目录校验模型，防止缺模型或误带 Git LFS 指针文件。
$manifest = Get-Content -LiteralPath (Join-Path $releaseFace 'models.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($model in $manifest.models) {
    $modelPath = Join-Path $releaseFace "models/$($model.filename)"
    $actualHash = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash
    if ($actualHash -ne $model.sha256) { throw "模型摘要不符：$($model.filename)" }
    if (-not (Test-Path -LiteralPath (Join-Path $releaseFace "models/$($model.licenseFile)"))) {
        throw "缺少模型许可证：$($model.licenseFile)"
    }
}
Write-Host "发布目录已准备：$releaseRoot"
```

此时还没有压缩。先按第 6、7 节检查发布目录 `config/`，确定是填写目标环境参数，还是交由现场在解压后填写。当前源模板含开发现场凭据，发布前要逐项替换，不应把开发值当作目标配置。

### 3.4 完整交付目录

```text
exhibition/
├── exhibition-backend-1.0.0.jar
├── config/
│   ├── application.yml
│   ├── application-datasource.yml
│   ├── application-exhibition.yml
│   ├── application-swagger.yml
│   ├── exhibition-display.yml
│   └── zlmediakit.ini
├── scripts/
│   ├── runtime.ps1
│   ├── start.ps1
│   ├── start-media.ps1
│   ├── setup-face-service.ps1
│   ├── start-face-service.ps1
│   └── set-camera-latency.ps1
├── media-server/
│   ├── MediaServer.exe
│   └── LICENSE*
├── face-service/
│   ├── app.py
│   ├── download_models.py
│   ├── requirements.txt
│   ├── models.json
│   ├── README.md
│   └── models/
│       ├── face_detection_yunet_2023mar.onnx
│       ├── face_recognition_sface_2021dec.onnx
│       ├── LICENSE-YuNet
│       └── LICENSE-SFace
├── sql/                         # 新库执行 schema.sql
├── docs/
├── README.md
└── wheelhouse/                  # 完全离线时额外准备
```

首次启动后生成 `data/media/zlm-secret.txt`、`data/media/config.ini`、媒体日志，以及配置的图片目录；人脸安装生成 `face-service/.venv/`。这些运行内容不需要从开发测试目录整包复制。

### 3.5 目标机无外网时，提前准备 Python 离线依赖

当前依赖固定为 `numpy==2.5.4`、`opencv-python-headless==4.13.0.92`。安装脚本只检查 Python >= 3.12，能否安装还取决于这些固定版本是否提供对应 Python / Windows 架构的 wheel。当前开发虚拟环境记录为 **CPython 3.14.7 x64**；优先在目标机使用相同 Python 小版本系列和架构，并实测安装。

在可联网的 Windows x64 机器上，使用与目标机相同小版本系列的 Python 下载：

```powershell
# 接续 3.3 的发布目录变量；解释器替换为与目标机匹配的真实安装路径。
$buildPython = 'C:/Python314/python.exe'
& $buildPython -m pip download --only-binary=:all: -r (Join-Path $releaseRoot 'face-service/requirements.txt') -d (Join-Path $releaseRoot 'wheelhouse')
if ($LASTEXITCODE -ne 0) { throw '离线依赖下载失败，请核对 Python 版本与架构。' }
```

同时准备 Python 安装介质、Java 8、MySQL 安装介质、适配的 VC++ x64 运行库，以及两个模型和许可证。不要直接复制开发机 `.venv`；Python 官方要求在目标位置重建虚拟环境。[Python venv 文档](https://docs.python.org/3/library/venv.html)；离线包下载方式见 [pip download](https://pip.pypa.io/en/stable/cli/pip_download/)。

### 3.6 压缩、校验并传输

```powershell
# 在发布配置、模型及离线依赖准备完成后执行；接续 3.3 的变量。
$archive = "$releaseRoot.zip"
Compress-Archive -Path (Join-Path $releaseRoot '*') -DestinationPath $archive
Get-FileHash -LiteralPath $archive -Algorithm SHA256
```

把 ZIP 及记录的 SHA-256 传到目标机。目标机先计算 `Get-FileHash -LiteralPath '实际ZIP路径' -Algorithm SHA256` 并核对，再解压到 `D:/exhibition`，确保 JAR 直接位于该目录，避免多套一层同名目录。

## 4. 目标 Windows：环境和目录准备

1. 安装 Java 8，检查指定路径的 `bin/java.exe -version` 显示 `1.8`。
2. 安装 MySQL 5.7.44，或确认外部数据库可达；MySQL 服务设置为自动启动。
3. 安装选定的 Python x64，确认真实解释器路径，包含 `venv` 和 pip。
4. 安装适配目标 Windows 的 VC++ x64 运行库。当前媒体程序由 MSVC 14.44 构建，运行库需满足构建版本要求；系统支持范围和安装包见 [Microsoft 官方说明](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist/)。
5. 解压发布文件到固定目录，运行账号可读 JAR、配置、模型，可写 `data/`、图片目录、`.venv/` 和日志目录。安装与运行账号不同时，检查安装产物对运行账号的访问权限。
6. 给服务器使用稳定的 LAN 地址或 DHCP 保留地址，核对实际网卡，避免误用虚拟网卡、VPN 或回环地址。
7. 核对服务器、MySQL 和摄像头时间。Java 使用北京时间；数据库 `DATETIME` 本身不保存时区，数据库 `NOW()` 也应与北京时间一致。

```powershell
# 以下在目标 Windows 执行，安装路径按现场修改。
Set-Location -LiteralPath 'D:/exhibition'
& 'D:/Java/jdk8/bin/java.exe' -version
& 'C:/Python314/python.exe' --version
Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,IPAddress,AddressState
New-Item -ItemType Directory -Path 'D:/exhibition/data','D:/exhibition/logs' -Force | Out-Null

# 参数使用目标数据库、摄像头的真实值。TCP 成功后仍需验证实际协议和凭据。
Test-NetConnection -ComputerName '127.0.0.1' -Port 3306
Test-NetConnection -ComputerName '192.168.0.232' -Port 80
Test-NetConnection -ComputerName '192.168.0.232' -Port 554
```

## 5. 目标 Windows：初始化数据库

本文按**新建空库**部署。当前 `schema.sql` 包含全部 **9 张表**，不是文件头旧注释中的 7 张；无需再追加两个升级脚本。程序不会自动创建库表。

先用数据库管理员执行，密码及 Host 按现场填写：

```sql
CREATE DATABASE exhibition CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'exhibition_app'@'localhost' IDENTIFIED BY '替换为实际密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON exhibition.* TO 'exhibition_app'@'localhost';
```

在 Navicat 等工具中选择 `exhibition`，执行解压目录的 `sql/schema.sql`。也可进入 MySQL 命令行后执行：

```sql
USE exhibition;
SOURCE D:/exhibition/sql/schema.sql;
SHOW TABLES;
SELECT @@version, @@global.time_zone, @@session.time_zone, NOW();
```

应存在：`exh_media`、`exh_staff`、`exh_device`、`exh_staff_device`、`exh_device_identity`、`exh_capture_event`、`exh_staff_import_task`、`exh_person_daily`、`exh_face_feature_event`。

数据库跨机时，应用账号的 Host 改为数据库识别的服务器来源地址，并放行数据库侧实际端口；配置里的 `localhost` 代表 Java 所在机器。建表由管理员完成，应用日常账号使用上述读写权限。

如果 `NOW()` 与北京时间不一致，在该 MySQL 实例实际加载的 `my.ini` 中，修改原有 `[mysqld]` 节点，然后重启对应 MySQL Windows 服务并重新连接检查：

```ini
[mysqld]
default-time-zone='+08:00'
```

不要只修改当前管理工具连接的会话时区；Java 后续新连接也需要正确的数据库默认时区。默认时区参数说明见 [MySQL 官方文档](https://dev.mysql.com/doc/refman/9.7/en/time-zone-support.html)，此处使用固定 UTC+8 偏移，不依赖命名时区表。

## 6. 目标 Windows：外部配置逐项检查

### 6.1 配置读取规则

- 部署后修改 **JAR 同级的 `D:/exhibition/config/`**。`src/main/resources/` 只是开发构建模板。
- 通过项目脚本启动时会显式加入外部配置；直接 `java -jar` 也会定位 JAR 同级 `config/`。
- 外部 YAML 覆盖 JAR 内的同名配置。尽量保留整套外部文件，避免缺失文件时回退到包内开发值。
- 业务图片目录、展示文件、媒体密钥文件的相对路径以 **JAR 所在目录** 为基准。另传 `-ConfigDirectory` 只改变 Spring YAML 加载目录，不会同步改变 `display-file` 等业务路径。
- YAML 使用 UTF-8、空格缩进；修改原有节点，不重复添加第二个 `exhibition:`、`spring:` 或 `logging:` 根节点。
- 除展示文案约每 5 秒重读外，Java 普通配置需重启 Java；媒体模板修改后需重启媒体脚本，重新生成运行 INI。

### 6.2 `config/application.yml`

| 配置项 | 当前值 | 动作 |
|---|---|---|
| `server.port` | `8080` | 与前端代理、设备回调、防火墙一致 |
| `spring.profiles.active` | `datasource,exhibition,swagger` | 保留，加载三份拆分配置 |
| `spring.servlet.multipart.max-file-size` | `50MB` | Excel / 文件上传限制，按业务确认 |
| `spring.servlet.multipart.max-request-size` | `55MB` | 代理的请求体上限应能覆盖 |
| `spring.jackson.time-zone` | `Asia/Shanghai` | 保留北京时间 |
| `spring.task.scheduling.pool.size` | `4` | 正常部署保留 |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | 保留 |
| `logging.level.org.springframework.web.client.RestTemplate` | `INFO` | 保留，避免调试输出中的设备/媒体凭据 |

无人值守建议在原 `logging` 节点配置文件日志，以下片段与原 `level` 并存：

```yaml
logging:
  file:
    name: D:/exhibition/logs/exhibition.log
  logback:
    rollingpolicy:
      max-file-size: 20MB
      max-history: 30
      total-size-cap: 1GB
  level:
    org.springframework.web.client.RestTemplate: INFO
```

Java 原模板未启用文件日志；只启动控制台不会自动产生 `logs/exhibition.log`。媒体日志位于 `data/media/logs/`，与 Java 日志分开管理。

### 6.3 `config/application-datasource.yml`

```yaml
# 替换现有数据源节点，IP、端口、库名及密码按目标环境填写。
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/exhibition?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false
    username: exhibition_app
    password: '替换为目标库密码'
```

当前开发端口为 3307，目标机不是必然使用 3306。上例为同机连接；跨机连接按数据库现场要求配置 TLS。密码用 YAML 引号包裹，单引号字符串中若含单引号，以两个连续单引号转义。

### 6.4 `config/application-exhibition.yml`：通用业务与设备

以下路径均在现有 `exhibition` 根节点内修改。

| 配置路径 | 动作 / 说明 |
|---|---|
| `hall-id` | 设置实际展厅业务标识；用于统计作用域 |
| `display-file` | 默认 `./config/exhibition-display.yml`，文件必须存在 |
| `storage-directory` | 建议新部署设为 `./data/uploads`，或现场固定绝对目录；账号可读写 |
| `retention-days` | 当前 30，控制抓拍、身份及特征相关记录保留；每天北京时间 03:15 执行清理 |
| `max-import-rows` / `max-export-rows` | 当前各 500，按业务确认 |
| `heartbeat-seconds` | 当前 30，在线判定取三倍间隔；与现场心跳频率匹配 |
| `jobs-enabled` | 当前 YAML 未显式填写，Java 默认 `true`；正常部署保留，工作人员重新关联和人脸去重后台任务依赖该开关 |
| `devices[0].serial-number` | 设备真实序列号，必须与回调 JSON 一致 |
| `devices[0].base-url` | Java 可达的摄像头 HTTP / HTTPS 管理地址 |
| `devices[0].username` / `password` | 设备真实管理凭据，供主动同步和默认 RTSP 引用 |
| `devices[0].face-group-id` | 设备已有工作人员库字符串 `GroupID2`；应用不会创建脸库 |
| `devices[0].owner-identifier` | 设备已设置的拥有者 `Ownner` 原值，不能拿设备密码或临时安全码代替 |
| `devices[0].connect-timeout-ms` / `read-timeout-ms` | 当前 3000 / 15000，按设备网络情况确认 |

只为一台主动人员同步设备配置 `base-url`。仅在设备上已有人员、数据库却为空时，不会自动导入人员；身份基线接口也不会按姓名创建或关联档案。

### 6.5 视频 `exhibition.live`

| 配置项 | 当前值 / 填写动作 |
|---|---|
| `enabled` | 当前 `true`；需要视频时保留 |
| `rtsp-url` | 改成现场摄像头地址、码流路径及凭据；当前模板引用 `devices[0]` 账号密码 |
| `zlm-base-url` | 同机保持 `http://127.0.0.1:9999` |
| `zlm-secret` | 保留 `${ZLM_SECRET:}`；环境变量为空时回退到密钥文件 |
| `zlm-secret-file` | 保持 `./data/media/zlm-secret.txt`，由媒体脚本首次生成 |
| `startup-timeout-seconds` | 当前 15，首次按需拉流的就绪等待 |
| `idle-timeout-seconds` | 当前 60，无观看者后回收共享流 |

现场设备需输出可播放的 H.264；当前只传视频，无音频。设备账号密码含 `@`、`:` 等 URL 特殊字符时，直接填写正确编码的完整 RTSP URL，模板简单拼接不会自动完成 URL 编码。

`config/zlmediakit.ini` 是模板，不直接拿模板启动程序。`start-media.ps1 -RtcAddress` 自动替换 `@RTC_ADDRESS@`、`@ZLM_SECRET@`，写入 `data/media/config.ini`。管理端口保持 9999，WebRTC UDP/TCP 保持 8000。服务器 IP 变化后，重新传入新 `-RtcAddress` 启动媒体。

媒体密钥不需要交给前端或摄像头。目标机器若已配置旧 `ZLM_SECRET`，需与新密钥文件保持一致，或移除过时值；显式密钥优先于文件。不要把开发机历史 `media/` 目录误当成当前运行目录，当前脚本使用 JAR 同级 `data/media/`。

### 6.6 展示与 Swagger

`config/exhibition-display.yml` 中填写展厅名称和欢迎语：

```yaml
exhibition:
  name: 一楼展厅
  welcome-message: 欢迎莅临参观指导
```

文件首次缺失或内容无效会阻止 Java 启动；运行中修改约 5 秒生效。

`config/application-swagger.yml` 默认可保留：`/swagger-ui.html`、`/v3/api-docs`。如果关闭 Swagger，将 `springdoc.swagger-ui.enabled` 和 `springdoc.api-docs.enabled` 设为 `false`，相应验收改用业务接口。当前项目接口没有登录账号或访问令牌配置，部署访问范围由实际网络、代理和防火墙控制。

## 7. 工作人员识别与本机人脸服务配置

### 7.1 `exhibition.recognition` 配置对照

| 配置项 | 当前现场模板值 | 跨环境动作 |
|---|---|---|
| `enabled` | `true` | 新设备或身份未核实时先设 `false`，准备完成再启用 |
| `protocol-verified` / `ack-verified` | 均为 `true` | 仅在已核实现场契约时保留，不能因模板为 true 就认为新设备已验收 |
| `channel-id` | `1` | 核对摄像头实际通道 |
| `identity-field` | `personId` | 与实际回调字段一致，也支持已验证的 `uniqueId` |
| `identity-source` | `FACE_UUID` | 当前设备已验证；其他来源支持 `PERSON_ID` / `UNIQUE_ID`，不要混用 |
| `group-field` | `personType` | 核对真实报文工作人员库字段，也支持已验证的 `groupId` |
| `group-value` | 当前开发现场库 ID | 改为实际回调作用域；当前设备已核实其等于工作人员库 GroupID2 |
| `min-similarity` | `80` | 设备匹配分数门槛，取值 `(0,100]`，按现场验收 |
| `ack-person-id-type` | `STRING` | 当前设备回显 UUID 字符串；新固件需验证 |
| `unmatched-person-id` / `unmatched-unique-id` | `'0'` / `''` | 与真实未命中 ACK 契约一致 |

**设备人员同步与工作人员识别统计是两条相关流程；有视频、能上传人员，均不能单独证明识别统计可用。**

新设备或需重新核实身份时，顺序为：

1. 暂时保持 `recognition.enabled=false`，准备完整数据库、设备已有脸库和管理凭据。
2. 通过 `GET /api/admin/recognition/device-diagnostics` 只读核对设备能力、通道、规则和目标库。
3. 按 [识别接入文档](staff-recognition-integration.md) 核实真实命中/未命中报文、身份来源、库作用域、ACK 和停止重传效果，填写验收开关；普通配置修改后重启。
4. 已有后台人员绑定时，调用 `POST /api/admin/recognition/baseline` 回读并保存身份基线，检查 `data.verifiedCount`。该操作只查询设备、更新本地绑定/历史；不会把设备已有人员导入空数据库。新库无绑定时返回 0，不能当作已有设备人员已关联。
5. 人员通过后台维护 / Excel 导入形成真实设备绑定后，再核对基线及回调。把 `recognition.enabled=true` 并重启，验证工作人员当天去重。

同一已验收设备、固件和脸库迁到新服务器时，可依据原验收结果保留契约；目标库、绑定和回调地址仍需核对。启用时会校验已有身份绑定及历史是否一致，不一致应先关闭识别并完成基线核实。

### 7.2 `exhibition.face-feature` 配置对照

| 配置项 | 当前值 / 动作 |
|---|---|
| `enabled` | 当前 `true`，启用全体人员当日去重时保留 |
| `base-url` | `http://127.0.0.1:18090`；Java 只允许本机 HTTP 地址，不能随意换成另一台主机 |
| `model-id` | `opencv-sface-2021dec`，需与模型清单和 `/health` 一致 |
| `min-similarity` | `0.363`，SFace 余弦相似度；不能填写设备 80 分门槛，需现场样本验收 |
| `connect-timeout-ms` / `read-timeout-ms` | 1500 / 10000 |
| `batch-size` | 10 |
| `max-attempts` | 3 |
| `retry-seconds` | 30 |

当日全体人员去重同时依赖 `recognition.enabled=true`、`face-feature.enabled=true`、真实 FaceReco 抓拍及可用本机模型。仅开启人脸开关不会自动产生人数。缺少模型服务可能使任务重试或进入失败状态，不能仅因 Java 启动成功而判定整套功能正常。

### 7.3 联网目标机安装人脸服务

```powershell
# 目标机执行一次，真实 Python 路径按安装位置替换。
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/setup-face-service.ps1' -PythonExecutable 'C:/Python314/python.exe'
if ($LASTEXITCODE -ne 0) { throw '人脸服务安装失败。' }
```

脚本创建 `face-service/.venv`，安装固定依赖，下载或校验模型。已携带且摘要正确的模型会复用；对应 LICENSE 已携带时也无需重下。如确实需要下载代理，可增加 `-ProxyUrl 'http://实际代理IP:端口'`，不要沿用开发机本地代理地址。

### 7.4 完全离线目标机安装人脸服务

离线时使用第 3.5 节的 `wheelhouse/`。现有 `setup-face-service.ps1` 没有离线参数，不直接用它替代下面的离线安装步骤：

```powershell
Set-Location -LiteralPath 'D:/exhibition'
& 'C:/Python314/python.exe' -m venv './face-service/.venv'
if ($LASTEXITCODE -ne 0) { throw '创建 Python 虚拟环境失败。' }
& './face-service/.venv/Scripts/python.exe' -m pip install --no-index --find-links './wheelhouse' --only-binary=:all: -r './face-service/requirements.txt'
if ($LASTEXITCODE -ne 0) { throw '离线依赖安装失败，请核对 wheel 与 Python 版本。' }

# 模型和 LICENSE 均已随包携带时，该脚本直接校验复用，不需要下载。
& './face-service/.venv/Scripts/python.exe' './face-service/download_models.py'
if ($LASTEXITCODE -ne 0) { throw '模型或许可证不完整，请补齐离线交付内容。' }
```

离线安装参数参考 [pip install 官方文档](https://pip.pypa.io/en/stable/cli/pip_install/)。环境不要激活也可运行，项目始终显式调用 `.venv/Scripts/python.exe`。

## 8. 网络、防火墙和摄像头回调

### 8.1 通信方向与端口

| 来源 → 目标 | 协议 / 端口 | 用途与动作 |
|---|---|---|
| 浏览器 / 前端代理 → Java | TCP 8080，或配置的 HTTP 端口 | API、图片、播放页和 WebRTC 信令 |
| 摄像头 → Java | TCP 8080，或配置的 HTTP 端口 | 心跳、FaceSnapshot、FaceReco 回调 |
| 浏览器 → 媒体服务器 | UDP 8000、TCP 8000 | WebRTC 实际视频；浏览器直接访问 |
| Java → 本机媒体 | TCP 9999 | 管理 API，仅本机白名单；不对外放行 |
| Java → 本机人脸服务 | TCP 18090 | 特征提取，服务仅监听 127.0.0.1；不对外放行 |
| Java → MySQL | TCP 实际数据库端口 | 同机不用向外开放；跨机只允许应用来源 |
| Java → 摄像头 | TCP 实际管理端口 | 工作人员维护和设备只读诊断 |
| 媒体服务器 → 摄像头 | TCP 554，或实际 RTSP 端口 | 当前实现以 RTSP TCP 拉流 |
| 前端浏览器 → 静态服务器 | TCP 80 / 443，按前端部署 | 完整管理 / 大屏页面 |

在目标 Windows 管理员 PowerShell 中按实际可信来源添加规则，以下以同一局域网为例：

```powershell
New-NetFirewallRule -DisplayName 'Exhibition Java 8080' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'Exhibition RTC UDP 8000' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8000 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'Exhibition RTC TCP 8000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -RemoteAddress LocalSubnet
```

跨网段时将 `LocalSubnet` 改为摄像头、大屏和管理端的实际可信来源，不能因为网段不同就关闭全部防火墙。规则语法参考 [New-NetFirewallRule](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule)。只检查 8080 无法验证视频 UDP 路径，浏览器还必须能到达媒体公告 IP 的 8000 端口。

### 8.2 摄像头端必须修改的内容

在摄像头 HTTP 平台 / 报警上报设置中核对：

- 服务器地址：目标 Windows 的可达 IP / 域名，不能再填开发机 IP。
- 端口：目标 `server.port`，默认 8080。
- 回调路径：`/api/device/p6s/events`，不是前端页面地址。
- 请求方法 / 内容类型：设备协议使用 POST / JSON。
- 认证：当前后台回调不要求 OAuth 签名或访问令牌；已验收设备使用 none。
- 上报超时、心跳、抓拍和识别规则：按真实设备协议及验收记录确认；原现场超时为 10 秒。
- 设备序列号：与 `exhibition.devices[0].serial-number` 一致。
- 人脸比对：工作人员目标库、通道、推送及有效时段正确；前端人数依赖真实上报。

必须分别验证“服务器能管理设备 / 拉视频”和“设备能反向访问回调”。服务器 IP 变化后，摄像头回调、媒体 `-RtcAddress`、前端代理三处都需核对。

## 9. 首次启动：按顺序启动并检查

顺序为 **MySQL → 媒体与本机人脸服务 → Java → 前端**。首次先用三个 PowerShell 窗口前台运行，确认成功后再配置自启。

### 9.1 窗口 A：媒体

```powershell
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/start-media.ps1' -JarFile 'D:/exhibition/exhibition-backend-1.0.0.jar' -RtcAddress '192.168.2.100'
```

替换 `192.168.2.100` 为**目标服务器自身、浏览器可达的 LAN IPv4**，不能用摄像头 IP、127.0.0.1 或 0.0.0.0。脚本会保留已有有效密钥，并生成本次实际运行配置。手工启动需保留窗口。

### 9.2 窗口 B：本机人脸服务

```powershell
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/start-face-service.ps1' -Port 18090
```

另一个终端检查：

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:18090/health' -TimeoutSec 10
```

应返回 `ready=true`、`model=opencv-sface-2021dec`、`dimensions=128`。本机模型健康不代表现场抓拍质量和去重阈值已通过验收。

### 9.3 窗口 C：Java

```powershell
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/start.ps1' -Jdk8Home 'D:/Java/jdk8' -JarFile 'D:/exhibition/exhibition-backend-1.0.0.jar'
```

脚本固定 UTF-8 和 `Asia/Shanghai`，读取同级 config 与媒体密钥。显式给出 JAR 路径可避免目录内多个版本造成歧义。

### 9.4 端口、接口与 WHEP 检查

```powershell
# 在目标机执行；18090 应仅监听 127.0.0.1。
Get-NetTCPConnection -State Listen -LocalPort 8080,9999,8000,18090
Get-NetUDPEndpoint -LocalPort 8000
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/screen/config' -TimeoutSec 10
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/screen/overview' -TimeoutSec 10

# 管理密钥只放 POST 表单，不放 URL，也不输出密钥。
$mediaKey = [IO.File]::ReadAllText('D:/exhibition/data/media/zlm-secret.txt').Trim()
$mediaApi = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9999/index/api/getApiList' -Body @{secret=$mediaKey} -TimeoutSec 10
if ($mediaApi.code -ne 0 -or '/index/api/whep' -notin $mediaApi.data) {
    throw '媒体 API 或 WHEP 支持检查失败。'
}
Write-Host '媒体 API 与 WHEP 检查通过。'
```

项目未引入 Actuator，不使用 `/actuator/health` 检查 Java。`/api/screen/config` 验证展示配置，`/overview` 涉及数据库；视频仍需要实际浏览器播放确认。

## 10. 独立前端部署和代理

完整前端工程不在当前仓库，需在其工程中执行实际构建命令，复制构建后的静态文件到目标 Web 服务器。根据前端项目确认 API 基址 / 构建环境变量和 history 路由回退。

如果前端经 Nginx 与 Java 同机部署，将以下片段合并进现有站点配置：

```nginx
# 保留 /api 前缀；proxy_pass 后不加用于替换前缀的尾部斜杠。
location /api/ {
    client_max_body_size 60m;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout 90s;
    proxy_cache off;
}
```

Nginx 与 Java 分机时，上游改成目标 Java 服务器地址。当前后台未配置跨域 CORS 放行，前端优先使用同源 `/api/` 代理；HTTPS 页面需要同源 HTTPS 信令与脚本。前端播放器从 Java 的 `/api/screen/live/player.js` 加载，独立播放页为 `/api/screen/live/player`，完整接入见 [前端 WebRTC 文档](frontend-webrtc-integration.md)。

普通 HTTP 代理不会替代 WebRTC 的 UDP/TCP 8000 直连路径。不要将 9999 或 18090 配成面向浏览器的 API 地址。

## 11. 无人值守：配置开机自启

当前仓库没有 Windows 服务包装器。可用“任务计划程序”创建三个任务，MySQL 仍由其自身 Windows 服务管理。先关闭前台手工实例，再启动计划任务。

| 设置 | 媒体任务 | 人脸任务 | Java 任务 |
|---|---|---|---|
| 程序 | `powershell.exe` | `powershell.exe` | `powershell.exe` |
| 参数 | `-NoProfile -ExecutionPolicy Bypass -File "D:/exhibition/scripts/start-media.ps1" -JarFile "D:/exhibition/exhibition-backend-1.0.0.jar" -RtcAddress "实际服务器IPv4"` | `-NoProfile -ExecutionPolicy Bypass -File "D:/exhibition/scripts/start-face-service.ps1" -Port 18090` | `-NoProfile -ExecutionPolicy Bypass -File "D:/exhibition/scripts/start.ps1" -Jdk8Home "D:/Java/jdk8" -JarFile "D:/exhibition/exhibition-backend-1.0.0.jar"` |
| 起始目录 | `D:/exhibition` | 同左 | 同左 |
| 触发器 | 系统启动，等待网络后启动 | 系统启动 | 系统启动，等待数据库和依赖启动 |
| 运行账号 | 固定账号，可读配置/程序，可写 data | 可读模型和 .venv | 可写图片和 Java 日志 |

三个任务共同设置：

- “无论用户是否登录都要运行”，按目标环境填写账号凭据。
- 使用需要的运行权限；普通服务进程不因安装防火墙规则就必须一直以管理员运行。
- 取消默认的长时间运行后停止、闲置结束等会中断常驻进程的设置，核对电源条件。
- “如果任务已在运行”：不启动新实例。
- 任务失败后重试；停止任务后通过端口/PID确认子进程也已退出，再重启。
- 固定 JAR、JDK 和脚本绝对路径；JAR 文件名升级后修改任务参数。

启动延迟只提供顺序缓冲，不保证依赖已就绪；按第 9 节检查端口和健康接口。Java 使用第 6.2 节的文件日志；人脸服务如需保存启动异常，可在任务“操作”中采用同等启动命令并重定向到固定日志文件，安排日志保留。

## 12. 现场验收清单

### 12.1 基础运行

- [ ] ZIP SHA-256 与交付记录一致，目录中没有误用 `.jar.original`。
- [ ] Java 实际使用 Java 8，外部配置来自目标 `config/`。
- [ ] 数据库连接成功，9 张表完整，时间符合北京时间。
- [ ] 运行账号可写实际图片目录；上传后能用返回的媒体 ID 读取图片。
- [ ] 本机人脸 `/health` 返回正确模型与维度。
- [ ] 媒体 API 成功并支持 `/index/api/whep`。
- [ ] 可访问 `http://服务器IP:8080/swagger-ui.html`（若保留 Swagger）。
- [ ] 修改欢迎语后，约 5 秒内 `/api/screen/config` 更新。

### 12.2 设备与视频

- [ ] 摄像头心跳指向新服务器，`/api/screen/overview` 的 `cameraOnline` 正常。
- [ ] 真正发生抓拍后，后台记录和图片保存成功；不能只测空 HTTP 请求。
- [ ] 后台能只读查询设备、目标脸库和人员；需要人员维护时验证一次受控的完整维护流程。
- [ ] 在实际大屏浏览器打开 `/api/screen/live/player`，看到可解码真实画面。
- [ ] 两个观看端同时播放，关闭一个不影响另一个；持续播放超过 60 秒正常。
- [ ] 停止全部观看约 60–70 秒后流被回收，再次观看可重新建立。
- [ ] 断流重连、服务器实际 IP、防火墙和大屏所在网段验证完成。

### 12.3 识别及当日人数

- [ ] 已确认设备匹配身份、工作人员库作用域及命中/未命中 ACK，重复上报行为符合契约。
- [ ] 后台已有人真实绑定到设备；基线核实数量符合预期，不以空库的 0 作为人员已导入证据。
- [ ] 工作人员首次命中计 1，同一天重复不重复计数；卡片约 10 秒后清空，人数保留。
- [ ] 本机人脸服务参与处理，`countMode=PEOPLE_DAILY_DISTINCT`（完整功能启用时）。
- [ ] `totalCount` 表示含工作人员的总人数；`guestCount` 表示访客人数，前端不混用。
- [ ] 核对 `personCountStatus`：`AVAILABLE` 为任务完成，`PROCESSING` 为处理中，`PARTIAL` 为存在未计入的失败样本；不能把部分结果当成完整验收。
- [ ] 用现场不同人员、重复抓拍、不同光照和姿态样本核对阈值与去重，不只测一个样本。

### 12.4 重启及交付

- [ ] 停止手工窗口，计划任务能独立启动三个进程，无重复端口。
- [ ] Windows 重启后，MySQL、媒体、人脸、Java、前端恢复，摄像头回调与视频再次通过。
- [ ] 保存目标机配置、安装路径、运行账号、自启任务、日志位置与备份位置。

## 13. 后续更新和备份

1. 开发机重新执行构建验证，制作新发布目录。
2. 更新前记录当前版本，备份目标外部配置、JAR、数据库、真实图片根目录、媒体密钥及运行 INI。
3. 停止 Java；如果人脸代码/模型/依赖或媒体程序有变化，再停止对应服务。
4. 替换明确变化的 JAR / 文件，按发布说明调整现场配置。开发 `config/` 含开发值，不整体覆盖已经填写好的目标配置。
5. Python 依赖变化时，在目标机更新目标虚拟环境；目录移动或解释器变化时重建虚拟环境。
6. 保留媒体密钥，媒体模板或 IP 变化时通过启动脚本重新生成运行配置。
7. 按第 9 节启动，完成接口、图片、回调、视频、人数和自启验收。

至少定期备份：**数据库、实际 `storage-directory`、外部 config、媒体密钥和运行配置**。数据库中的图片元数据和实际图片文件需要成套保留。日志按配置轮换，额外检查媒体日志及持续增长的图片目录空间。

## 14. 常见故障定位

| 现象 | 优先核对 |
|---|---|
| Java 无法启动 | Java 版本、8080 占用、外部 YAML 语法、展示文件、数据库和完整表结构 |
| 数据库连接失败 | 把开发 3307 当成目标端口、localhost 指向错误机器、账号 Host、密码和数据库服务 |
| 人脸模块报表不存在 | `schema.sql` 是否完整执行，是否仍只按旧注释初始化了 7 张表 |
| 人脸服务无法启动 | Python 实际路径、目标 .venv、wheel 版本/架构、VC++ 运行库、模型哈希和端口 |
| 安装脚本离线失败 | 是否误用联网 setup；是否缺少 wheelhouse、模型或 LICENSE |
| 工作人员维护失败 | 设备 HTTP 可达性、凭据、拥有者、GroupID2，以及真实设备返回的错误码 |
| 设备有人员但后台没有 | 新数据库不会从设备自动导入；baseline 只核实已有绑定 |
| 有视频但无心跳/抓拍 | 设备回调仍指向开发机、路径/端口/序列号错误；视频与回调是不同方向 |
| 信令成功但无画面 | `-RtcAddress` 是否为目标服务器、浏览器能否到达 UDP/TCP 8000、实际 H.264 输出 |
| 视频 503 / 密钥错误 | 媒体进程、9999 API、WHEP 支持、实际密钥文件、旧 `ZLM_SECRET` |
| 人数为 null / 模式不对 | recognition / face-feature 开关、真实 FaceReco、首次启用与绑定、统计状态 |
| `PARTIAL` 持续存在 | 本机模型服务、失败原因、样本质量；修复后按需调用 `POST /api/admin/recognition/face-features/retry` 重试失败任务 |
| 手工正常、自启异常 | 任务账号权限、绝对路径、JDK/Python 位置、网络时序、重复实例与日志 |
| 改配置不生效 | 是否改了源码模板而非目标 config，是否重启对应进程，是否被显式环境值覆盖 |

## 15. 本次整理的验证范围

本手册基于当前仓库配置、打包规则和实现逐项核对；开发目录中的两个模型已按 `models.json` 校验 SHA-256 一致。本文命令为部署操作示例，需替换目标地址和安装路径。

本次整理不代表已在另一台 Windows 执行构建、安装、自启创建或现场验收；目标机实测结果应按第 12 节记录。未取得目标 Windows 版本、IP、安装路径及前端工程信息前，不把示例值视为最终生产配置。
