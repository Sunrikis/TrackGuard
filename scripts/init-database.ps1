param([string]$Server = 'localhost', [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    throw '.env already exists. Existing database settings are preserved. See README for manual configuration.'
}
Add-Type -AssemblyName System.Data
$connection = New-Object System.Data.SqlClient.SqlConnection
$builder = New-Object System.Data.SqlClient.SqlConnectionStringBuilder
$builder['Data Source'] = $Server
$builder['Initial Catalog'] = 'master'
$builder['Integrated Security'] = $true
$builder['Encrypt'] = $true
$builder['TrustServerCertificate'] = $true
$builder['Connect Timeout'] = 8
$connection.ConnectionString = $builder.ConnectionString
$random = [byte[]]::new(30)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random)
$rng.Dispose()
$password = [Convert]::ToBase64String($random) + '!a9'
try {
    $connection.Open()
    $check = $connection.CreateCommand()
    $check.CommandText = "SELECT CASE WHEN DB_ID(N'RFOID_TrackGuard') IS NOT NULL OR SUSER_ID(N'rfoid_app') IS NOT NULL THEN 1 ELSE 0 END"
    if ($check.ExecuteScalar() -eq 1) { throw 'Database or login already exists. No existing database/login has been modified; configure .env manually.' }
    $create = $connection.CreateCommand()
    $create.CommandText = "CREATE DATABASE [RFOID_TrackGuard]"
    [void]$create.ExecuteNonQuery()
    $create.CommandText = "CREATE LOGIN [rfoid_app] WITH PASSWORD=N'$password', CHECK_POLICY=ON, CHECK_EXPIRATION=OFF, DEFAULT_DATABASE=[RFOID_TrackGuard]"
    [void]$create.ExecuteNonQuery()
    $connection.ChangeDatabase('RFOID_TrackGuard')
    $schema = Get-Content -LiteralPath (Join-Path $projectRoot 'backend/src/main/resources/schema.sql') -Raw -Encoding UTF8
    foreach ($batch in ($schema -split '(?m)^GO\s*$')) {
        if ($batch.Trim()) { $create.CommandText = $batch; [void]$create.ExecuteNonQuery() }
    }
    $create.CommandText = "CREATE USER [rfoid_app] FOR LOGIN [rfoid_app]; ALTER ROLE [db_datareader] ADD MEMBER [rfoid_app]; ALTER ROLE [db_datawriter] ADD MEMBER [rfoid_app];"
    [void]$create.ExecuteNonQuery()
    $model = 'ai-core/models/trackguard-world.pt'
    $lines = @(
        '# Local development settings. Keep this file private.',
        "DB_URL=jdbc:sqlserver://${Server}:1433;databaseName=RFOID_TrackGuard;encrypt=true;trustServerCertificate=true",
        'DB_USERNAME=rfoid_app',
        "DB_PASSWORD=$password",
        "RFOID_PYTHON=$Python",
        "RFOID_MODEL=$model",
        'RFOID_WORKER=ai-core/worker.py',
        'RFOID_STORAGE=storage'
    )
    [IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Output 'Created RFOID_TrackGuard and a project-only read/write account. Settings saved privately to .env.'
} finally { $connection.Dispose() }
