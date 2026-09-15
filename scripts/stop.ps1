$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$statePath = Join-Path $projectRoot 'storage/processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No saved project processes.'; return }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.project -ne $projectRoot) { throw 'Process record belongs to a different project.' }
foreach ($key in @('frontend','backend')) {
    $taskProcessId = [int]$state.$key
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$taskProcessId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($projectRoot)) {
        if ($key -eq 'backend') {
            $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$taskProcessId" -ErrorAction SilentlyContinue
            foreach ($child in $children) {
                if ($child.CommandLine -and $child.CommandLine.Contains((Join-Path $projectRoot 'ai-core'))) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
            }
        }
        Stop-Process -Id $taskProcessId -ErrorAction SilentlyContinue
    }
}
Remove-Item -LiteralPath $statePath -Force
Write-Output 'Project processes stopped; unrelated processes were left intact.'
