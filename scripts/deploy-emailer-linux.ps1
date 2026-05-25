#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$RemoteRoot = '/opt/vlugboekmailer',
    [string]$ServiceName = 'vlugboekmailer',
    [string]$EnvPath,
    [string]$BundlePath,
    [int]$ExpectedPort = 8788
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$MailerRoot = Join-Path $Root 'emailer'
$StageRoot = Join-Path $Root 'dist\emailer'
$ReleaseName = 'vlugboekmailer-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$ReleaseDir = Join-Path $StageRoot $ReleaseName
$Installer = Join-Path $PSScriptRoot 'linux\install-emailer.sh'
$Target = "$User@$Server"

function Invoke-External {
    param(
        [string]$File,
        [string[]]$Arguments
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File failed with exit code $LASTEXITCODE"
    }
}

function Read-DotEnv {
    param([string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) {
            continue
        }

        $parts = $trimmed -split '=', 2
        $values[$parts[0].Trim()] = $parts[1]
    }

    return $values
}

if (-not $EnvPath) {
    $EnvPath = Join-Path $MailerRoot '.env'
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw 'OpenSSH ssh was not found on PATH.'
}
if (-not (Get-Command scp -ErrorAction SilentlyContinue)) {
    throw 'OpenSSH scp was not found on PATH.'
}
if (-not (Test-Path -LiteralPath $Installer)) {
    throw "Remote installer not found at $Installer"
}
if (-not (Test-Path -LiteralPath $EnvPath)) {
    throw "Mailer env file not found at $EnvPath. Create it from emailer\.env.example."
}

$envValues = Read-DotEnv -Path $EnvPath
$configuredPort = if ($envValues.ContainsKey('PORT') -and -not [string]::IsNullOrWhiteSpace($envValues['PORT'])) {
    $envValues['PORT'].Trim()
} else {
    '8788'
}
if ($configuredPort -ne [string]$ExpectedPort) {
    throw "Mailer env PORT is $configuredPort, but Vlugboek expects $ExpectedPort. Update $EnvPath or pass -ExpectedPort."
}

foreach ($requiredKey in @('MAIL_WEBHOOK_TOKEN')) {
    if (-not $envValues.ContainsKey($requiredKey) -or [string]::IsNullOrWhiteSpace($envValues[$requiredKey]) -or $envValues[$requiredKey] -like 'replace-with-*') {
        throw "Mailer env is missing a real $requiredKey value."
    }
}

$hasSmtpUser = ($envValues.ContainsKey('SMTP_USER') -and -not [string]::IsNullOrWhiteSpace($envValues['SMTP_USER']) -and $envValues['SMTP_USER'] -notlike 'replace-with-*') -or
    ($envValues.ContainsKey('GMAIL_USER') -and -not [string]::IsNullOrWhiteSpace($envValues['GMAIL_USER']) -and $envValues['GMAIL_USER'] -notlike 'replace-with-*')
$hasSmtpPassword = ($envValues.ContainsKey('SMTP_PASSWORD') -and -not [string]::IsNullOrWhiteSpace($envValues['SMTP_PASSWORD']) -and $envValues['SMTP_PASSWORD'] -notlike 'replace-with-*') -or
    ($envValues.ContainsKey('GMAIL_APP_PASSWORD') -and -not [string]::IsNullOrWhiteSpace($envValues['GMAIL_APP_PASSWORD']) -and $envValues['GMAIL_APP_PASSWORD'] -notlike 'replace-with-*')
if (-not $hasSmtpUser) {
    throw 'Mailer env needs SMTP_USER or legacy GMAIL_USER.'
}
if (-not $hasSmtpPassword) {
    throw 'Mailer env needs SMTP_PASSWORD or legacy GMAIL_APP_PASSWORD.'
}

$configuredHost = if ($envValues.ContainsKey('HOST') -and -not [string]::IsNullOrWhiteSpace($envValues['HOST'])) {
    $envValues['HOST'].Trim()
} else {
    '127.0.0.1'
}
if ($configuredHost -notin @('127.0.0.1', 'localhost')) {
    throw "Mailer HOST must stay private on 127.0.0.1 or localhost. Current value: $configuredHost"
}

if (-not $BundlePath) {
    if (Test-Path -LiteralPath $ReleaseDir) {
        Remove-Item -LiteralPath $ReleaseDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
    foreach ($name in @('server.mjs', 'package.json', 'package-lock.json', 'README.md', '.env.example')) {
        Copy-Item -LiteralPath (Join-Path $MailerRoot $name) -Destination (Join-Path $ReleaseDir $name) -Force
    }

    New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null
    $BundlePath = Join-Path $StageRoot "$ReleaseName.tar.gz"
    if (Test-Path -LiteralPath $BundlePath) {
        Remove-Item -LiteralPath $BundlePath -Force
    }

    Push-Location $StageRoot
    try {
        Invoke-External tar @('-czf', $BundlePath, $ReleaseName)
    } finally {
        Pop-Location
    }

    Remove-Item -LiteralPath $ReleaseDir -Recurse -Force
}

$BundlePath = (Resolve-Path -LiteralPath $BundlePath).Path
$EnvPath = (Resolve-Path -LiteralPath $EnvPath).Path
$BundleName = Split-Path -Leaf $BundlePath
$ReleaseName = [System.IO.Path]::GetFileNameWithoutExtension([System.IO.Path]::GetFileNameWithoutExtension($BundleName))
$RemoteIncoming = "$RemoteRoot/incoming"
$RemoteShared = "$RemoteRoot/shared"
$RemoteBundle = "$RemoteIncoming/$BundleName"
$RemoteInstaller = "$RemoteIncoming/install-emailer.sh"
$RemoteEnv = "$RemoteShared/.env"

Write-Host "Creating remote mailer directories on $Target..."
Invoke-External ssh @($Target, "mkdir -p '$RemoteIncoming' '$RemoteShared'")

Write-Host 'Uploading mailer bundle...'
Invoke-External scp @($BundlePath, "${Target}:$RemoteBundle")

Write-Host 'Uploading mailer environment file...'
Invoke-External scp @($EnvPath, "${Target}:$RemoteEnv")

Write-Host 'Uploading mailer installer...'
Invoke-External scp @($Installer, "${Target}:$RemoteInstaller")

Write-Host 'Running remote mailer install/update...'
$remoteCommand = "chmod +x '$RemoteInstaller' && MAILER_ROOT='$RemoteRoot' SERVICE_NAME='$ServiceName' MAILER_EXPECTED_PORT='$ExpectedPort' bash '$RemoteInstaller' '$RemoteBundle' '$ReleaseName'"
Invoke-External ssh @($Target, $remoteCommand)

Write-Host "Mailer deployment complete: $ServiceName on $Server"
