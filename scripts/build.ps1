# Java 8 构建入口，显式选择 JDK，通过 Maven 引导类构建，不读取 JAVA_HOME。 @author MoShangHuaKai
param([Parameter(Mandatory=$true)][string]$Jdk8Home, [switch]$SkipTests, [string]$TestDatasourceConfig)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if (-not $Jdk8Home -or -not (Test-Path -LiteralPath (Join-Path $Jdk8Home 'bin\java.exe'))) { throw '请用 -Jdk8Home 指定 Java 8 JDK 目录。' }
$javaExecutable = Join-Path $Jdk8Home 'bin\java.exe'
$javaVersion = (& $javaExecutable -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "1\.8\.') { throw '项目必须使用 Java 8 编译和运行。' }
$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
$mavenHome = Split-Path -Parent (Split-Path -Parent $mavenCommand.Source)
$classworlds = Get-ChildItem -LiteralPath (Join-Path $mavenHome 'boot') -Filter 'plexus-classworlds-*.jar' | Select-Object -First 1
if (-not $classworlds) { throw '未找到 Maven 引导文件，请检查 Maven 安装。' }
if ($TestDatasourceConfig) { $TestDatasourceConfig = (Resolve-Path -LiteralPath $TestDatasourceConfig).Path }
Push-Location -LiteralPath $projectDirectory
try {
    $arguments = @('-B', '-Dmaven.repo.local=target/maven-repository', 'verify')
    if ($SkipTests) { $arguments += '-DskipTests' }
    if ($TestDatasourceConfig) { $arguments += "-Dtest.datasource.config=$TestDatasourceConfig" }
    & $javaExecutable '-Dfile.encoding=UTF-8' "-Dmaven.home=$mavenHome" "-Dclassworlds.conf=$mavenHome\bin\m2.conf" "-Dmaven.multiModuleProjectDirectory=$projectDirectory" -classpath $classworlds.FullName org.codehaus.plexus.classworlds.launcher.Launcher @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败，请查看上面的错误信息。' }
} finally {
    Pop-Location
}
