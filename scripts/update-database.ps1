param([string]$Server = 'localhost', [string]$Database = 'RFOID_TrackGuard')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Add-Type -AssemblyName System.Data
$connection = New-Object System.Data.SqlClient.SqlConnection
$builder = New-Object System.Data.SqlClient.SqlConnectionStringBuilder
$builder['Data Source'] = $Server
$builder['Initial Catalog'] = $Database
$builder['Integrated Security'] = $true
$builder['Encrypt'] = $true
$builder['TrustServerCertificate'] = $true
$builder['Connect Timeout'] = 8
$connection.ConnectionString = $builder.ConnectionString
try {
    $connection.Open()
    $transaction = $connection.BeginTransaction()
    $command = $connection.CreateCommand()
    $command.Transaction = $transaction
    $schema = Get-Content -LiteralPath (Join-Path $projectRoot 'backend/src/main/resources/schema.sql') -Raw -Encoding UTF8
    foreach ($batch in ($schema -split '(?m)^GO\s*$')) {
        if ($batch.Trim()) { $command.CommandText = $batch; [void]$command.ExecuteNonQuery() }
    }
    $transaction.Commit()
    Write-Output 'TrackGuard schema updated. Existing accounts, inspection records and media are preserved.'
} catch {
    if ($transaction -and $transaction.Connection) { $transaction.Rollback() }
    throw
} finally { $connection.Dispose() }
