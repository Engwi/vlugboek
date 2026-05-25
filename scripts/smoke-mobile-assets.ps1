#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApiUrl = '',
    [string]$BuildType = '',
    [string]$VersionName = '',
    [int]$VersionCode = 0,
    [string]$AndroidAssetsDir
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if (-not $AndroidAssetsDir) {
    $AndroidAssetsDir = Join-Path $Root 'frontend\android\app\src\main\assets\public'
}
$CapacitorConfigPath = Join-Path $Root 'frontend\android\app\src\main\assets\capacitor.config.json'

function Assert-File {
    param(
        [string]$Path,
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Label at $Path"
    }
}

function Assert-Equals {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Label
    )

    if ($null -eq $Expected -or [string]$Expected -eq '') {
        return
    }
    if ([string]$Actual -ne [string]$Expected) {
        throw "$Label mismatch. Expected '$Expected', got '$Actual'."
    }
}

Assert-File -Path $AndroidAssetsDir -Label 'Android web assets directory'
Assert-File -Path (Join-Path $AndroidAssetsDir 'index.html') -Label 'mobile index.html'
Assert-File -Path (Join-Path $AndroidAssetsDir 'site.webmanifest') -Label 'PWA manifest'
Assert-File -Path (Join-Path $AndroidAssetsDir 'android-chrome-192x192.png') -Label '192px launcher web icon'
Assert-File -Path (Join-Path $AndroidAssetsDir 'android-chrome-512x512.png') -Label '512px launcher web icon'
Assert-File -Path $CapacitorConfigPath -Label 'Capacitor asset config'

$index = Get-Content -LiteralPath (Join-Path $AndroidAssetsDir 'index.html') -Raw
$assetMatches = [regex]::Matches($index, '(?:src|href)="(/assets/[^"]+\.(?:js|css))"')
if ($assetMatches.Count -eq 0) {
    throw 'Mobile index.html does not reference packaged JS/CSS assets.'
}
foreach ($match in $assetMatches) {
    $relative = $match.Groups[1].Value.TrimStart('/') -replace '/', '\'
    Assert-File -Path (Join-Path $AndroidAssetsDir $relative) -Label "referenced asset $relative"
}

$mobileBuildPath = Join-Path $AndroidAssetsDir 'mobile-build.json'
Assert-File -Path $mobileBuildPath -Label 'mobile build metadata'
$mobileBuild = Get-Content -LiteralPath $mobileBuildPath -Raw | ConvertFrom-Json
Assert-Equals -Actual $mobileBuild.appId -Expected 'za.co.vlugboek.app' -Label 'App id'
Assert-Equals -Actual $mobileBuild.apiUrl -Expected $ApiUrl -Label 'API URL'
Assert-Equals -Actual $mobileBuild.buildType -Expected $BuildType -Label 'Build type'
Assert-Equals -Actual $mobileBuild.versionName -Expected $VersionName -Label 'Version name'
if ($VersionCode -gt 0 -and [int]$mobileBuild.versionCode -ne $VersionCode) {
    throw "Version code mismatch. Expected '$VersionCode', got '$($mobileBuild.versionCode)'."
}

$capacitorConfig = Get-Content -LiteralPath $CapacitorConfigPath -Raw | ConvertFrom-Json
Assert-Equals -Actual $capacitorConfig.appId -Expected 'za.co.vlugboek.app' -Label 'Capacitor app id'
Assert-Equals -Actual $capacitorConfig.appName -Expected 'Vlugboek' -Label 'Capacitor app name'

if ($ApiUrl) {
    $jsFiles = Get-ChildItem -LiteralPath (Join-Path $AndroidAssetsDir 'assets') -Filter '*.js' -ErrorAction SilentlyContinue
    $apiUrlFound = $false
    foreach ($js in $jsFiles) {
        if ((Get-Content -LiteralPath $js.FullName -Raw) -like "*$ApiUrl*") {
            $apiUrlFound = $true
            break
        }
    }
    if (-not $apiUrlFound) {
        throw "API URL '$ApiUrl' was not found in packaged mobile JS assets."
    }
}

Write-Host "Mobile asset smoke check passed: $AndroidAssetsDir"
