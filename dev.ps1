$backendPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

Push-Location $PSScriptRoot
try {
    & $backendPython -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
}
finally {
    Pop-Location
}
