# 调整 P6S 抓拍时效：先备份完整配置，保留其他字段，写入后逐项回读核实。
# 凭据由调用方提供，不写入脚本、备份或控制台；恢复时仍先备份设备当前配置。
# @author MoShangHuaKai
param(
    [Parameter(Mandatory=$true)][string]$DeviceUrl,
    [Parameter(Mandatory=$true)][PSCredential]$Credential,
    [ValidateRange(0, 30000)][int]$FilterDurationMs = 1000,
    [ValidateRange(1, 600)][int]$RepeatPushIntervalSeconds = 3,
    [string]$BackupDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'target/camera-latency'),
    [string]$RestoreFrom,
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'
$endpoint = $DeviceUrl.TrimEnd('/') + '/AI/FaceSnapshotCfg'
$networkCredential = $Credential.GetNetworkCredential()
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($networkCredential.UserName + ':' + $networkCredential.Password)) }
$utf8 = New-Object Text.UTF8Encoding($false)

function Read-SnapshotConfig {
    # 固定读取抓拍接口；禁止解析 DTD 和外部实体，不通过此脚本执行任意设备命令。
    $response = Invoke-WebRequest -Uri $endpoint -Method Get -Headers $headers -TimeoutSec 15 -UseBasicParsing
    $settings = New-Object Xml.XmlReaderSettings
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create((New-Object IO.StringReader([string]$response.Content)), $settings)
    try {
        $document = New-Object Xml.XmlDocument
        $document.XmlResolver = $null
        $document.Load($reader)
    } finally { $reader.Dispose() }
    if ($document.DocumentElement.Name -ne 'AIFaceSnapshotCfg') { throw '设备未返回有效抓拍配置，停止写入。' }
    return ,$document
}

$current = Read-SnapshotConfig
$desired = $current.CloneNode($true)
if ($RestoreFrom) {
    # 仅接受本脚本保存的抓拍配置；恢复也使用同一个固定接口，不修改脸库、人员及识别阈值。
    $desired = New-Object Xml.XmlDocument
    $desired.XmlResolver = $null
    $desired.Load((Resolve-Path -LiteralPath $RestoreFrom).Path)
    if ($desired.DocumentElement.Name -ne 'AIFaceSnapshotCfg') { throw '恢复文件不是抓拍配置。' }
} else {
    # fast 优先上报，最大筛选时长单位为毫秒；重复推送间隔单位为秒，真机页面范围为1至600。
    # 只修改这四项：完整保留人脸质量、背景图、检测范围、时间表及所有固件扩展字段。
    $changes = [ordered]@{
        SnapshotMode = 'fast'
        MaxFilterDuration = [string]$FilterDurationMs
        IsRepeatPush = 'true'
        RepeatPushInterval = [string]$RepeatPushIntervalSeconds
    }
    foreach ($field in $changes.Keys) {
        $node = $desired.DocumentElement.SelectSingleNode($field)
        if ($null -eq $node) { throw "设备缺少字段 $field，停止写入，不猜测固件契约。" }
        $node.InnerText = $changes[$field]
    }
}

$fields = @('SnapshotMode', 'MaxFilterDuration', 'IsRepeatPush', 'RepeatPushInterval')
foreach ($field in $fields) {
    $before = $current.DocumentElement.SelectSingleNode($field)
    $after = $desired.DocumentElement.SelectSingleNode($field)
    if ($null -eq $before -or $null -eq $after) { throw "抓拍配置缺少字段 $field，停止写入。" }
    [pscustomobject]@{ Field = $field; Before = $before.InnerText; After = $after.InnerText }
}
if ($Preview) { return }

# 唯一文件名避免覆盖先前的回退点；备份必须成功落盘后才允许发送 PUT。
$null = New-Item -ItemType Directory -Path $BackupDirectory -Force
$backupFile = Join-Path $BackupDirectory ('snapshot-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N') + '.xml')
[IO.File]::WriteAllText($backupFile, $current.OuterXml, $utf8)
Write-Host "原配置备份：$backupFile"
$response = Invoke-WebRequest -Uri $endpoint -Method Put -Headers $headers -TimeoutSec 15 -UseBasicParsing `
    -ContentType 'application/xml; charset=utf-8' -Body $utf8.GetBytes($desired.OuterXml)
# HTTP 200 只代表传输成功；厂商业务码必须为0，随后回读确认设备实际保存了目标值。
[xml]$ack = $response.Content
$code = $ack.SelectSingleNode('//statusCode')
if ($null -eq $code -or $code.InnerText.Trim() -ne '0') {
    throw "设备未确认写入成功，请核对设备状态；原配置已备份：$backupFile"
}
$saved = Read-SnapshotConfig
foreach ($field in $fields) {
    $actual = $saved.DocumentElement.SelectSingleNode($field)
    if ($null -eq $actual -or $actual.InnerText -ne $desired.DocumentElement.SelectSingleNode($field).InnerText) {
        throw "设备回读字段 $field 与目标不一致；原配置已备份：$backupFile"
    }
}
Write-Host '抓拍配置已写入并回读核实。人员入镜到大屏显示的实际耗时仍需现场走动验证。'
