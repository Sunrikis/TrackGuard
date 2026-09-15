param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'load-env.ps1')
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) { throw 'Configure .env first. See README.md or run scripts/init-database.ps1.' }
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { throw 'DB_PASSWORD is missing in .env.' }
$logDir = Join-Path $projectRoot 'storage'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$jar = Join-Path $projectRoot 'backend/target/trackguard-2.0.0.jar'
$vite = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
if (-not $NoBuild) {
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath $vite)) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    $maven = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { 'mvn.cmd' }
    Push-Location (Join-Path $projectRoot 'backend')
    try { & $maven -B -q package; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } }
    finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $vite)) { throw 'Build files missing. Run start.ps1 without -NoBuild.' }
foreach ($port in @(8080,5173)) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $port is in use. Stop the existing project process first, or use its running page." }
}
$backend = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList '-jar',('"' + $jar + '"') -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'backend-stdout.log') -RedirectStandardError (Join-Path $logDir 'backend-stderr.log')
$frontend = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList ('"' + $vite + '"'),'--host','127.0.0.1','--port','5173' -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'frontend-stdout.log') -RedirectStandardError (Join-Path $logDir 'frontend-stderr.log')
@{ backend = $backend.Id; frontend = $frontend.Id; project = $projectRoot } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logDir 'processes.json') -Encoding UTF8
Write-Output 'TrackGuard is starting. Open http://127.0.0.1:5173 after backend initialization. Logs: storage/.'
