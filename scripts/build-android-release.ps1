#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApiUrl = 'https://vlugboek.co.za',
    [string]$VersionName,
    [int]$VersionCode = 0,
    [switch]$SkipWebBuild,
    [switch]$SkipSync,
    [switch]$SkipSmokeCheck,
    [string]$KeystorePath = $env:VLUGBOEK_ANDROID_KEYSTORE,
    [string]$KeyAlias = $env:VLUGBOEK_ANDROID_KEY_ALIAS,
    [string]$KeystorePassword = $env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD,
    [string]$KeyPassword = $env:VLUGBOEK_ANDROID_KEY_PASSWORD
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

function ConvertFrom-SecureStringForProcess {
    param([securestring]$Value)

    if (-not $Value) { return $null }

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Resolve-MobileSigning {
    param(
        [string]$Path,
        [string]$Alias,
        [string]$StorePassword,
        [string]$SigningPassword
    )

    $localProfile = Join-Path $PSScriptRoot 'mobile-signing.local.ps1'
    if (Test-Path -LiteralPath $localProfile) {
        . $localProfile
    }

    $secretFile = Join-Path $PSScriptRoot '.secrets\mobile-signing.clixml'
    if (Test-Path -LiteralPath $secretFile) {
        $secret = Import-Clixml -LiteralPath $secretFile
        if (-not $Path) { $Path = $secret.KeystorePath }
        if (-not $Alias) { $Alias = $secret.KeyAlias }
        if (-not $StorePassword) { $StorePassword = ConvertFrom-SecureStringForProcess $secret.KeystorePassword }
        if (-not $SigningPassword) { $SigningPassword = ConvertFrom-SecureStringForProcess $secret.KeyPassword }
    }

    if (-not $Path) { $Path = $env:VLUGBOEK_ANDROID_KEYSTORE }
    if (-not $Alias) { $Alias = $env:VLUGBOEK_ANDROID_KEY_ALIAS }
    if (-not $StorePassword) { $StorePassword = $env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD }
    if (-not $SigningPassword) { $SigningPassword = $env:VLUGBOEK_ANDROID_KEY_PASSWORD }

    [pscustomobject]@{
        KeystorePath = $Path
        KeyAlias = $Alias
        KeystorePassword = $StorePassword
        KeyPassword = $SigningPassword
    }
}

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

function Get-KeytoolPath {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }

    return $null
}

function ConvertTo-CommandLineArgument {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value -or $Value.Length -eq 0) { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }

    $result = '"'
    $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') {
            $backslashes++
            continue
        }

        if ($ch -eq '"') {
            $result += ('\' * (($backslashes * 2) + 1))
            $result += '"'
            $backslashes = 0
            continue
        }

        if ($backslashes -gt 0) {
            $result += ('\' * $backslashes)
            $backslashes = 0
        }
        $result += $ch
    }

    if ($backslashes -gt 0) {
        $result += ('\' * ($backslashes * 2))
    }
    $result += '"'
    return $result
}

function Get-KeystoreAliases {
    param(
        [string]$Path,
        [string]$StorePassword
    )

    $keytool = Get-KeytoolPath
    if (-not $keytool) {
        Write-Warning 'keytool was not found; skipping keystore alias validation.'
        return @()
    }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $keytool
    $psi.Arguments = (@('-list', '-v', '-keystore', $Path) | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $process.StandardInput.WriteLine($StorePassword)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Could not read keystore aliases from $Path. keytool exit code $($process.ExitCode): $stderr`n$stdout"
    }

    $aliases = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($stdout -split "`r?`n")) {
        if ($line -match '^Alias name:\s*(.+)$') {
            $aliases.Add($matches[1].Trim())
        }
    }

    return $aliases.ToArray()
}

function Resolve-KeyAlias {
    param(
        [string]$Path,
        [string]$Alias,
        [string]$StorePassword
    )

    $aliases = Get-KeystoreAliases -Path $Path -StorePassword $StorePassword
    if ($aliases.Count -eq 0 -or $aliases -contains $Alias) {
        return $Alias
    }

    if ($aliases.Count -eq 1) {
        Write-Warning "Configured key alias '$Alias' was not found. Using the only alias in the keystore: '$($aliases[0])'."
        return $aliases[0]
    }

    throw "Configured key alias '$Alias' was not found in $Path. Available aliases: $($aliases -join ', '). Re-save the signing secret with save-mobile-signing-secret.ps1 -KeyAlias <alias>."
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

$signing = Resolve-MobileSigning -Path $KeystorePath -Alias $KeyAlias -StorePassword $KeystorePassword -SigningPassword $KeyPassword
$KeystorePath = $signing.KeystorePath
$KeyAlias = $signing.KeyAlias
$KeystorePassword = $signing.KeystorePassword
$KeyPassword = $signing.KeyPassword

$resolvedVersion = Resolve-MobileVersion -Root $Root -VersionName $VersionName -VersionCode $VersionCode
$VersionName = $resolvedVersion.VersionName
$VersionCode = $resolvedVersion.VersionCode

if (-not $SkipSync) {
    $syncParams = @{
        ApiUrl = $ApiUrl
        BuildType = 'release'
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

$gradleArgs = @(
    'assembleRelease',
    "-Pvlugboek.versionName=$VersionName",
    "-Pvlugboek.versionCode=$VersionCode"
)
$signed = $false
if ($KeystorePath -or $KeyAlias -or $KeystorePassword -or $KeyPassword) {
    if (-not ($KeystorePath -and $KeyAlias -and $KeystorePassword -and $KeyPassword)) {
        throw 'Release signing needs KeystorePath, KeyAlias, KeystorePassword, and KeyPassword. You can pass parameters or set VLUGBOEK_ANDROID_* environment variables.'
    }
    $resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
    $KeyAlias = Resolve-KeyAlias -Path $resolvedKeystore -Alias $KeyAlias -StorePassword $KeystorePassword
    $gradleArgs += @(
        "-Pandroid.injected.signing.store.file=$resolvedKeystore",
        "-Pandroid.injected.signing.store.password=$KeystorePassword",
        "-Pandroid.injected.signing.key.alias=$KeyAlias",
        "-Pandroid.injected.signing.key.password=$KeyPassword"
    )
    $signed = $true
}

Write-Host $(if ($signed) { 'Building signed release APK...' } else { 'Building unsigned release APK...' })
Invoke-Gradle -Arguments $gradleArgs

$releaseDir = Join-Path $AndroidDir 'app\build\outputs\apk\release'
$apk = if ($signed) {
    Join-Path $releaseDir 'app-release.apk'
} else {
    Join-Path $releaseDir 'app-release-unsigned.apk'
}
if (-not (Test-Path -LiteralPath $apk)) {
    $apk = Get-ChildItem -LiteralPath $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $apk -or -not (Test-Path -LiteralPath $apk)) {
    throw "Release APK not found in $releaseDir"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$destination = Join-Path $OutputDir ($(if ($signed) { 'vlugboek-release.apk' } else { 'vlugboek-release-unsigned.apk' }))
$versionedName = "vlugboek-$(if ($signed) { 'release' } else { 'release-unsigned' })-$(ConvertTo-MobileFileToken $VersionName)-$VersionCode.apk"
$versionedDestination = Join-Path $OutputDir $versionedName
Copy-Item -LiteralPath $apk -Destination $destination -Force
Copy-Item -LiteralPath $apk -Destination $versionedDestination -Force
Write-MobileApkMetadata -Root $Root -ApkPath $destination -BuildType 'release' -ApiUrl $ApiUrl -VersionName $VersionName -VersionCode $VersionCode -Signed $signed | Out-Null
Write-MobileApkMetadata -Root $Root -ApkPath $versionedDestination -BuildType 'release' -ApiUrl $ApiUrl -VersionName $VersionName -VersionCode $VersionCode -Signed $signed | Out-Null
Write-Host "Release APK created: $versionedDestination"
Write-Host "Stable release APK updated: $destination"
Write-Output $versionedDestination
