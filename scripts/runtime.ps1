# 定位部署 JAR，源码目录执行脚本时才回退到 target；部署目录不依赖 src 或 target。 @author MoShangHuaKai
function Resolve-ExhibitionJar {
    param([string]$JarFile, [Parameter(Mandatory=$true)][string]$ScriptDirectory)
    if ($JarFile) {
        $resolvedJar = (Resolve-Path -LiteralPath $JarFile).Path
        if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf) -or [System.IO.Path]::GetExtension($resolvedJar) -ne '.jar') { throw 'JarFile 必须指向可执行 JAR 文件。' }
        return $resolvedJar
    }
    $scriptParent = Split-Path -Parent $ScriptDirectory
    foreach ($candidateDirectory in @($scriptParent, (Join-Path $scriptParent 'target'))) {
        if (-not (Test-Path -LiteralPath $candidateDirectory -PathType Container)) { continue }
        $jars = @(Get-ChildItem -LiteralPath $candidateDirectory -Filter '*.jar' -File)
        # 开发 target 可能包含格式化器等工具JAR，优先选择本项目构建产物。
        $applicationJars = @($jars | Where-Object { $_.Name -like 'exhibition-backend-*.jar' })
        if ($applicationJars.Count -eq 1) { return $applicationJars[0].FullName }
        if ($applicationJars.Count -gt 1) { throw '目录内存在多个应用版本，请通过 -JarFile 明确指定。' }
        if ($jars.Count -eq 1) { return $jars[0].FullName }
        if ($jars.Count -gt 1) { throw '目录内存在多个 JAR，请通过 -JarFile 明确指定。' }
    }
    throw '找不到可执行 JAR，请先构建项目，或通过 -JarFile 指定文件。'
}
