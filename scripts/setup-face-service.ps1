# 安装独立 CPU 推理环境并校验官方模型；Python、模型和依赖均与 Java 后台分开维护。
# @author MoShangHuaKai
param([Parameter(Mandatory=$true)][string]$PythonExecutable, [string]$ProxyUrl)
$ErrorActionPreference = 'Stop'
$serviceDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'face-service'
$python = (Resolve-Path -LiteralPath $PythonExecutable).Path
& $python -c 'import sys; assert sys.version_info >= (3, 12), "Python 3.12 or newer required"'
if ($LASTEXITCODE -ne 0) { throw '特征服务需要 Python 3.12 或以上版本。' }
$environmentDirectory = Join-Path $serviceDirectory '.venv'
if (-not (Test-Path -LiteralPath (Join-Path $environmentDirectory 'Scripts/python.exe'))) {
    & $python -m venv $environmentDirectory
    if ($LASTEXITCODE -ne 0) { throw '创建特征服务虚拟环境失败。' }
}
$servicePython = Join-Path $environmentDirectory 'Scripts/python.exe'
& $servicePython -c 'import importlib.util, sys; sys.exit(importlib.util.find_spec("pip") is None)'
if ($LASTEXITCODE -ne 0) {
    & $servicePython -m ensurepip --upgrade --default-pip
    if ($LASTEXITCODE -ne 0) { throw '修复虚拟环境 pip 失败。' }
}
$pipArguments = @('-m', 'pip', 'install', '--disable-pip-version-check', '--no-cache-dir', '--only-binary=:all:', '-r', (Join-Path $serviceDirectory 'requirements.txt'))
if ($ProxyUrl) { $pipArguments += @('--proxy', $ProxyUrl) }
& $servicePython @pipArguments
if ($LASTEXITCODE -ne 0) { throw '特征服务依赖安装失败。' }
$downloadArguments = @((Join-Path $serviceDirectory 'download_models.py'))
if ($ProxyUrl) { $downloadArguments += @('--proxy', $ProxyUrl) }
& $servicePython @downloadArguments
if ($LASTEXITCODE -ne 0) { throw '特征模型下载或校验失败。' }
Write-Output '特征服务环境和模型已就绪。'
