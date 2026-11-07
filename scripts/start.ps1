# 从分拆后的 YAML 启动后台；所有业务配置均在文件中维护。 @author MoShangHuaKai
param([Parameter(Mandatory=$true)][string]$Jdk8Home, [string]$ConfigDirectory, [string]$JarFile)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$JarFile = Resolve-ExhibitionJar -JarFile $JarFile -ScriptDirectory $PSScriptRoot
$applicationDirectory = Split-Path -Parent $JarFile
# 与独立媒体服务共用运行密钥；也支持运维通过 ZLM_SECRET 指定同一个密钥。
$mediaSecretFile = Join-Path $applicationDirectory 'data/media/zlm-secret.txt'
if (-not $env:ZLM_SECRET -and (Test-Path -LiteralPath $mediaSecretFile)) {
    $env:ZLM_SECRET = [System.IO.File]::ReadAllText($mediaSecretFile).Trim()
}
if (-not $ConfigDirectory) { $ConfigDirectory = Join-Path $applicationDirectory 'config' }
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigDirectory).Path
$configLocation = ([System.Uri]($resolvedConfig + [System.IO.Path]::DirectorySeparatorChar)).AbsoluteUri
$javaExecutable = Join-Path $Jdk8Home 'bin\java.exe'
if ((& $javaExecutable -version 2>&1 | Out-String) -notmatch 'version "1\.8\.') { throw '项目要求 Java 8。' }
Push-Location -LiteralPath $applicationDirectory
try {
    & $javaExecutable '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' -jar $JarFile "--spring.config.additional-location=$configLocation"
    if ($LASTEXITCODE -ne 0) { throw '后台启动失败，请查看日志。' }
} finally { Pop-Location }
