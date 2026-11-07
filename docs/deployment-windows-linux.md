# JAR 打包与 Windows / Linux 部署手册

<!-- @author MoShangHuaKai -->

> Windows 本地开发、另一台 Windows 部署，请优先使用 [Windows 跨机部署操作清单](windows-deployment-checklist.md)。该清单按 2026-11-06 当前代码补齐本机人脸服务、9 张表、联网/离线安装和三项自启任务；本文保留早期 Windows / Linux 综合说明。

更新日期：2026-11-05。适用版本：Spring Boot 2.7.18、Java 8、MySQL 5.7.44，摄像头通过 ZLMediaKit WebRTC 播放。当前版本仅视频，无音频。

## 1. 部署全貌

顺序：准备环境 → 初始化数据库 → 复制部署文件 → 修改外部配置 → 启动媒体 → 启动 Java → 验收前端和设备。

| 组成 | Windows | Linux | 是否随 JAR 提供 |
|---|---|---|---|
| Java 后端 | Java 8 运行同一个 JAR | Java 8 运行同一个 JAR | JAR 包含 Java 依赖 |
| 媒体服务 | MediaServer.exe | 对应发行版及 CPU 的 MediaServer | 独立准备，不在 JAR 中 |
| 数据库 | MySQL 5.7.44 | MySQL 5.7.44 或可访问的独立实例 | 单独安装 / 配置 |
| 大屏前端 | 浏览器及独立前端静态文件 | 浏览器及独立前端静态文件 | 完整前端工程不在本仓库 |

Java 与 ZLMediaKit 是两个独立服务，启动 Java 不会自动启动媒体。默认二者同机，管理 API 通过回环地址访问。应用不自动创建库表或迁移旧表；首次部署使用新版 schema.sql 初始化空库。本次工作人员识别升级另提供一次性sql/recognition-upgrade.sql，已有调整前结构在停服备份后显式执行；新库与升级脚本二选一。识别默认关闭，启用须先完成[协议核实、身份基线与ACK验收](staff-recognition-integration.md)。

## 2. 部署前填写的信息

| 项目 | 当前值 / 示例 | 要确认什么 |
|---|---|---|
| Java | Java 8 | 实际安装路径，使用维护中的 Java 8 更新版本 |
| 数据库 | MySQL 5.7.44，exhibition 库 | IP、端口、账号、密码；当前开发端口为 3307 |
| Java HTTP | 8080 | 前端代理和摄像头回调保持一致 |
| 摄像头 HTTP | http://192.168.0.232 | 设备用户名、密码、序列号 |
| 摄像头 RTSP | 554，/live/0/MAIN | H.264，服务端能连接 |
| 设备业务参数 | 已有脸库 ID、拥有者原值 | 人员同步必须正确，应用不自动创建或设置 |
| 媒体服务器 IP | 上次验收 192.168.2.100 | 浏览器可达的服务器 IP，不是摄像头 IP |
| 媒体管理 | http://127.0.0.1:9999 | 默认 Java 与媒体同机 |
| WebRTC | UDP/TCP 8000 | 浏览器可达、防火墙放行 |
| 图片存储 | 当前 E:/tengyuan/uploadPath | Linux 必改；推荐统一为 ./data |
| 展示文案 | ./config/exhibition-display.yml | 文件必须存在，首次读取失败会阻止启动 |
| 媒体密钥 | ./data/media/zlm-secret.txt | 与媒体运行 config.ini 完全一致 |
| 设备回调 | 后端地址:8080/api/device/p6s/events | 换服务器后在摄像头网页同步修改 |

本手册默认局域网部署、Java 与媒体同机。跨公网、媒体分机及音频需另行设计或实施。

## 3. 构建 JAR

### 3.1 构建机要求

需要 JDK 8、Maven、网络或已缓存的依赖。运行服务器不需要 Maven、IDEA、源码或测试依赖。默认测试使用隔离 H2 和模拟设备；不要求生产数据库或摄像头在线，实体测试通过环境变量显式启用。

当前 Windows 开发机在 PowerShell 执行：

~~~powershell
Set-Location -LiteralPath 'E:/idea_workSpace/TY/Exhibition stand/exhibition-backend'
$env:JAVA_HOME = 'D:/Programming/Java/JDK-8/jdk1.8.0_131'
& 'E:/Maven/apache-maven-3.9.10/bin/mvn.cmd' '-Dmaven.repo.local=target/maven-repository' verify
~~~

换电脑时修改实际目录。项目也提供 scripts/build.ps1，要求 mvn.cmd 在 PATH 中：

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/build.ps1' -Jdk8Home 'D:/Java/jdk8'
~~~

Linux 构建，在后端 pom.xml 所在目录执行：

~~~bash
export JAVA_HOME=/opt/jdk8
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
mvn -Dmaven.repo.local=target/maven-repository verify
~~~

看到 BUILD SUCCESS 后，使用 target/exhibition-backend-1.0.0.jar。不要部署 .jar.original，它不是最终可执行 Spring Boot 包。

**媒体程序不是 Maven 产物。当前 Windows MediaServer.exe 在 target/media-server，执行 clean 或删除 target 前先把媒体程序、许可证、密钥和需保留的数据备份到项目外。** Maven 不会自动重新编译媒体程序。verify 会复制模板覆盖 target/config，不要把唯一生产配置长期放在开发 target 中。

### 3.2 部署文件清单

推荐 Windows D:/exhibition、Linux /opt/exhibition，两种系统保持相同相对结构：

~~~text
exhibition/
├── exhibition-backend-1.0.0.jar
├── config/
│   ├── application.yml
│   ├── application-datasource.yml
│   ├── application-exhibition.yml
│   ├── application-swagger.yml
│   ├── exhibition-display.yml
│   └── zlmediakit.ini                # 模板，含占位符
├── media-server/
│   ├── MediaServer.exe              # Linux 使用 MediaServer
│   └── LICENSE*                     # 媒体及依赖许可证
├── scripts/                         # Windows 使用的现有入口
│   ├── start.ps1
│   ├── runtime.ps1
│   └── start-media.ps1
├── sql/schema.sql
├── docs/                            # 建议携带，需手动复制
├── README.md
├── data/
│   └── media/
│       ├── zlm-secret.txt
│       ├── config.ini               # 实际运行配置
│       └── logs/
└── logs/                            # Java 文件日志按需启用
~~~

从 target 选择 JAR、config、scripts、sql、README，额外加入对应系统媒体程序及 docs。不要复制 Maven 缓存、测试数据或整个 target。现有 Maven 任务不会自动复制 docs。

完整大屏前端需独立构建部署；JAR 只提供独立播放页、播放器脚本、Swagger 和业务接口。迁移已有运行环境还要复制真实图片目录及数据库；绝对外部图片目录不一定在上述 data 中。

## 4. 初始化 MySQL

1. 安装、启动 MySQL 5.7.44 或连接已有实例。
2. 用管理员创建独立库和日常应用账号，同机示例：

~~~sql
CREATE DATABASE exhibition CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'exhibition_app'@'localhost' IDENTIFIED BY '替换为实际数据库密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON exhibition.* TO 'exhibition_app'@'localhost';
~~~

数据库跨机时账号 Host 改成数据库识别的应用源地址，并核实网络权限。建表使用具备 DDL 权限的管理员，日常运行账号不必保留建表权限。

3. 在空库执行 sql/schema.sql。Windows 可在 Navicat / IDEA 中选中 exhibition 并打开 SQL 文件执行。Linux：

~~~bash
# 使用 -p 交互输入密码，端口按现场修改。
mysql -h 127.0.0.1 -P 3306 -u root -p exhibition < /opt/exhibition/sql/schema.sql
~~~

4. 确认表已建立，再配置 Java 的数据库连接。当前开发配置是 3307，不要机械沿用示例 3306。

脚本不自动迁移旧表；已有旧库应另行安排结构处理和备份，不能认为 CREATE TABLE IF NOT EXISTS 已更新字段。目标版本为 MySQL 5.7.44，其他版本需隔离验证。

## 5. 修改 JAR 同级 config

JAR 优先读取自身同级 config，部署后修改这里的外部文件。普通配置重启 Java 生效；展示文案每 5 秒热更新。YAML 使用 UTF-8、空格缩进，不使用 Tab。

### 5.1 application.yml

保留原文件完整内容，关注端口、Profile、上传限制、时区和日志。关键片段：

~~~yaml
server:
  port: 8080
spring:
  profiles:
    active: datasource,exhibition,swagger
~~~

不要用片段覆盖整份文件。公共配置已将 RestTemplate 日志保持 INFO，不要开启会打印媒体管理表单的 DEBUG。

### 5.2 application-datasource.yml

~~~yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/exhibition?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false
    username: exhibition_app
    password: '替换为实际数据库密码'
~~~

同机示例 useSSL=false；数据库跨机时按实际 TLS 设置调整。账号密码及特殊字符按 YAML 规则填写。

### 5.3 application-exhibition.yml

在原来的 exhibition 节点内修改，不新增第二个同名根节点：

~~~yaml
exhibition:
  display-file: ./config/exhibition-display.yml
  storage-directory: ./data
  live:
    enabled: true
    rtsp-url: rtsp://${exhibition.devices[0].username}:${exhibition.devices[0].password}@192.168.0.232:554/live/0/MAIN
    zlm-base-url: http://127.0.0.1:9999
    zlm-secret: ${ZLM_SECRET:}
    zlm-secret-file: ./data/media/zlm-secret.txt
    startup-timeout-seconds: 15
    idle-timeout-seconds: 60
  devices:
    - serial-number: '替换为设备序列号'
      base-url: http://192.168.0.232
      username: '替换为设备用户名'
      password: '替换为设备密码'
      face-group-id: '替换为已有脸库ID'
      owner-identifier: '替换为设备拥有者原值'
      connect-timeout-ms: 3000
      read-timeout-ms: 15000
~~~

片段之外的 hall-id、保存期限等现有配置保留。账号密码包含 @、冒号等 RTSP 特殊字符时，单独填写正确 URL 编码后的 rtsp-url，简单拼接不会自动编码。

**Linux 必须将原 E:/tengyuan/uploadPath 改为 ./data 或 Linux 绝对目录。** Java 账号必须有图片目录的读写权限。路径以 JAR 所在目录为基准，与命令执行目录无关。

媒体密钥优先读取显式 zlm-secret，空值才回退至文件。旧环境变量 ZLM_SECRET 可能覆盖文件回退，要保持一致或移除过时变量。IDEA 使用项目 target/data/media 的密钥属于开发模式，不等于 JAR 部署路径。

设备已有脸库和拥有者需要事先准备。设备视频选择 H.264，建议 25 fps、关键帧间隔 25；实际输出仍需核实。

### 5.4 exhibition-display.yml

~~~yaml
exhibition:
  name: 一楼展厅
  welcome-message: 欢迎莅临参观指导
~~~

文件缺失或首次内容无效会阻止启动；运行中写坏则保留上次有效文案并提示日志。

### 5.5 媒体配置与密钥

config/zlmediakit.ini 是模板，@ZLM_SECRET@ 和 @RTC_ADDRESS@ 必须替换后写入 data/media/config.ini。Windows 脚本自动完成，Linux 按第 7 节初始化。

| 配置键 | 作用 / 要求 |
|---|---|
| api.secret | 与 zlm-secret.txt 一致，不能发给前端 |
| api.apiDebug | 0 |
| general.listen_ip | 0.0.0.0，允许 LAN 浏览器连接媒体 |
| http.port | 9999，与 Java 地址一致 |
| http.allow_ip_range | 127.0.0.1,::1，管理 API 仅本机 |
| rtc.externIP | 浏览器可达的媒体服务器 IP |
| rtc.port / tcpPort | UDP/TCP 8000 |
| protocol.enable_rtsp | 1，保留内部 RTSP 媒体源 |
| rtsp.port | 0，不再开放 RTSP 服务端监听 |
| protocol.enable_audio | 0，当前实现只接收视频 |

HTTP 可能监听 0.0.0.0，但 API 使用回环白名单；防火墙也不对外放行 9999。Java 与媒体分机时需要改变白名单、地址和密钥管理，本手册按同机方案执行。

## 6. Windows 部署

### 6.1 准备

- 安装 Java 8，确认 bin/java.exe 路径。
- 准备 Windows x64 MediaServer.exe 和许可证。本项目媒体程序需要对应的 Visual C++ x64 运行库；新机器缺少时安装微软对应 Redistributable。
- 文件放入 D:/exhibition，确保运行账号可读 config、可写 data / 图片目录和 logs。
- 数据库和摄像头可达，完成外部配置。

### 6.2 启动媒体服务

PowerShell 窗口 A：

~~~powershell
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/start-media.ps1' -RtcAddress '192.168.2.100'
~~~

将 IP 换成本机实际 LAN IPv4，不能填摄像头 IP。脚本生成密钥和运行配置并保留已有密钥。保留窗口，Ctrl+C 停止。不要重复启动。IP 变化后用新参数重新启动媒体。

### 6.3 启动 Java

PowerShell 窗口 B：

~~~powershell
Set-Location -LiteralPath 'D:/exhibition'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File './scripts/start.ps1' -Jdk8Home 'D:/Java/jdk8'
~~~

JDK 路径按现场调整；脚本寻找同级 JAR、config，读取媒体密钥。多个应用 JAR 时用 -JarFile 指定。也可以直接执行：

~~~powershell
& 'D:/Java/jdk8/bin/java.exe' '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' '-jar' 'D:/exhibition/exhibition-backend-1.0.0.jar'
~~~

需要 Java 文件日志时，在 application.yml 现有 logging 节点增加 file.name，例如 D:/exhibition/logs/exhibition.log，并设置 logback.rollingpolicy 的 max-history、max-file-size 和 total-size-cap。只启动控制台不会自动生成 Java 日志文件。

### 6.4 Windows 防火墙

管理员 PowerShell 按现场策略执行一次。LocalSubnet 只允许本地子网，跨路由大屏需要替换成实际可信源地址范围：

~~~powershell
New-NetFirewallRule -DisplayName 'Exhibition Java 8080' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'Exhibition RTC UDP 8000' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8000 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'Exhibition RTC TCP 8000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -RemoteAddress LocalSubnet
~~~

不开放 9999。Java 经反向代理访问时可进一步收紧 8080。不要为了播放关闭整个防火墙。

### 6.5 开机启动

当前没有 Windows 服务包装器，不能直接用 sc create 把 java.exe 当作原生 Windows Service。可在“任务计划程序”创建两个任务：

| 设置 | 媒体任务 | Java 任务 |
|---|---|---|
| 程序 | powershell.exe | powershell.exe |
| 参数 | -NoProfile -ExecutionPolicy Bypass -File "D:/exhibition/scripts/start-media.ps1" -RtcAddress "实际媒体IP" | -NoProfile -ExecutionPolicy Bypass -File "D:/exhibition/scripts/start.ps1" -Jdk8Home "D:/Java/jdk8" |
| 起始目录 | D:/exhibition | D:/exhibition |
| 触发器 | 系统启动，延迟等待网络 | 系统启动，延迟等待数据库 |
| 运行账号 | 可读配置和程序、可写数据的固定账号 | 同左 |

选择“无论用户是否登录都运行”，配置所需凭据；取消长时间运行自动停止限制；重复实例选择不启动新实例；设置失败后重启。后台任务通过状态、端口和日志检查。MySQL 由自身系统服务启动。

本节是部署建议，本次没有创建计划任务。

## 7. Linux 部署

以下假设使用 systemd、目录 /opt/exhibition，命令按发行版调整。当前尚未提供 Linux 媒体二进制，也未进行 Linux 实机验收。

### 7.1 账号、目录、依赖

~~~bash
# 管理终端执行，账号已存在则跳过 useradd。
sudo useradd --system --home-dir /opt/exhibition --shell /usr/sbin/nologin exhibition
sudo install -d -m 0750 -o exhibition -g exhibition /opt/exhibition
~~~

上传部署文件，Linux 媒体程序命名为 media-server/MediaServer，文件名区分大小写，不能使用 EXE。

~~~bash
sudo install -d -m 0750 -o exhibition -g exhibition /opt/exhibition/data/media/logs /opt/exhibition/logs
sudo chown -R exhibition:exhibition /opt/exhibition
sudo chmod 750 /opt/exhibition/media-server/MediaServer
sudo chmod 750 /opt/exhibition/config /opt/exhibition/data /opt/exhibition/data/media
sudo chmod 640 /opt/exhibition/config/*.yml /opt/exhibition/config/*.ini
java -version
file /opt/exhibition/media-server/MediaServer
ldd /opt/exhibition/media-server/MediaServer
~~~

Java 必须为 8；file 核实 CPU 架构，ldd 核实无 not found。完全静态程序可能显示不是动态可执行文件；不据此误判。不要盲目复制其他发行版的动态库。

### 7.2 Linux 媒体程序要求

准备启用 OpenSSL、libSRTP、WebRTC/WHEP 的 Linux ZLMediaKit。只开启 WebRTC CMake 选项但缺依赖，可能无法得到可用功能，要检查构建输出和实际 API。

常见构建条件：C/C++ 编译器、CMake、OpenSSL / libSRTP 开发包、上游源码及子模块。按目标系统安装依赖、构建；也可由同架构兼容构建机提供。官方文档入口：[ZLMediaKit 文档](https://docs.zlmediakit.com/)。

本项目 Windows 程序做过功能裁剪，Linux 应按相同范围准备并验收，详情见 [媒体构建记录](media-build.md)。现有 Windows SHA256 不能用于检查 Linux 二进制。当前 Windows 文件不能当作 Linux 交付物。

### 7.3 初始化媒体配置

下面使用 Python 3 标准库生成密钥和替换模板，服务器需安装 python3。它仅用于部署初始化，不参与 Java 或媒体日常运行。管理员终端执行，将 RTC_ADDRESS 换为 Linux 服务器真实 IPv4：

~~~bash
sudo -u exhibition env RTC_ADDRESS=192.168.2.100 python3 - <<'PY'
# @author MoShangHuaKai
import ipaddress
import os
import re
import secrets
from pathlib import Path

root = Path('/opt/exhibition')
address = os.environ['RTC_ADDRESS']
ip = ipaddress.IPv4Address(address)
if ip.is_loopback or ip.is_unspecified or ip.is_multicast:
    raise SystemExit('RTC_ADDRESS must be a browser-reachable server IPv4')

media = root / 'data/media'
media.mkdir(parents=True, exist_ok=True)
secret_file = media / 'zlm-secret.txt'
if secret_file.exists():
    key = secret_file.read_text(encoding='utf-8').strip()
else:
    key = secrets.token_hex(32)
    descriptor = os.open(str(secret_file), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as out:
        out.write(key)
if not re.fullmatch(r'[a-f0-9]{64}', key):
    raise SystemExit('Invalid media secret file')

configuration = (root / 'config/zlmediakit.ini').read_text(encoding='utf-8-sig')
configuration = configuration.replace('@ZLM_SECRET@', key).replace('@RTC_ADDRESS@', address)
runtime_file = media / 'config.ini'
descriptor = os.open(str(runtime_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(descriptor, 'w', encoding='utf-8') as out:
    out.write(configuration)
os.chmod(secret_file, 0o600)
os.chmod(runtime_file, 0o600)
print('Media configuration prepared; credentials not printed')
PY
~~~

重复执行保留密钥并更新实际运行配置；IP 或模板变化后重新生成并重启媒体。不要只修改模板而忘记运行 config.ini。Java 直接读取同目录密钥，无需把密钥写入命令行。

### 7.4 先在前台验证

终端 A：

~~~bash
cd /opt/exhibition/data/media
sudo -u exhibition /opt/exhibition/media-server/MediaServer -c /opt/exhibition/data/media/config.ini -l 4 -t 2 --log-dir /opt/exhibition/data/media/logs
~~~

终端 B：

~~~bash
cd /opt/exhibition
# /usr/bin/java 必须指向 Java 8，否则替换为实际绝对路径。
sudo -u exhibition /usr/bin/java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar /opt/exhibition/exhibition-backend-1.0.0.jar
~~~

Linux 不需要 PowerShell。前台验证通过后 Ctrl+C 停止两进程，再交给 systemd，避免端口冲突。

### 7.5 systemd 媒体单元

管理员创建 /etc/systemd/system/exhibition-media.service：

~~~ini
# @author MoShangHuaKai
[Unit]
Description=Exhibition ZLMediaKit
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=exhibition
Group=exhibition
WorkingDirectory=/opt/exhibition/data/media
ExecStart=/opt/exhibition/media-server/MediaServer -c /opt/exhibition/data/media/config.ini -l 4 -t 2 --log-dir /opt/exhibition/data/media/logs
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
UMask=0077
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
~~~

### 7.6 systemd Java 单元

管理员创建 /etc/systemd/system/exhibition-backend.service：

~~~ini
# @author MoShangHuaKai
[Unit]
Description=Exhibition Java Backend
Wants=network-online.target exhibition-media.service
After=network-online.target exhibition-media.service

[Service]
Type=simple
User=exhibition
Group=exhibition
WorkingDirectory=/opt/exhibition
ExecStart=/usr/bin/java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar /opt/exhibition/exhibition-backend-1.0.0.jar
Restart=on-failure
RestartSec=10
TimeoutStopSec=60
UMask=0027
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
~~~

替换实际 Java 8 路径。MySQL 同机时，按发行版将实际 mysql.service 或 mysqld.service 加入 Java 单元的 After / Wants。After 只是顺序，不保证数据库和媒体接口已就绪。媒体不通过强依赖绑定 Java，媒体故障时其他业务接口仍可提供服务。

~~~bash
sudo systemctl daemon-reload
sudo systemctl enable --now exhibition-media.service
sudo systemctl enable --now exhibition-backend.service
sudo systemctl status exhibition-media.service exhibition-backend.service
sudo journalctl -u exhibition-backend.service -n 100 --no-pager
sudo journalctl -u exhibition-media.service -n 100 --no-pager
~~~

通过 journald 配置日志磁盘上限和保留时间；媒体自身 logs 目录按实际写入量安排轮换。

### 7.7 Linux 防火墙

按实际使用 UFW、firewalld、云安全组或网络 ACL，选择相应工具，不混用。UFW 示例将源网段改为可信大屏网段：

~~~bash
sudo ufw allow from 192.168.2.0/24 to any port 8080 proto tcp
sudo ufw allow from 192.168.2.0/24 to any port 8000 proto udp
sudo ufw allow from 192.168.2.0/24 to any port 8000 proto tcp
sudo ufw status
~~~

9999 不向外放行。不要在远程连接中未确认 SSH 规则就启用防火墙。若 Java 仅经反向代理访问，按边界进一步限制 8080。

## 8. 前端和设备换服务器后的配置

前端构建独立部署。开发 / 生产代理中的 /api 指向新 Java 地址，并保留完整前缀。完整播放器接入见 [前端文档](frontend-webrtc-integration.md)。

Nginx 与 Java 同机时，合并至已有 server 块：

~~~nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout 90s;
    proxy_cache off;
}
~~~

代理与 Java 分机时改成 Java 内网 IP。HTTPS 页面应同源 HTTPS 加载脚本和信令。媒体仍由浏览器直连 rtc.externIP:8000，HTTP 代理不转发 UDP 媒体。

摄像头页面中的心跳、抓拍回调目标改为新后端的可达 IP / 域名、端口、/api/device/p6s/events；序列号与 Java 配置一致。后端能拉 RTSP 不代表设备能反向访问回调，两个方向分别验证。

## 9. 验收步骤

### 9.1 服务和端口

Windows：

~~~powershell
Get-NetTCPConnection -State Listen -LocalPort 8080,9999,8000
Get-NetUDPEndpoint -LocalPort 8000
~~~

Linux：

~~~bash
sudo ss -lntup | grep -E ':(8080|9999|8000)\b'
curl -f http://127.0.0.1:8080/v3/api-docs -o /dev/null
~~~

当前没有 Actuator，不使用 /actuator/health。Swagger 可访问说明 HTTP 运行，不代表数据库写入与摄像头都通过。

### 9.2 检查媒体支持 WHEP

Linux 复用初始化时的 Python 3，在本机执行，不输出密钥：

~~~bash
sudo -u exhibition python3 - <<'PY'
# @author MoShangHuaKai
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

key = Path('/opt/exhibition/data/media/zlm-secret.txt').read_text().strip()
request = Request('http://127.0.0.1:9999/index/api/getApiList', data=urlencode({'secret': key}).encode())
with urlopen(request, timeout=8) as response:
    result = json.load(response)
print('API code:', result.get('code'))
print('WHEP supported:', '/index/api/whep' in result.get('data', []))
PY
~~~

必须 code=0、WHEP supported=True。管理密钥通过 POST 表单传递，不放在 URL、截图或公开日志中。

### 9.3 业务及实际画面

1. 打开 http://服务器IP:8080/swagger-ui.html。
2. 打开 http://服务器IP:8080/api/screen/live/player，确认真实画面和解码，不只检查 200。
3. 实际大屏浏览器播放，检查防火墙、网络、自动播放和容器尺寸；当前仅静音视频。
4. 两个页面同时播放，关闭一个后另一个继续。
5. 持续观看超过 60 秒正常；停止全部观看约 60–70 秒后回收共享流。
6. 断流后自动重连，旧会话不复用。
7. 检查图片目录写权限、授权业务维护和数据库读写；不要随意向生产设备新增测试人员。
8. 检查设备心跳、抓拍回调，以及双方时钟。
9. 日志不出现设备密码和媒体密钥。
10. 通过实物同步时钟测延迟，connected 不能直接证明毫秒级延迟。

## 10. 日常启动、升级和备份

### 10.1 日常运行

无需重复建库或打包。启动数据库 → 媒体 → Java → 前端。停止先关闭观看、停止 Java，再停媒体。

~~~bash
sudo systemctl stop exhibition-backend.service
sudo systemctl stop exhibition-media.service
# 启动顺序相反：
sudo systemctl start exhibition-media.service
sudo systemctl start exhibition-backend.service
~~~

### 10.2 更新 JAR

1. 构建机 verify 通过，明确发布版本。
2. 备份原 JAR、外部配置、数据库、真实图片目录、媒体密钥和运行配置。
3. 停止 Java，替换 JAR；文件名变化时同步 systemd 或脚本参数。
4. 按发布说明调整生产配置，保留现场账号、IP、目录和密钥，不用开发 config 整体覆盖。
5. 表结构变化另行明确处理；项目不自动迁移，回退 JAR 也不保证兼容改过的数据库。
6. 媒体程序和媒体模板未变化时不必重启媒体；变化则重新生成运行配置并重启。
7. 完成第 9 节验收；回退计划包含数据库和文件，不只是 JAR。

### 10.3 必须保留

MySQL 数据库、真实 storage-directory、外部 config、媒体密钥、运行配置、媒体程序及许可证。备份媒体密钥和设备参数时限制访问。

运行目录不能当作随时删除的构建缓存，JAR 位置决定默认 config 和 data 路径。

## 11. 常见问题

| 现象 | 优先检查 |
|---|---|
| no main manifest / 启动类缺失 | 是否误用 .jar.original，Spring Boot repackage 是否完成 |
| Java 版本错误 | systemd / 脚本实际 Java 路径，不只看交互终端 |
| 数据库连接失败 | 服务、3306/3307、库名、密码、账号 Host 和网络 |
| 表 / 字段缺失 | 新库是否执行 SQL，旧表不会自动升级 |
| 展厅文案读取失败 | JAR 同级 config 文件存在且有效、运行账号可读 |
| Linux 图片无法写 | 是否残留 Windows 路径，父目录可访问和目录可写 |
| Linux MediaServer 无法执行 | 错用 EXE、CPU 不符、缺动态库或执行权限 |
| 缺 WHEP API | 媒体构建的 WebRTC / OpenSSL / libSRTP 是否完整 |
| 密钥错误或 503 | 实际运行配置与密钥文件一致，无旧 ZLM_SECRET 覆盖 |
| 协商成功但媒体超时 | rtc.externIP、8000 UDP/TCP、防火墙、跨机路由 |
| 端口占用 | IDEA、手工、计划任务、systemd 是否重复启动 |
| 改配置不生效 | 是否改错模板 / 运行文件，是否重启对应进程 |
| 视频有但抓拍没有 | 摄像头回调目标、序列号、抓拍开关 |
| 后端有但完整大屏没有 | 完整前端工程需独立构建部署 |

## 12. 当前交付和验证范围

Windows 已验证 Java 8 verify、实体摄像头视频、双观看者、自动重连和空闲回收，见 [verification.md](verification.md)。

Linux 部署命令和 systemd 为依据代码整理的待现场执行示例，本轮未在 Linux 主机安装、编译或运行；Linux MediaServer 尚未随当前 Windows 产物交付。远端大屏、跨公网、长期无人值守及音频不属于本轮已验收范围。

相关资料：[README](../README.md)、[前端接入](frontend-webrtc-integration.md)、[媒体构建记录](media-build.md)。
