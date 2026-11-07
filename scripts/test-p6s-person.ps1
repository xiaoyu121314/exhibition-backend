# P6S 已有人员资料、照片更新测试；只在手动运行且未传 -Preview 时请求设备。
# 官方参数参考：http://p6scgi-api.p6sai.com:4999/web/#/663863654/149227016
# @author MoShangHuaKai
param(
    [Parameter(Mandatory=$true)][string]$DeviceUrl,
    [Parameter(Mandatory=$true)][string]$OwnerIdentifier,
    [Parameter(Mandatory=$true)][string]$FaceGroupId,
    [Parameter(Mandatory=$true)][string]$FaceUuid,
    [Parameter(Mandatory=$true)][string]$Name,
    [ValidateSet('male', 'female')][string]$Sex = 'male',
    [ValidateSet('UpdateFace', 'ModifyInfo')][string]$Action = 'UpdateFace',
    [string]$PhotoPath,
    [string]$Username = 'admin',
    [ValidateSet('none', 'IC', 'passport', 'other')][string]$CertificateType = 'none',
    [string]$CertificateId = '',
    [string]$Country = '',
    [string]$Address = '',
    [string]$Birthday = '',
    [switch]$Preview
)
$ErrorActionPreference = 'Stop'

# OwnerIdentifier 是已经设置到设备的脸库拥有者原值，不是摄像头密码、回调 Token 或安全码。
# 每次请求重新生成时间；同一请求的安全码与 SystemTime 必须使用同一个时间字符串。
$systemTime = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
    [DateTime]::UtcNow, 'China Standard Time').ToString('yyyy-MM-ddTHH:mm:ss')
$md5 = [Security.Cryptography.MD5]::Create()
try {
    $hashBytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($OwnerIdentifier + $systemTime))
} finally { $md5.Dispose() }
$hashText = ([BitConverter]::ToString($hashBytes)).Replace('-', '').ToLowerInvariant()
$codeChars = $hashText.Substring(0, 8).ToCharArray()
[Array]::Reverse($codeChars)
$safetyCode = -join $codeChars

if ($Action -eq 'UpdateFace') {
    if (-not $PhotoPath) { throw 'UpdateFace 必须指定 -PhotoPath，官方参数表要求 file 非空。' }
    $photo = Get-Item -LiteralPath $PhotoPath
    if ($photo.PSIsContainer) { throw 'PhotoPath 必须是图片文件。' }
    $extension = $photo.Extension.ToLowerInvariant()
    if ($extension -notin @('.jpg', '.jpeg', '.png')) { throw '测试图片请使用 JPG 或 PNG。' }
    $mimeType = if ($extension -eq '.png') { 'image/png' } else { 'image/jpeg' }
    $url = $DeviceUrl.TrimEnd('/') + '/FaceGroup/UpdatePersonInfoAndFaceImage'
    $fields = [ordered]@{
        Name = $Name; Sex = $Sex; Ownner = $safetyCode
        FaceGroupID = $FaceGroupId; FaceUUID = $FaceUuid; SystemTime = $systemTime
    }
    if ($Preview) {
        # 预览不请求设备，也不读取或输出照片内容。
        [pscustomobject]@{ Method = 'POST'; Url = $url; Fields = $fields; File = $photo.FullName }
        return
    }
    # curl 自动生成 multipart boundary；不要手动设置 Content-Type。
    # 文本字段用 --form-string，防止姓名等字段中的 @ 被解释为文件上传。
    $curlArguments = @('--silent', '--show-error', '--include', '--basic', '--user', $Username,
        '--request', 'POST', $url)
    foreach ($key in $fields.Keys) {
        $curlArguments += @('--form-string', ($key + '=' + $fields[$key]))
    }
    # curl 的 multipart 文件名规则使用双引号包裹路径，支持路径中的空格、逗号和分号。
    $curlArguments += @('--form', ('file=@"' + $photo.FullName + '";type=' + $mimeType))
    & curl.exe @curlArguments
    if ($LASTEXITCODE -ne 0) { throw '设备请求未完成，请检查 curl 输出。' }
} else {
    $url = $DeviceUrl.TrimEnd('/') + '/FaceGroup/ModifyPersonInfo'
    # 基础资料接口是 XML；所有可修改字段完整提交。空值可能清空原字段，请先填入需保留的值。
    # PersonID 在当前官方文档中标为不再使用，使用 FaceUUID 定位已有人员。
    $fields = [ordered]@{
        Name = $Name; Sex = $Sex; CertificateType = $CertificateType; CertificateID = $CertificateId
        Country = $Country; Address = $Address; Birthday = $Birthday; Ownner = $safetyCode
        FaceGroupID = $FaceGroupId; FaceUUID = $FaceUuid; SystemTime = $systemTime
    }
    $xmlNodes = foreach ($key in $fields.Keys) {
        '<' + $key + '>' + [Security.SecurityElement]::Escape([string]$fields[$key]) + '</' + $key + '>'
    }
    $body = '<?xml version="1.0" encoding="utf-8"?><PersonInfo>' + ($xmlNodes -join '') + '</PersonInfo>'
    if ($Preview) {
        [pscustomobject]@{ Method = 'PUT'; Url = $url; Body = $body }
        return
    }
    # 先写 UTF-8 临时文件，再由 curl 原样读取，避免 PowerShell 命令行编码损坏中文姓名。
    $bodyFile = Join-Path ([IO.Path]::GetTempPath()) ('p6s-person-' + [Guid]::NewGuid().ToString('N') + '.xml')
    try {
        [IO.File]::WriteAllText($bodyFile, $body, (New-Object Text.UTF8Encoding($false)))
        & curl.exe '--silent' '--show-error' '--include' '--basic' '--user' $Username `
            '--request' 'PUT' $url '--header' 'Content-Type: application/xml; charset=utf-8' `
            '--data-binary' ('@' + $bodyFile)
        if ($LASTEXITCODE -ne 0) { throw '设备请求未完成，请检查 curl 输出。' }
    } finally {
        if (Test-Path -LiteralPath $bodyFile) { Remove-Item -LiteralPath $bodyFile }
    }
}

# curl 运行时交互询问设备账号密码。HTTP 200 不代表业务成功，还需检查 XML 中 statusCode 是否为 0。
Write-Host '请检查响应 XML：官方成功示例的 statusCode 为 0；本脚本不会自动把 HTTP 200 判定为成功。'
