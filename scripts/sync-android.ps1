#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApiUrl = 'https://vlugboek.co.za',
    [string]$BuildType = 'web',
    [string]$VersionName,
    [int]$VersionCode = 0,
    [switch]$SkipWebBuild,
    [switch]$SkipSmokeCheck
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Frontend = Join-Path $Root 'frontend'
$NpmCli = 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js'
$AndroidDir = Join-Path $Frontend 'android'
$MobileCommon = Join-Path $PSScriptRoot 'mobile-build-common.ps1'
if (-not (Test-Path -LiteralPath $MobileCommon)) {
    throw "Mobile build helpers not found at $MobileCommon"
}
. $MobileCommon

function Invoke-Tool {
    param(
        [string]$File,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $File @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$File failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $NpmCli)) {
    throw "Cannot find npm CLI at $NpmCli"
}

$resolvedVersion = Resolve-MobileVersion -Root $Root -VersionName $VersionName -VersionCode $VersionCode
$VersionName = $resolvedVersion.VersionName
$VersionCode = $resolvedVersion.VersionCode

if (-not (Test-Path -LiteralPath (Join-Path $Frontend 'node_modules\@capacitor\cli'))) {
    Write-Host 'Installing frontend dependencies...'
    Invoke-Tool -File 'node' -Arguments @($NpmCli, 'install') -WorkingDirectory $Frontend
}

if (-not $SkipWebBuild) {
    Write-Host "Building web assets for mobile API URL: $ApiUrl"
    Write-MobileWebBuildManifest -Root $Root -ApiUrl $ApiUrl -BuildType $BuildType -VersionName $VersionName -VersionCode $VersionCode | Out-Null
    $previousApiUrl = $env:VITE_API_URL
    $env:VITE_API_URL = $ApiUrl
    try {
        Invoke-Tool -File 'node' -Arguments @($NpmCli, 'run', 'build') -WorkingDirectory $Frontend
    } finally {
        if ($null -eq $previousApiUrl) {
            Remove-Item Env:VITE_API_URL -ErrorAction SilentlyContinue
        } else {
            $env:VITE_API_URL = $previousApiUrl
        }
    }
}

if (-not (Test-Path -LiteralPath $AndroidDir)) {
    Write-Host 'Adding Capacitor Android platform...'
    Invoke-Tool -File 'node' -Arguments @($NpmCli, 'exec', '--', 'cap', 'add', 'android') -WorkingDirectory $Frontend
} else {
    Write-Host 'Capacitor Android platform already exists.'
}

Write-Host 'Syncing web assets to Android...'
Invoke-Tool -File 'node' -Arguments @($NpmCli, 'exec', '--', 'cap', 'sync', 'android') -WorkingDirectory $Frontend

if (-not $SkipSmokeCheck) {
    $smokeParams = @{}
    if (-not $SkipWebBuild) {
        $smokeParams.ApiUrl = $ApiUrl
        $smokeParams.BuildType = $BuildType
        $smokeParams.VersionName = $VersionName
        $smokeParams.VersionCode = $VersionCode
    }
    & (Join-Path $PSScriptRoot 'smoke-mobile-assets.ps1') @smokeParams
    if ($LASTEXITCODE -ne 0) { throw 'Mobile asset smoke check failed.' }
}

Write-Host "Android project ready: $AndroidDir"
