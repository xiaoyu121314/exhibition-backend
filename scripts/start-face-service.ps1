# 前台启动本机特征服务；后台运行时由调用者使用隐藏窗口和日志重定向。
# @author MoShangHuaKai
param([int]$Port = 18090)
$ErrorActionPreference = 'Stop'
$serviceDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'face-service'
$python = Join-Path $serviceDirectory '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw '请先运行 setup-face-service.ps1 安装推理环境。' }
& $python (Join-Path $serviceDirectory 'app.py') --port $Port
if ($LASTEXITCODE -ne 0) { throw '特征服务启动失败，请核对模型和端口。' }
