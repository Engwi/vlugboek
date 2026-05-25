#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApiUrl = 'https://vlugboek.co.za',
    [string]$VersionName,
    [int]$VersionCode = 0,
    [switch]$SkipWebBuild,
    [switch]$SkipSync,
    [switch]$SkipSmokeCheck
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Frontend = Join-Path $Root 'frontend'
$AndroidDir = Join-Path $Frontend 'android'
$Gradle = Join-Path $AndroidDir 'gradlew.bat'
$OutputDir = Join-Path $Root 'dist\mobile'
$MobileCommon = Join-Path $PSScriptRoot 'mobile-build-common.ps1'
if (-not (Test-Path -LiteralPath $MobileCommon)) {
    throw "Mobile build helpers not found at $MobileCommon"
}
. $MobileCommon

function Resolve-AndroidSdk {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $candidate) {
        $env:ANDROID_HOME = $candidate
        return $candidate
    }
    return $null
}

function Invoke-Gradle {
    param([string[]]$Arguments)
    Push-Location $AndroidDir
    try {
        & $Gradle @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$resolvedVersion = Resolve-MobileVersion -Root $Root -VersionName $VersionName -VersionCode $VersionCode
$VersionName = $resolvedVersion.VersionName
$VersionCode = $resolvedVersion.VersionCode

if (-not $SkipSync) {
    $syncParams = @{
        ApiUrl = $ApiUrl
        BuildType = 'debug'
        VersionName = $VersionName
        VersionCode = $VersionCode
        SkipWebBuild = $SkipWebBuild
        SkipSmokeCheck = $SkipSmokeCheck
    }
    & (Join-Path $PSScriptRoot 'sync-android.ps1') @syncParams
    if ($LASTEXITCODE -ne 0) { throw 'Android sync failed.' }
}

if (-not (Resolve-AndroidSdk)) {
    throw 'Android SDK not found. Install Android Studio, then set ANDROID_HOME or ANDROID_SDK_ROOT.'
}
if (-not (Test-Path -LiteralPath $Gradle)) {
    throw "Gradle wrapper not found at $Gradle. Run .\scripts\sync-android.ps1 first."
}

Write-Host 'Building debug APK...'
Invoke-Gradle -Arguments @(
    'assembleDebug',
    "-Pvlugboek.versionName=$VersionName",
    "-Pvlugboek.versionCode=$VersionCode"
)

$apk = Join-Path $AndroidDir 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Debug APK not found at $apk"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$destination = Join-Path $OutputDir 'vlugboek-debug.apk'
$versionedName = "vlugboek-debug-$(ConvertTo-MobileFileToken $VersionName)-$VersionCode.apk"
$versionedDestination = Join-Path $OutputDir $versionedName
Copy-Item -LiteralPath $apk -Destination $destination -Force
Copy-Item -LiteralPath $apk -Destination $versionedDestination -Force
Write-MobileApkMetadata -Root $Root -ApkPath $destination -BuildType 'debug' -ApiUrl $ApiUrl -VersionName $VersionName -VersionCode $VersionCode -Signed $false | Out-Null
Write-MobileApkMetadata -Root $Root -ApkPath $versionedDestination -BuildType 'debug' -ApiUrl $ApiUrl -VersionName $VersionName -VersionCode $VersionCode -Signed $false | Out-Null
Write-Host "Debug APK created: $versionedDestination"
Write-Host "Stable debug APK updated: $destination"
Write-Output $versionedDestination
