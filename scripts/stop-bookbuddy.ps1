$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.run\app.json'
if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Host 'No launcher-managed Book Buddy process is recorded. For a terminal-started server, press Ctrl+C in that terminal.'
    exit 0
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$process = Get-Process -Id $state.backendId -ErrorAction SilentlyContinue
# A timestamp prevents stopping an unrelated app if Windows reuses the recorded PID.
if ($process -and $process.StartTime.ToUniversalTime().ToString('o') -eq $state.backendStarted) {
    Stop-Process -Id $process.Id
    $process.WaitForExit(10000) | Out-Null
    Write-Host 'Book Buddy stopped. Your books and accounts remain in H2.'
} else {
    Write-Host 'The recorded Book Buddy process has already stopped.'
}
