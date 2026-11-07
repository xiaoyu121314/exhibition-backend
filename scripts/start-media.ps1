# 独立启动媒体服务，不与 Java 进程绑定。运行目录与 JAR 相同。 @author MoShangHuaKai
param([string]$MediaServerFile, [string]$JarFile, [string]$RtcAddress)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$JarFile = Resolve-ExhibitionJar -JarFile $JarFile -ScriptDirectory $PSScriptRoot
$applicationDirectory = Split-Path -Parent $JarFile
if (-not $MediaServerFile) { $MediaServerFile = Join-Path $applicationDirectory 'media-server/MediaServer.exe' }
$mediaExecutable = (Resolve-Path -LiteralPath $MediaServerFile).Path
$mediaDirectory = Join-Path $applicationDirectory 'data/media'
New-Item -ItemType Directory -Path $mediaDirectory -Force | Out-Null
# 文件既供媒体服务使用，也供 start.ps1 加载；密钥不进入参数、控制台和源码模板。
$secretFile = Join-Path $mediaDirectory 'zlm-secret.txt'
if (-not (Test-Path -LiteralPath $secretFile)) {
    $randomBytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
    $secret = ([BitConverter]::ToString($randomBytes)).Replace('-', '').ToLowerInvariant()
    [IO.File]::WriteAllText($secretFile, $secret, (New-Object Text.UTF8Encoding($false)))
}
$secret = [IO.File]::ReadAllText($secretFile).Trim()
if ($secret -notmatch '^[a-f0-9]{64}$') { throw '媒体服务密钥文件无效，请检查 data/media/zlm-secret.txt。' }
$template = [IO.File]::ReadAllText((Join-Path $applicationDirectory 'config/zlmediakit.ini'))
# 单张有效物理网卡时自动选择；多网卡必须显式指定，避免向浏览器公告虚拟网卡或代理地址。
if (-not $RtcAddress) {
    $physical = @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | Select-Object -ExpandProperty ifIndex)
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.InterfaceIndex -in $physical -and $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.AddressState -eq 'Preferred'
    } | Select-Object -ExpandProperty IPAddress -Unique)
    if ($addresses.Count -ne 1) { throw '请指定 -RtcAddress 本机局域网IP；应是大屏浏览器能访问的媒体服务器地址。' }
    $RtcAddress = $addresses[0]
}
$parsedAddress = $null
if (-not [Net.IPAddress]::TryParse($RtcAddress, [ref]$parsedAddress) -or
    $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
    [Net.IPAddress]::IsLoopback($parsedAddress) -or $RtcAddress -eq '0.0.0.0') {
    throw 'RtcAddress 必须是浏览器可达的本机 IPv4，不能使用回环地址。'
}
$configuration = $template.Replace('@ZLM_SECRET@', $secret).Replace('@RTC_ADDRESS@', $RtcAddress)
$configFile = Join-Path $mediaDirectory 'config.ini'
[IO.File]::WriteAllText($configFile, $configuration, (New-Object Text.UTF8Encoding($false)))
# 显示本次实际使用的配置，便于区分源码模板与部署目录；不输出密钥或设备地址。
Write-Host ('正在启动 ZLMediaKit，运行配置：' + $configFile)
Write-Host ('WebRTC 地址：' + $RtcAddress + '，需要浏览器可达 UDP/TCP 8000；管理 API 仅允许本机访问。')
Write-Host '手动启动时请保留此窗口；按 Ctrl+C 停止。日志较少属于正常现象。'
Push-Location -LiteralPath $mediaDirectory
try {
    # 仅输出 Error 等级，避免上游 RTSP 诊断包含设备鉴权信息；后台返回脱敏故障原因。
    & $mediaExecutable '-c' $configFile '-l' '4' '-t' '2' '--log-dir' (Join-Path $mediaDirectory 'logs')
    if ($LASTEXITCODE -ne 0) { throw '媒体服务启动失败，请检查配置、端口和目录权限。' }
} finally { Pop-Location }
