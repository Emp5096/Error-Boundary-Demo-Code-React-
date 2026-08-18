param(
    [ValidateRange(128, 2048)]
    [int]$HeapMb = 384,
    [switch]$CrashOnOom
)

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$appDirectory = Join-Path $repositoryRoot "demo-app"
$jarPath = Join-Path $appDirectory "target\demo-app-1.0.0-SNAPSHOT.jar"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "JAR 不存在。请先在仓库根目录执行：mvn -pl demo-app -am package"
}

$runtimeDirectories = @(
    (Join-Path $appDirectory "runtime\gc"),
    (Join-Path $appDirectory "runtime\dump"),
    (Join-Path $appDirectory "runtime\tmp"),
    (Join-Path $appDirectory "runtime\logs"),
    (Join-Path $appDirectory "runtime\evidence"),
    (Join-Path $appDirectory "runtime\export-output")
)
foreach ($directory in $runtimeDirectories) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

$jvmArguments = @(
    "-Xms128m",
    "-Xmx${HeapMb}m",
    "-XX:+UseG1GC",
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=./runtime/dump",
    "-XX:ErrorFile=./runtime/logs/hs_err_pid%p.log",
    "-XX:NativeMemoryTracking=summary",
    "-Xlog:gc*,safepoint:file=./runtime/gc/gc.log:time,uptime,level,tags:filecount=5,filesize=20m",
    "-Djava.io.tmpdir=./runtime/tmp",
    "-Dfile.encoding=UTF-8"
)

if ($CrashOnOom) {
    $jvmArguments += "-XX:+ExitOnOutOfMemoryError"
    Write-Host "危险模式：发生 OOME 后整个 Spring Boot JVM 会退出，并尝试写入 heap dump。" -ForegroundColor Yellow
} else {
    Write-Host "普通模式：错误任务可在页面手动停止；只有配置正数保护时长时才会自动停止。" -ForegroundColor Green
}

$jvmArguments += @("-jar", $jarPath)
Push-Location $appDirectory
try {
    & java $jvmArguments
} finally {
    Pop-Location
}
