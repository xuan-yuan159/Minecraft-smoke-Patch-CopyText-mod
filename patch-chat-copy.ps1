param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $ClientJar,
    [string] $OutputJar = ""
)

$ErrorActionPreference = "Stop"
$packageRoot = $PSScriptRoot
$sourceDirectory = Join-Path $packageRoot "src"
$buildDirectory = Join-Path $packageRoot "build"
$resolvedClientJar = (Resolve-Path -LiteralPath $ClientJar).Path

if ([string]::IsNullOrWhiteSpace($OutputJar)) {
    $resolvedOutputJar = Join-Path (Split-Path -Parent $resolvedClientJar) "Smoke.jar.chatcopy.patched"
} else {
    $resolvedOutputJar = [System.IO.Path]::GetFullPath($OutputJar)
}

$outputDirectory = Split-Path -Parent $resolvedOutputJar

if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    throw "输出目录不存在: $outputDirectory"
}

if (Test-Path -LiteralPath $resolvedOutputJar -PathType Leaf) {
    throw "输出文件已存在: $resolvedOutputJar"
}

New-Item -ItemType Directory -Path $buildDirectory -Force | Out-Null

$asmExports = @(
    "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED"
)

& javac -encoding UTF-8 -source 8 -target 8 -d $buildDirectory (Join-Path $sourceDirectory "ChatCopyHook.java") (Join-Path $sourceDirectory "AutoGGHook.java")

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& javac @asmExports -encoding UTF-8 -d $buildDirectory (Join-Path $sourceDirectory "PatchSmokeChatCopy.java")

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java @asmExports -classpath $buildDirectory PatchSmokeChatCopy $resolvedClientJar $buildDirectory $resolvedOutputJar

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output "已生成补丁: $resolvedOutputJar"
