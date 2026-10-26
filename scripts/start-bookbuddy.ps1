param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'myBooks\myBooks'
$logRoot = Join-Path $projectRoot 'logs'
$runRoot = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $logRoot, $runRoot -Force | Out-Null
$statePath = Join-Path $runRoot 'app.json'
$url = 'http://localhost:8080/'

function Get-Health {
    try { return Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2 }
    catch { return $null }
}

$health = Get-Health
if ($health -and $health.app -eq 'AI Book Buddy') {
    Write-Host 'Book Buddy is already running at http://localhost:8080/'
    if (-not $NoBrowser) { Start-Process $url -WindowStyle Hidden }
    exit 0
}
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw 'Port 8080 is occupied. Stop the earlier Book Buddy backend with Ctrl+C in its terminal before starting this version.'
}
$jar = Join-Path $backendRoot 'target\myBooks-0.0.1-SNAPSHOT.jar'
$sourceFiles = @(
    Get-ChildItem (Join-Path $backendRoot 'src') -Recurse -File
    Get-ChildItem (Join-Path $projectRoot 'frontend\src') -Recurse -File
    Get-Item (Join-Path $backendRoot 'pom.xml')
    Get-Item (Join-Path $projectRoot 'frontend\package-lock.json')
    Get-Item (Join-Path $projectRoot 'frontend\vite.config.js')
    Get-Item (Join-Path $projectRoot 'frontend\index.html')
)
$needsBuild = -not (Test-Path -LiteralPath $jar)
if (-not $needsBuild) {
    $lastSource = $sourceFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $needsBuild = $lastSource.LastWriteTime -gt (Get-Item $jar).LastWriteTime
}
if ($needsBuild) { & (Join-Path $PSScriptRoot 'build-bookbuddy.ps1') }
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else { (Get-Command java.exe -ErrorAction Stop).Source }
# Serve the compiled React app inside Spring: only one server process is needed.
$backend = Start-Process -FilePath $java -ArgumentList @('-Xms128m', '-Xmx512m', '-jar', ('"' + $jar + '"'), '--debug=false') `
    -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logRoot 'backend.log') `
    -RedirectStandardError (Join-Path $logRoot 'backend-error.log')
@{ backendId = $backend.Id; backendStarted = $backend.StartTime.ToUniversalTime().ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
$deadline = (Get-Date).AddSeconds(60)
do {
    $health = Get-Health
    if ($health -and $health.app -eq 'AI Book Buddy') {
        Write-Host 'Book Buddy is ready: http://localhost:8080/' -ForegroundColor Green
        Write-Host 'Use Stop-BookBuddy.cmd when finished. Nothing starts automatically with Windows.'
        if (-not $NoBrowser) { Start-Process $url -WindowStyle Hidden }
        exit 0
    }
    if ($backend.HasExited) { throw 'Book Buddy exited. See logs/backend.log and logs/backend-error.log.' }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
throw 'Book Buddy did not become ready within 60 seconds. Check logs/backend.log.'
