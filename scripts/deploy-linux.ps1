#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$RemoteRoot = '/opt/vlugboek',
    [string]$Domain = 'vlugboek.co.za',
    [string]$PublicUrl = '',
    [int]$BackendPort = 18081,
    [string]$SpringProfilesActive = '',
    [string]$DatabaseUrl = '',
    [string]$DatabaseUser = '',
    [string]$DatabasePassword = '',
    [bool]$SeedReferenceData = $true,
    [bool]$SeedPdfImport = $true,
    [bool]$SeedAdmin = $true,
    [string]$SeedAdminEmail = 'admin@vlugboek.local',
    [string]$SeedAdminName = 'Admin',
    [string]$SeedAdminPassword = 'admin123',
    [bool]$SeedDemoUsers = $true,
    [string]$SeedDemoEmail = 'demo@vlugboek.local',
    [string]$SeedDemoName = 'Demo Fancier',
    [string]$SeedDemoPassword = 'demo123',
    [bool]$AuthenticatedHealthCheck = $true,
    [string]$HealthLoginEmail = 'admin@vlugboek.local',
    [string]$HealthLoginPassword = 'admin123',
    [bool]$AdminHealthCheck = $false,
    [string]$HealthAdminEmail = 'admin@vlugboek.local',
    [string]$HealthAdminPassword = 'admin123',
    [bool]$RollbackOnFailure = $true,
    [string]$BundlePath,
    [switch]$SkipBuild,
    [switch]$SkipPublicHealthCheck
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Target = "$User@$Server"
$Installer = Join-Path $PSScriptRoot 'linux\install-or-update.sh'

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

function ConvertTo-ShellLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "'`"'`"'") + "'"
}

function ConvertTo-LinuxBool {
    param([bool]$Value)
    if ($Value) { return 'true' }
    return 'false'
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

if (-not $BundlePath) {
    $buildOutput = & (Join-Path $PSScriptRoot 'build-release.ps1') -SkipBuild:$SkipBuild
    $BundlePath = ($buildOutput | Select-Object -Last 1).ToString()
}
$BundlePath = (Resolve-Path -LiteralPath $BundlePath).Path
$BundleName = Split-Path -Leaf $BundlePath
$ReleaseName = [System.IO.Path]::GetFileNameWithoutExtension([System.IO.Path]::GetFileNameWithoutExtension($BundleName))
$RemoteIncoming = "$RemoteRoot/incoming"
$RemoteBundle = "$RemoteIncoming/$BundleName"
$RemoteInstaller = "$RemoteIncoming/install-or-update.sh"

Write-Host "Creating remote incoming directory on $Target..."
Invoke-External ssh @($Target, "mkdir -p '$RemoteIncoming'")

Write-Host "Uploading bundle..."
Invoke-External scp @($BundlePath, "${Target}:$RemoteBundle")

Write-Host "Uploading remote installer..."
Invoke-External scp @($Installer, "${Target}:$RemoteInstaller")

Write-Host 'Running remote install/update...'
$effectivePublicUrl = if ($PublicUrl) { $PublicUrl } else { "https://$Domain" }
$remoteEnv = [System.Collections.Generic.List[string]]::new()
$remoteEnv.Add("APP_ROOT=$(ConvertTo-ShellLiteral $RemoteRoot)")
$remoteEnv.Add("DOMAIN=$(ConvertTo-ShellLiteral $Domain)")
$remoteEnv.Add("VLUGBOEK_PUBLIC_URL=$(ConvertTo-ShellLiteral $effectivePublicUrl)")
$remoteEnv.Add("BACKEND_PORT=$(ConvertTo-ShellLiteral ([string]$BackendPort))")
$remoteEnv.Add("VLUGBOEK_SEED_REFERENCE_DATA_ENABLED=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $SeedReferenceData))")
$remoteEnv.Add("VLUGBOEK_SEED_PDF_IMPORT_ENABLED=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $SeedPdfImport))")
$remoteEnv.Add("VLUGBOEK_SEED_ADMIN_ENABLED=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $SeedAdmin))")
$remoteEnv.Add("VLUGBOEK_SEED_ADMIN_EMAIL=$(ConvertTo-ShellLiteral $SeedAdminEmail)")
$remoteEnv.Add("VLUGBOEK_SEED_ADMIN_NAME=$(ConvertTo-ShellLiteral $SeedAdminName)")
$remoteEnv.Add("VLUGBOEK_SEED_ADMIN_PASSWORD=$(ConvertTo-ShellLiteral $SeedAdminPassword)")
$remoteEnv.Add("VLUGBOEK_SEED_DEMO_USERS_ENABLED=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $SeedDemoUsers))")
$remoteEnv.Add("VLUGBOEK_SEED_DEMO_EMAIL=$(ConvertTo-ShellLiteral $SeedDemoEmail)")
$remoteEnv.Add("VLUGBOEK_SEED_DEMO_NAME=$(ConvertTo-ShellLiteral $SeedDemoName)")
$remoteEnv.Add("VLUGBOEK_SEED_DEMO_PASSWORD=$(ConvertTo-ShellLiteral $SeedDemoPassword)")
$remoteEnv.Add("AUTH_HEALTH_CHECK=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $AuthenticatedHealthCheck))")
$remoteEnv.Add("HEALTH_LOGIN_EMAIL=$(ConvertTo-ShellLiteral $HealthLoginEmail)")
$remoteEnv.Add("HEALTH_LOGIN_PASSWORD=$(ConvertTo-ShellLiteral $HealthLoginPassword)")
$remoteEnv.Add("ADMIN_HEALTH_CHECK=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $AdminHealthCheck))")
$remoteEnv.Add("HEALTH_ADMIN_EMAIL=$(ConvertTo-ShellLiteral $HealthAdminEmail)")
$remoteEnv.Add("HEALTH_ADMIN_PASSWORD=$(ConvertTo-ShellLiteral $HealthAdminPassword)")
$remoteEnv.Add("ROLLBACK_ON_FAILURE=$(ConvertTo-ShellLiteral (ConvertTo-LinuxBool $RollbackOnFailure))")
if ($SpringProfilesActive) { $remoteEnv.Add("SPRING_PROFILES_ACTIVE=$(ConvertTo-ShellLiteral $SpringProfilesActive)") }
if ($DatabaseUrl) { $remoteEnv.Add("VLUGBOEK_DB_URL=$(ConvertTo-ShellLiteral $DatabaseUrl)") }
if ($DatabaseUser) { $remoteEnv.Add("VLUGBOEK_DB_USER=$(ConvertTo-ShellLiteral $DatabaseUser)") }
if ($DatabasePassword) { $remoteEnv.Add("VLUGBOEK_DB_PASSWORD=$(ConvertTo-ShellLiteral $DatabasePassword)") }

$remoteCommand = "chmod +x $(ConvertTo-ShellLiteral $RemoteInstaller) && $($remoteEnv -join ' ') bash $(ConvertTo-ShellLiteral $RemoteInstaller) $(ConvertTo-ShellLiteral $RemoteBundle) $(ConvertTo-ShellLiteral $ReleaseName)"
Invoke-External ssh @($Target, $remoteCommand)

if (-not $SkipPublicHealthCheck) {
    Write-Host 'Checking public endpoints...'
    Invoke-WebRequest -Uri "https://$Domain/healthz" -UseBasicParsing -TimeoutSec 20 | Out-Null
    Invoke-WebRequest -Uri "https://$Domain/api/healthz" -UseBasicParsing -TimeoutSec 20 | Out-Null
}

Write-Host "Deployment complete: https://$Domain"
