param([switch]$CheckJava)
$ErrorActionPreference = 'Stop'

function Get-BookBuddyJava {
    $candidates = [System.Collections.Generic.List[string]]::new()
    # Prefer a standalone Java 21 installation over an editor-managed runtime.
    if ($env:ProgramFiles) {
        $javaDirectory = Join-Path $env:ProgramFiles 'Java'
        if (Test-Path -LiteralPath $javaDirectory -PathType Container) {
            foreach ($installedJdk in @(Get-ChildItem -LiteralPath $javaDirectory -Directory -Filter 'jdk-21*' | Sort-Object LastWriteTime -Descending)) {
                $candidates.Add((Join-Path $installedJdk.FullName 'bin\java.exe'))
            }
        }
    }
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $pathJava = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($pathJava) { $candidates.Add($pathJava.Source) }

    foreach ($javaCandidate in @($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $javaCandidate -PathType Leaf)) { continue }
        # A present java.exe can still belong to an unusable runtime. Run a
        # bounded version check before collecting any database credentials.
        $versionProbe = [System.Diagnostics.Process]::new()
        try {
            $versionProbe.StartInfo.FileName = $javaCandidate
            $versionProbe.StartInfo.Arguments = '-version'
            $versionProbe.StartInfo.UseShellExecute = $false
            $versionProbe.StartInfo.CreateNoWindow = $true
            $versionProbe.StartInfo.RedirectStandardOutput = $true
            $versionProbe.StartInfo.RedirectStandardError = $true
            if (-not $versionProbe.Start()) { continue }
            $standardOutput = $versionProbe.StandardOutput.ReadToEndAsync()
            $standardError = $versionProbe.StandardError.ReadToEndAsync()
            if (-not $versionProbe.WaitForExit(5000)) {
                $versionProbe.Kill()
                continue
            }
            $versionText = $standardOutput.GetAwaiter().GetResult() + $standardError.GetAwaiter().GetResult()
            $javaVersion = [regex]::Match($versionText, '(?im)^(?:openjdk|java) version "(?<major>\d+)')
            if ($versionProbe.ExitCode -eq 0 -and $javaVersion.Success -and [int]$javaVersion.Groups['major'].Value -ge 21) {
                return [pscustomobject]@{ Path = $javaCandidate; Major = [int]$javaVersion.Groups['major'].Value }
            }
        } catch {
            # Try the next installation without printing inherited Java options.
        } finally { $versionProbe.Dispose() }
    }
    throw 'No working Java 21 or newer runtime was found. Check the standalone JDK installation before entering Neon credentials.'
}

$selectedJava = Get-BookBuddyJava
$java = $selectedJava.Path
Write-Host ('Using Java ' + $selectedJava.Major + ': ' + $java)
if ($CheckJava) { return }

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'myBooks\myBooks'
$jar = Join-Path $backendRoot 'target\myBooks-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Build Book Buddy first with Build-BookBuddy.cmd, then run this launcher again.'
}
# Refuse an occupied port; never stop another process on the user's behalf.
$portProbe = New-Object System.Net.Sockets.TcpClient
try {
    $portProbe.Connect('127.0.0.1', 8080)
    $portOccupied = $true
} catch [System.Net.Sockets.SocketException] {
    $portOccupied = $false
} finally { $portProbe.Dispose() }
if ($portOccupied) {
    throw 'Port 8080 is in use. Stop your current Book Buddy with Stop-BookBuddy.cmd (or Ctrl+C in its terminal), then run this launcher again.'
}

Write-Host 'Neon connection for localhost. Find these fields in your project > Connect.'
Write-Host 'Use the hostname only, for example ep-example-pooler.ap-southeast-1.aws.neon.tech.'
$databaseHost = (Read-Host 'Neon hostname').Trim()
if ($databaseHost -notmatch '^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+neon\.tech$') {
    throw 'Enter only the Neon hostname, without a protocol, port, path or connection string.'
}
$databaseName = (Read-Host 'Database name [neondb]').Trim()
if (-not $databaseName) { $databaseName = 'neondb' }
$databaseUser = (Read-Host 'Database role/user [neondb_owner]').Trim()
if (-not $databaseUser) { $databaseUser = 'neondb_owner' }
$securePassword = Read-Host 'Database password (hidden)' -AsSecureString
if ($securePassword.Length -eq 0) {
    $securePassword.Dispose()
    throw 'A database password is required.'
}

$previousEnvironment = @{}
foreach ($variableName in @('BOOKBUDDY_DB_URL', 'BOOKBUDDY_DB_USER', 'BOOKBUDDY_DB_PASSWORD')) {
    $previousEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
}
try {
    $env:BOOKBUDDY_DB_URL = 'jdbc:postgresql://' + $databaseHost + ':5432/' + [uri]::EscapeDataString($databaseName) + '?sslmode=require&channelBinding=require'
    $env:BOOKBUDDY_DB_USER = $databaseUser
    $env:BOOKBUDDY_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
    Write-Host 'Starting Book Buddy at http://localhost:8080/ with PostgreSQL.' -ForegroundColor Green
    Write-Host 'Keep this terminal open. Press Ctrl+C here to stop the app.'
    Push-Location $backendRoot
    try {
        & $java '-Xms64m' '-Xmx320m' '-jar' $jar '--spring.profiles.active=postgres' '--server.address=127.0.0.1' '--server.port=8080' '--debug=false'
        if ($LASTEXITCODE -ne 0) { throw 'Book Buddy could not start. Check the error above and the Neon connection fields.' }
    } finally { Pop-Location }
} finally {
    foreach ($variableName in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $previousEnvironment[$variableName], 'Process')
    }
    $securePassword.Dispose()
}
