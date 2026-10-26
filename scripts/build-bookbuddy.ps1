$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Test-Path 'node_modules\vite\bin\vite.js')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & npm.cmd run lint
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'myBooks\myBooks')
try {
    & .\mvnw.cmd package
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests or packaging failed.' }
} finally { Pop-Location }
Write-Host 'Built and checked Book Buddy. Start it with Start-BookBuddy.cmd.' -ForegroundColor Green
