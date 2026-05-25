#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Build,
    [int]$BackendPort = 8081,
    [int]$FrontendPort = 5173,
    [int]$MailerPort = 8788,
    [switch]$SkipMailer
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Root 'logs'
$TargetJar = Join-Path $Root 'target\vlugboek-0.0.1-SNAPSHOT.jar'
$NpmCli = 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js'

New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Test-ListeningPort {
    param([int]$Port)
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $connection) {
        return $true
    }

    $escapedPort = [regex]::Escape([string]$Port)
    return $null -ne ((netstat -ano 2>$null) | Select-String -Pattern "^\s*TCP\s+\S+:$escapedPort\s+\S+\s+LISTENING\s+\d+" | Select-Object -First 1)
}

function Start-BatchDetached {
    param(
        [string]$Name,
        [string]$BatchFile,
        [string]$PidFile,
        [hashtable]$ExtraEnvironment
    )

    $wrapperName = (($Name -replace '[^A-Za-z0-9.-]+', '-').Trim('-').ToLowerInvariant()) + '-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
    $wrapperFile = Join-Path $Logs "$wrapperName.cmd"
    $wrapperLines = @('@echo off')
    if ($ExtraEnvironment) {
        foreach ($key in $ExtraEnvironment.Keys) {
            $value = [string]$ExtraEnvironment[$key]
            $wrapperLines += "set `"$key=$value`""
        }
    }
    $wrapperLines += "call `"$BatchFile`""
    Set-Content -LiteralPath $wrapperFile -Value $wrapperLines -Encoding ASCII

    $process = Start-Process -FilePath $wrapperFile -WorkingDirectory $Root -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ASCII
    Write-Host "$Name started. PID $($process.Id)"
}

if ($Build -or -not (Test-Path -LiteralPath $TargetJar)) {
    Write-Host 'Packaging backend jar...'
    Push-Location $Root
    try {
        & mvn package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $NpmCli)) {
    throw "Cannot find npm CLI at $NpmCli"
}

if (-not $SkipMailer) {
    if (Test-ListeningPort $MailerPort) {
        Write-Host "Mailer port $MailerPort is already listening. Skipping mailer start."
    } else {
        Start-BatchDetached -Name 'Vlugboek mailer' -BatchFile (Join-Path $PSScriptRoot 'run-emailer.cmd') -PidFile (Join-Path $Logs 'emailer.pid') -ExtraEnvironment @{ VLUGBOEK_MAILER_PORT = $MailerPort; HOST = '127.0.0.1' }
    }
}

if (Test-ListeningPort $BackendPort) {
    Write-Host "Backend port $BackendPort is already listening. Skipping backend start."
} else {
    Start-BatchDetached -Name 'Vlugboek backend' -BatchFile (Join-Path $PSScriptRoot 'run-backend.cmd') -PidFile (Join-Path $Logs 'backend.pid') -ExtraEnvironment @{ VLUGBOEK_MAILER_URL = "http://127.0.0.1:$MailerPort/send-document" }
}

if (Test-ListeningPort $FrontendPort) {
    Write-Host "Frontend port $FrontendPort is already listening. Skipping frontend start."
} else {
    Start-BatchDetached -Name 'Vlugboek frontend' -BatchFile (Join-Path $PSScriptRoot 'run-frontend.cmd') -PidFile (Join-Path $Logs 'frontend.pid')
}

Write-Host ''
Write-Host "Backend:  http://127.0.0.1:$BackendPort/api/healthz"
if (-not $SkipMailer) {
    Write-Host "Mailer:   http://127.0.0.1:$MailerPort/health"
}
Write-Host "Frontend: http://127.0.0.1:$FrontendPort"
Write-Host "Logs:     $Logs"
