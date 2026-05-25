#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$AlsoStopPorts,
    [int[]]$Ports = @(8081, 5173, 8788)
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Root 'logs'

function Stop-ProcessTree {
    param([int]$ProcessId)
    $existing = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $existing) {
        return
    }
    & taskkill /PID $ProcessId /T /F | Out-Host
}

foreach ($pidFile in @('backend.pid', 'frontend.pid', 'emailer.pid')) {
    $path = Join-Path $Logs $pidFile
    if (Test-Path -LiteralPath $path) {
        $pidValue = (Get-Content -LiteralPath $path -Raw).Trim()
        if ($pidValue -match '^\d+$') {
            Stop-ProcessTree -ProcessId ([int]$pidValue)
        }
        Remove-Item -LiteralPath $path -Force
    }
}

if ($AlsoStopPorts) {
    foreach ($port in $Ports) {
        $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($owner in $owners) {
            Stop-ProcessTree -ProcessId ([int]$owner)
        }
    }
}

Write-Host 'Vlugboek local processes stopped.'
