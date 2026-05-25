#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Release = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$NpmCli = 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js'
$JarPath = Join-Path $Root 'target\vlugboek-0.0.1-SNAPSHOT.jar'
$FrontendDist = Join-Path $Root 'frontend\dist'
$StageRoot = Join-Path $Root 'dist\deploy'
$ReleaseName = "vlugboek-$Release"
$ReleaseDir = Join-Path $StageRoot $ReleaseName
$BundlePath = Join-Path $StageRoot "$ReleaseName.tar.gz"
$BuildTime = (Get-Date).ToUniversalTime().ToString('o')

function Get-ProjectVersion {
    try {
        [xml]$pom = Get-Content -LiteralPath (Join-Path $Root 'pom.xml')
        $version = [string]$pom.project.version
        if ($version) { return $version }
    } catch {
        return '0.0.1-SNAPSHOT'
    }
    return '0.0.1-SNAPSHOT'
}

function Get-GitCommit {
    if (-not (Get-Command git -ErrorAction SilentlyContinue) -or -not (Test-Path -LiteralPath (Join-Path $Root '.git'))) {
        return 'unknown'
    }
    Push-Location $Root
    try {
        $commit = (& git rev-parse --short HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $commit) {
            return ($commit | Select-Object -First 1).ToString().Trim()
        }
    } finally {
        Pop-Location
    }
    return 'unknown'
}

function Clear-OldReleaseArtifacts {
    if (-not (Test-Path -LiteralPath $StageRoot)) {
        return
    }

    Get-ChildItem -LiteralPath $StageRoot -Force |
        Where-Object {
            $_.Name -like 'vlugboek-*' -and
            $_.FullName -ne $ReleaseDir -and
            ($_.PSIsContainer -or $_.Name -like '*.tar.gz')
        } |
        ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }
}

if (-not $SkipBuild) {
    Write-Host 'Building backend...'
    Push-Location $Root
    try {
        & mvn package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    } finally {
        Pop-Location
    }

    Write-Host 'Building frontend...'
    Push-Location (Join-Path $Root 'frontend')
    try {
        & node $NpmCli run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Backend jar not found at $JarPath"
}
if (-not (Test-Path -LiteralPath $FrontendDist)) {
    throw "Frontend dist not found at $FrontendDist"
}

New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null
Clear-OldReleaseArtifacts

if (Test-Path -LiteralPath $ReleaseDir) {
    Remove-Item -LiteralPath $ReleaseDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Join-Path $ReleaseDir 'app'), (Join-Path $ReleaseDir 'frontend'), (Join-Path $ReleaseDir 'Docs') | Out-Null

Copy-Item -LiteralPath $JarPath -Destination (Join-Path $ReleaseDir 'app\vlugboek.jar') -Force
$ReleaseInfoPath = Join-Path $ReleaseDir 'app\release-info.properties'
@(
    "version=$(Get-ProjectVersion)"
    "release=$ReleaseName"
    "buildTime=$BuildTime"
    "commit=$(Get-GitCommit)"
) | Set-Content -LiteralPath $ReleaseInfoPath -Encoding ASCII
Copy-Item -Path (Join-Path $FrontendDist '*') -Destination (Join-Path $ReleaseDir 'frontend') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $Root 'Docs\Uitslae') -Destination (Join-Path $ReleaseDir 'Docs\Uitslae') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $Root 'README.md') -Destination (Join-Path $ReleaseDir 'README.md') -Force

if (Test-Path -LiteralPath $BundlePath) {
    Remove-Item -LiteralPath $BundlePath -Force
}

Push-Location $StageRoot
try {
    & tar -czf $BundlePath $ReleaseName
    if ($LASTEXITCODE -ne 0) { throw 'tar failed while creating release bundle.' }
} finally {
    Pop-Location
}

Remove-Item -LiteralPath $ReleaseDir -Recurse -Force

Write-Host "Bundle created: $BundlePath"
Write-Output $BundlePath
