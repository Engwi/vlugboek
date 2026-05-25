#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'release',
    [string]$ApkPath,
    [string]$ApkFileName,
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$RemoteRoot = '/opt/vlugboek',
    [string]$Domain = 'vlugboek.co.za',
    [string]$ApiUrl = 'https://vlugboek.co.za',
    [string]$VersionName,
    [int]$VersionCode = 0,
    [string]$PublicDownloadsPath = '/downloads',
    [switch]$SkipBuild,
    [switch]$SkipWebBuild,
    [switch]$SkipSync,
    [switch]$SkipSmokeCheck,
    [switch]$AllowUnsignedRelease,
    [switch]$SkipPublicCheck,
    [string]$KeystorePath = $env:VLUGBOEK_ANDROID_KEYSTORE,
    [string]$KeyAlias = $env:VLUGBOEK_ANDROID_KEY_ALIAS,
    [string]$KeystorePassword = $env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD,
    [string]$KeyPassword = $env:VLUGBOEK_ANDROID_KEY_PASSWORD
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Target = "$User@$Server"
$OutputDir = Join-Path $Root 'dist\mobile'
$StagingDir = Join-Path $Root 'dist\mobile-deploy'
$MobileCommon = Join-Path $PSScriptRoot 'mobile-build-common.ps1'
if (-not (Test-Path -LiteralPath $MobileCommon)) {
    throw "Mobile build helpers not found at $MobileCommon"
}
. $MobileCommon

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

function Find-ExistingApk {
    if ($BuildType -eq 'debug') {
        return Join-Path $OutputDir 'vlugboek-debug.apk'
    }

    $signed = Join-Path $OutputDir 'vlugboek-release.apk'
    if (Test-Path -LiteralPath $signed) {
        return $signed
    }

    return Join-Path $OutputDir 'vlugboek-release-unsigned.apk'
}

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
        try {
            $secret = Import-Clixml -LiteralPath $secretFile
            if (-not $Path) { $Path = $secret.KeystorePath }
            if (-not $Alias) { $Alias = $secret.KeyAlias }
            if (-not $StorePassword) { $StorePassword = ConvertFrom-SecureStringForProcess $secret.KeystorePassword }
            if (-not $SigningPassword) { $SigningPassword = ConvertFrom-SecureStringForProcess $secret.KeyPassword }
        } catch {
            Write-Warning "Could not read encrypted mobile signing secret at $secretFile. Falling back to parameters and environment variables."
        }
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

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw 'OpenSSH ssh was not found on PATH.'
}
if (-not (Get-Command scp -ErrorAction SilentlyContinue)) {
    throw 'OpenSSH scp was not found on PATH.'
}

$PublicDownloadsPath = '/' + $PublicDownloadsPath.Trim('/')
if ($PublicDownloadsPath -eq '/') {
    throw 'PublicDownloadsPath must not be the site root.'
}
if ($PublicDownloadsPath -notmatch '^/[A-Za-z0-9/_-]+$') {
    throw 'PublicDownloadsPath may only contain letters, numbers, slashes, underscores, and hyphens.'
}

$signing = Resolve-MobileSigning -Path $KeystorePath -Alias $KeyAlias -StorePassword $KeystorePassword -SigningPassword $KeyPassword
$KeystorePath = $signing.KeystorePath
$KeyAlias = $signing.KeyAlias
$KeystorePassword = $signing.KeystorePassword
$KeyPassword = $signing.KeyPassword

if (-not $ApkPath) {
    if ($SkipBuild) {
        $ApkPath = Find-ExistingApk
    } else {
        $buildScript = Join-Path $PSScriptRoot "build-android-$BuildType.ps1"
        if (-not (Test-Path -LiteralPath $buildScript)) {
            throw "Android build script not found at $buildScript"
        }

        $buildParams = @{
            ApiUrl = $ApiUrl
            VersionName = $VersionName
            VersionCode = $VersionCode
            SkipWebBuild = $SkipWebBuild
            SkipSync = $SkipSync
            SkipSmokeCheck = $SkipSmokeCheck
        }
        if ($BuildType -eq 'release') {
            $hasAllSigning = $KeystorePath -and $KeyAlias -and $KeystorePassword -and $KeyPassword
            if (-not $hasAllSigning -and -not $AllowUnsignedRelease) {
                throw 'Signed release deployment needs signing settings. Run save-mobile-signing-secret.ps1 -UseSamePassword once from the scripts folder, set VLUGBOEK_ANDROID_* environment variables, or pass -AllowUnsignedRelease.'
            }
            if ($hasAllSigning) {
                $buildParams.KeystorePath = $KeystorePath
                $buildParams.KeyAlias = $KeyAlias
                $buildParams.KeystorePassword = $KeystorePassword
                $buildParams.KeyPassword = $KeyPassword
            }
        }

        Write-Host "Building $BuildType APK..."
        $buildOutput = & $buildScript @buildParams
        $lastBuildLine = $buildOutput | Where-Object { $_ } | Select-Object -Last 1
        if (-not $lastBuildLine) {
            throw "$buildScript did not return an APK path."
        }
        $ApkPath = $lastBuildLine.ToString()
    }
}

$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $ApkFileName) {
    $ApkFileName = Split-Path -Leaf $ApkPath
}
if ($ApkFileName -notmatch '^[A-Za-z0-9._-]+\.apk$') {
    throw 'ApkFileName may only contain letters, numbers, dots, underscores, and hyphens, and must end in .apk.'
}

$apk = Get-Item -LiteralPath $ApkPath
$metadata = Read-MobileApkMetadata -Root $Root -ApkPath $ApkPath -BuildType $BuildType
if ($metadata) {
    if (-not $VersionName) { $VersionName = [string]$metadata.versionName }
    if ($VersionCode -le 0 -and $metadata.versionCode) { $VersionCode = [int]$metadata.versionCode }
    if ($metadata.apiUrl) { $ApiUrl = [string]$metadata.apiUrl }
} else {
    $resolvedVersion = Resolve-MobileVersion -Root $Root -VersionName $VersionName -VersionCode $VersionCode
    $VersionName = $resolvedVersion.VersionName
    $VersionCode = $resolvedVersion.VersionCode
}
$apkSizeMb = [Math]::Round($apk.Length / 1MB, 1)
$apkSha256 = Get-FileSha256 -Path $apk.FullName
$generatedAt = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$DownloadPageUrl = "https://$Domain$PublicDownloadsPath/"
$DownloadApkUrl = "https://$Domain$PublicDownloadsPath/$ApkFileName"
$DownloadMetadataUrl = "https://$Domain$PublicDownloadsPath/mobile-build.json"
$htmlApkName = [System.Net.WebUtility]::HtmlEncode($ApkFileName)
$htmlGeneratedAt = [System.Net.WebUtility]::HtmlEncode($generatedAt)
$htmlDownloadUrl = [System.Net.WebUtility]::HtmlEncode("$PublicDownloadsPath/$ApkFileName")
$htmlVersionName = [System.Net.WebUtility]::HtmlEncode($VersionName)
$htmlVersionCode = [System.Net.WebUtility]::HtmlEncode([string]$VersionCode)
$htmlApiUrl = [System.Net.WebUtility]::HtmlEncode($ApiUrl)
$htmlSha256 = [System.Net.WebUtility]::HtmlEncode($apkSha256)

New-Item -ItemType Directory -Force -Path $StagingDir | Out-Null
$stagedApk = Join-Path $StagingDir $ApkFileName
$stagedIndex = Join-Path $StagingDir 'index.html'
$stagedMetadata = Join-Path $StagingDir 'mobile-build.json'
Copy-Item -LiteralPath $ApkPath -Destination $stagedApk -Force

$downloadMetadata = [ordered]@{
    product = 'Vlugboek'
    appId = 'za.co.vlugboek.app'
    appName = 'Vlugboek'
    buildType = $BuildType
    apiUrl = $ApiUrl
    versionName = $VersionName
    versionCode = $VersionCode
    signed = if ($metadata) { [bool]$metadata.signed } else { $BuildType -eq 'release' -and $ApkFileName -notlike '*unsigned*' }
    fileName = $ApkFileName
    sizeBytes = $apk.Length
    sha256 = $apkSha256
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    downloadUrl = "$PublicDownloadsPath/$ApkFileName"
}
$downloadMetadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stagedMetadata -Encoding ASCII

$downloadHtml = @"
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Download Vlugboek Android</title>
  <style>
    :root {
      --navy: #0b1623;
      --ink: #182331;
      --paper: #fffaf1;
      --line: #d9d2c5;
      --gold: #c79a47;
      --green: #315d4e;
    }

    * { box-sizing: border-box; }

    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 24px;
      background: #f8f6f1;
      color: var(--ink);
      font-family: "Segoe UI", Arial, sans-serif;
    }

    main {
      width: min(680px, 100%);
      border: 1px solid var(--line);
      border-radius: 8px;
      background: white;
      box-shadow: 0 18px 50px rgba(11, 22, 35, 0.12);
      overflow: hidden;
    }

    header {
      padding: 28px 24px;
      background: var(--navy);
      color: var(--paper);
      border-bottom: 5px solid var(--gold);
    }

    h1 {
      margin: 0;
      font: 700 clamp(2rem, 8vw, 3.2rem) Georgia, "Times New Roman", serif;
      line-height: 1;
    }

    section { padding: 24px; }

    p { margin: 0 0 14px; line-height: 1.55; }

    .meta {
      display: grid;
      gap: 8px;
      margin: 18px 0;
      color: #5b6570;
      font-size: 0.95rem;
    }

    a.button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 48px;
      padding: 0 18px;
      border-radius: 8px;
      background: var(--green);
      color: white;
      font-weight: 800;
      text-decoration: none;
    }

    code {
      font-family: Consolas, "Cascadia Mono", "Courier New", monospace;
      color: var(--navy);
      overflow-wrap: anywhere;
    }
  </style>
</head>
<body>
  <main>
    <header>
      <h1>Vlugboek Android</h1>
    </header>
    <section>
      <p>Download and install the latest Vlugboek Android APK.</p>
      <p><a class="button" href="$htmlDownloadUrl">Download APK</a></p>
      <div class="meta">
        <span>Version: <code>$htmlVersionName</code> ($htmlVersionCode)</span>
        <span>File: <code>$htmlApkName</code></span>
        <span>Size: $apkSizeMb MB</span>
        <span>API: <code>$htmlApiUrl</code></span>
        <span>SHA-256: <code>$htmlSha256</code></span>
        <span>Published: $htmlGeneratedAt</span>
      </div>
      <p>Android may ask for permission to install apps from the browser before opening this APK.</p>
    </section>
  </main>
</body>
</html>
"@
Set-Content -LiteralPath $stagedIndex -Value $downloadHtml -Encoding UTF8

$RemoteDownloadsDir = "$RemoteRoot/shared/downloads"
Write-Host "Creating remote downloads directory on $Target..."
Invoke-External ssh @($Target, "mkdir -p '$RemoteDownloadsDir'")

Write-Host "Uploading APK and download page..."
Invoke-External scp @($stagedApk, "${Target}:$RemoteDownloadsDir/$ApkFileName")
Invoke-External scp @($stagedIndex, "${Target}:$RemoteDownloadsDir/index.html")
Invoke-External scp @($stagedMetadata, "${Target}:$RemoteDownloadsDir/mobile-build.json")

Write-Host 'Linking downloads into the current web root and running checks...'
$remoteCommand = @"
set -e
if [ ! -d '$RemoteRoot/current/frontend' ]; then
  echo 'Missing $RemoteRoot/current/frontend. Run the main deploy script before publishing mobile downloads.' >&2
  exit 1
fi
if [ -e '$RemoteRoot/current/frontend/downloads' ] && [ ! -L '$RemoteRoot/current/frontend/downloads' ]; then
  echo 'Using existing frontend downloads directory.'
else
  ln -sfnT '$RemoteDownloadsDir' '$RemoteRoot/current/frontend/downloads'
fi
chmod 755 '$RemoteRoot' '$RemoteRoot/shared' '$RemoteDownloadsDir'
chmod 644 '$RemoteDownloadsDir/$ApkFileName' '$RemoteDownloadsDir/index.html' '$RemoteDownloadsDir/mobile-build.json'
if [ -f '/etc/letsencrypt/live/$Domain/fullchain.pem' ]; then
  curl -kfsS --resolve '${Domain}:443:127.0.0.1' 'https://${Domain}${PublicDownloadsPath}/' >/dev/null
  curl -kfsS --resolve '${Domain}:443:127.0.0.1' 'https://${Domain}${PublicDownloadsPath}/${ApkFileName}' -o /dev/null
  curl -kfsS --resolve '${Domain}:443:127.0.0.1' 'https://${Domain}${PublicDownloadsPath}/mobile-build.json' >/dev/null
else
  curl -fsS -H 'Host: ${Domain}' 'http://127.0.0.1${PublicDownloadsPath}/' >/dev/null
  curl -fsS -H 'Host: ${Domain}' 'http://127.0.0.1${PublicDownloadsPath}/${ApkFileName}' -o /dev/null
  curl -fsS -H 'Host: ${Domain}' 'http://127.0.0.1${PublicDownloadsPath}/mobile-build.json' >/dev/null
fi
"@
Invoke-External ssh @($Target, $remoteCommand)

if (-not $SkipPublicCheck) {
    Write-Host 'Checking public mobile download endpoints...'
    Invoke-WebRequest -Uri $DownloadPageUrl -UseBasicParsing -TimeoutSec 20 | Out-Null
    Invoke-WebRequest -Uri $DownloadApkUrl -UseBasicParsing -Method Head -TimeoutSec 20 | Out-Null
    Invoke-WebRequest -Uri $DownloadMetadataUrl -UseBasicParsing -TimeoutSec 20 | Out-Null
}

Write-Host "Mobile client deployed: $DownloadPageUrl"
Write-Host "APK URL: $DownloadApkUrl"
Write-Host "Metadata URL: $DownloadMetadataUrl"
