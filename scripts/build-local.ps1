#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$WithTests,
    [switch]$Clean,
    [switch]$InstallFrontendDependencies,
    [switch]$StopBackend,
    [int]$BackendPort = 8081,
    [string]$ApiUrl = ''
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$FrontendRoot = Join-Path $Root 'frontend'
$JarPath = Join-Path $Root 'target\vlugboek-0.0.1-SNAPSHOT.jar'
$FrontendDist = Join-Path $FrontendRoot 'dist'
$NpmCli = 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js'

if ($BackendOnly -and $FrontendOnly) {
    throw 'Use either -BackendOnly or -FrontendOnly, not both.'
}

function Test-ListeningPort {
    param([int]$Port)

    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $connection) {
        return $true
    }

    $escapedPort = [regex]::Escape([string]$Port)
    return $null -ne ((netstat -ano 2>$null) | Select-String -Pattern "^\s*TCP\s+\S+:$escapedPort\s+\S+\s+LISTENING\s+\d+" | Select-Object -First 1)
}

function Get-PortOwners {
    param([int]$Port)

    $owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -gt 0) {
        return $owners
    }

    $escapedPort = [regex]::Escape([string]$Port)
    return @((netstat -ano 2>$null) |
        Select-String -Pattern "^\s*TCP\s+\S+:$escapedPort\s+\S+\s+LISTENING\s+(\d+)" |
        ForEach-Object { [int]$_.Matches[0].Groups[1].Value } |
        Select-Object -Unique)
}

function Stop-ProcessTree {
    param([int]$ProcessId)

    $existing = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $existing) {
        return
    }
    & taskkill /PID $ProcessId /T /F | Out-Host
}

function Stop-LocalBackend {
    $logs = Join-Path $Root 'logs'
    $backendPidFile = Join-Path $logs 'backend.pid'

    if (Test-Path -LiteralPath $backendPidFile) {
        $pidValue = (Get-Content -LiteralPath $backendPidFile -Raw).Trim()
        if ($pidValue -match '^\d+$') {
            Write-Host "Stopping backend PID $pidValue..."
            Stop-ProcessTree -ProcessId ([int]$pidValue)
        }
        Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
    }

    foreach ($owner in Get-PortOwners -Port $BackendPort) {
        Write-Host "Stopping backend port owner PID $owner..."
        Stop-ProcessTree -ProcessId ([int]$owner)
    }
}

function Invoke-Npm {
    param([string[]]$Arguments)

    if (Test-Path -LiteralPath $NpmCli) {
        & node $NpmCli @Arguments
    } else {
        & npm @Arguments
    }

    if ($LASTEXITCODE -ne 0) {
        throw "npm $($Arguments -join ' ') failed."
    }
}

function Build-Backend {
    if ($StopBackend) {
        Stop-LocalBackend
    } elseif (Test-ListeningPort -Port $BackendPort) {
        Write-Warning "Backend port $BackendPort is listening. If Maven cannot replace the jar, stop the backend or rerun with -StopBackend."
    }

    $mavenArgs = @()
    if ($Clean) {
        $mavenArgs += 'clean'
    }
    $mavenArgs += 'package'
    if (-not $WithTests) {
        $mavenArgs += '-DskipTests'
    }

    Write-Host "Building backend: mvn $($mavenArgs -join ' ')"
    Push-Location $Root
    try {
        & mvn @mavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw 'Maven package failed.'
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $JarPath)) {
        throw "Backend jar not found at $JarPath"
    }
    Write-Host "Backend jar: $JarPath"
}

function Build-Frontend {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Node.js was not found on PATH.'
    }
    if (-not (Test-Path -LiteralPath $NpmCli) -and -not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw 'npm was not found. Install Node.js or add npm to PATH.'
    }

    Push-Location $FrontendRoot
    try {
        if ($InstallFrontendDependencies -or -not (Test-Path -LiteralPath (Join-Path $FrontendRoot 'node_modules'))) {
            if (Test-Path -LiteralPath (Join-Path $FrontendRoot 'package-lock.json')) {
                Write-Host 'Installing frontend dependencies: npm ci'
                Invoke-Npm @('ci')
            } else {
                Write-Host 'Installing frontend dependencies: npm install'
                Invoke-Npm @('install')
            }
        }

        $oldApiUrl = $env:VITE_API_URL
        if ($ApiUrl) {
            $env:VITE_API_URL = $ApiUrl
        }

        try {
            Write-Host 'Building frontend: npm run build'
            Invoke-Npm @('run', 'build')
        } finally {
            if ($null -eq $oldApiUrl) {
                Remove-Item Env:\VITE_API_URL -ErrorAction SilentlyContinue
            } else {
                $env:VITE_API_URL = $oldApiUrl
            }
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $FrontendDist)) {
        throw "Frontend dist not found at $FrontendDist"
    }
    Write-Host "Frontend dist: $FrontendDist"
}

$buildBackend = -not $FrontendOnly
$buildFrontend = -not $BackendOnly

if ($buildBackend -and $PSCmdlet.ShouldProcess($Root, 'Build local backend jar')) {
    Build-Backend
}

if ($buildFrontend -and $PSCmdlet.ShouldProcess($FrontendRoot, 'Build local frontend assets')) {
    Build-Frontend
}

Write-Host ''
if ($WhatIfPreference) {
    Write-Host 'Local build dry-run complete.'
} else {
    Write-Host 'Local build complete.'
}
if ($buildBackend) {
    Write-Host "Backend jar:  $JarPath"
}
if ($buildFrontend) {
    Write-Host "Frontend dist: $FrontendDist"
}
