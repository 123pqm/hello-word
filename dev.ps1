$backendPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

Push-Location $PSScriptRoot
try {
    & $backendPython -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
}
finally {
    Pop-Location
}
