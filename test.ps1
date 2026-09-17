param([switch]$MySQL)

$backendPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

Push-Location $PSScriptRoot
try {
    # 使用项目内的新临时目录，避免系统临时目录中旧测试文件的权限问题。
    $testTemp = Join-Path $PSScriptRoot (".pytest_cache\runs\" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path (Split-Path $testTemp -Parent) -Force | Out-Null
    $testArguments = @("-m", "pytest", "-q", "-p", "no:cacheprovider", "--basetemp", $testTemp)
    if ($MySQL) { $testArguments += "--mysql" }
    & $backendPython @testArguments
    $testExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $testExitCode
