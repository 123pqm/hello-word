param([switch]$MySQL)

$backendPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

Push-Location $PSScriptRoot
try {
    $testArguments = @("-m", "pytest", "-q", "-p", "no:cacheprovider")
    if ($MySQL) { $testArguments += "--mysql" }
    & $backendPython @testArguments
    $testExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $testExitCode
