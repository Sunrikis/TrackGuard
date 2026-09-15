$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $parts = $line.Split('=', 2)
        $key = $parts[0].Trim()
        if ($key -notmatch '^[A-Z][A-Z0-9_]+$') { throw 'Invalid environment variable name.' }
        [Environment]::SetEnvironmentVariable($key, $parts[1].Trim(), 'Process')
    }
}
