# Common helpers for reproducible Vlugboek mobile builds.

function Get-MobilePackageVersion {
    param([string]$Root)

    $packageJson = Join-Path $Root 'frontend\package.json'
    if (-not (Test-Path -LiteralPath $packageJson)) {
        return '0.0.1'
    }

    try {
        $package = Get-Content -LiteralPath $packageJson -Raw | ConvertFrom-Json
        if ($package.version) {
            return [string]$package.version
        }
    } catch {
        return '0.0.1'
    }
    return '0.0.1'
}

function Get-MobileDefaultVersionCode {
    $now = [DateTime]::UtcNow
    $base = [DateTime]::SpecifyKind([DateTime]'2020-01-01T00:00:00', [DateTimeKind]::Utc)
    $days = [int]($now.Date - $base.Date).TotalDays
    return ($days * 10000) + [int]$now.ToString('HHmm')
}

function Get-MobileDefaultVersionName {
    param([string]$Root)

    $packageVersion = Get-MobilePackageVersion -Root $Root
    return "$packageVersion.$((Get-Date).ToUniversalTime().ToString('yyyyMMdd.HHmm'))"
}

function ConvertTo-MobileFileToken {
    param([string]$Value)

    $token = $Value -replace '[^A-Za-z0-9._-]+', '-'
    $token = $token.Trim('.-_')
    if ($token) { return $token }
    return 'build'
}

function Resolve-MobileVersion {
    param(
        [string]$Root,
        [string]$VersionName,
        [int]$VersionCode
    )

    if (-not $VersionName) {
        $VersionName = Get-MobileDefaultVersionName -Root $Root
    }
    if ($VersionCode -le 0) {
        $VersionCode = Get-MobileDefaultVersionCode
    }
    if ($VersionCode -le 0 -or $VersionCode -gt 2100000000) {
        throw "Android VersionCode must be between 1 and 2100000000. Value was $VersionCode."
    }

    [pscustomobject]@{
        VersionName = $VersionName
        VersionCode = $VersionCode
    }
}

function Write-MobileWebBuildManifest {
    param(
        [string]$Root,
        [string]$ApiUrl,
        [string]$BuildType,
        [string]$VersionName,
        [int]$VersionCode
    )

    $publicDir = Join-Path $Root 'frontend\public'
    New-Item -ItemType Directory -Force -Path $publicDir | Out-Null

    $manifest = [ordered]@{
        product = 'Vlugboek'
        appId = 'za.co.vlugboek.app'
        appName = 'Vlugboek'
        buildType = $BuildType
        apiUrl = $ApiUrl
        versionName = $VersionName
        versionCode = $VersionCode
        packageVersion = Get-MobilePackageVersion -Root $Root
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }

    $path = Join-Path $publicDir 'mobile-build.json'
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $path -Encoding ASCII
    return $path
}

function Get-FileSha256 {
    param([string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-MobileApkMetadata {
    param(
        [string]$Root,
        [string]$ApkPath,
        [string]$BuildType,
        [string]$ApiUrl,
        [string]$VersionName,
        [int]$VersionCode,
        [bool]$Signed
    )

    $apk = Get-Item -LiteralPath $ApkPath
    $metadata = [ordered]@{
        product = 'Vlugboek'
        appId = 'za.co.vlugboek.app'
        appName = 'Vlugboek'
        buildType = $BuildType
        apiUrl = $ApiUrl
        versionName = $VersionName
        versionCode = $VersionCode
        signed = $Signed
        fileName = $apk.Name
        sizeBytes = $apk.Length
        sha256 = Get-FileSha256 -Path $apk.FullName
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }

    $json = $metadata | ConvertTo-Json -Depth 5
    $sidecarPath = "$ApkPath.json"
    $latestPath = Join-Path (Split-Path -Parent $ApkPath) "latest-$BuildType.json"
    $json | Set-Content -LiteralPath $sidecarPath -Encoding ASCII
    $json | Set-Content -LiteralPath $latestPath -Encoding ASCII
    return [pscustomobject]$metadata
}

function Read-MobileApkMetadata {
    param(
        [string]$Root,
        [string]$ApkPath,
        [string]$BuildType
    )

    $candidates = @(
        "$ApkPath.json",
        (Join-Path $Root "dist\mobile\latest-$BuildType.json")
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            try {
                return Get-Content -LiteralPath $candidate -Raw | ConvertFrom-Json
            } catch {
                continue
            }
        }
    }

    return $null
}
