#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'mobile-keystores\vlugboek-release.jks'),
    [string]$Alias = 'vlugboek',
    [string]$DistinguishedName = 'CN=Vlugboek, OU=Vlugboek, O=Vlugboek, L=Pretoria, ST=Gauteng, C=ZA',
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = 'Stop'

if (Test-Path -LiteralPath $KeystorePath) {
    throw "Keystore already exists at $KeystorePath. Refusing to overwrite it."
}

$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath $keytool)) {
    $keytool = 'keytool'
}

$secureStorePass = Read-Host 'Keystore password' -AsSecureString
$secureKeyPass = Read-Host 'Key password' -AsSecureString
$storePass = [System.Net.NetworkCredential]::new('', $secureStorePass).Password
$keyPass = [System.Net.NetworkCredential]::new('', $secureKeyPass).Password

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $KeystorePath) | Out-Null

& $keytool -genkeypair `
    -v `
    -keystore $KeystorePath `
    -alias $Alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $ValidityDays `
    -storepass $storePass `
    -keypass $keyPass `
    -dname $DistinguishedName

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

Write-Host "Keystore created: $KeystorePath"
Write-Host ''
Write-Host 'Use these for release builds:'
Write-Host "`$env:VLUGBOEK_ANDROID_KEYSTORE='$KeystorePath'"
Write-Host "`$env:VLUGBOEK_ANDROID_KEY_ALIAS='$Alias'"
Write-Host "`$env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD='<your keystore password>'"
Write-Host "`$env:VLUGBOEK_ANDROID_KEY_PASSWORD='<your key password>'"
