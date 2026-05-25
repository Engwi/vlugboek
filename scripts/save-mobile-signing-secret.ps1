#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$KeystorePath = 'C:\Development\Vlugboek\vlugboekkeystore',
    [string]$KeyAlias = 'key0',
    [switch]$UseSamePassword
)

$ErrorActionPreference = 'Stop'
$SecretDir = Join-Path $PSScriptRoot '.secrets'
$SecretFile = Join-Path $SecretDir 'mobile-signing.clixml'

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

if (-not (Test-Path -LiteralPath $KeystorePath)) {
    throw "Keystore file not found at $KeystorePath"
}

New-Item -ItemType Directory -Force -Path $SecretDir | Out-Null

$keystorePassword = Read-Host 'Keystore password' -AsSecureString
$keyPassword = if ($UseSamePassword) {
    $keystorePassword
} else {
    Read-Host 'Key password' -AsSecureString
}

$plainKeystorePassword = ConvertFrom-SecureStringForProcess $keystorePassword
$aliases = Get-KeystoreAliases -Path (Resolve-Path -LiteralPath $KeystorePath).Path -StorePassword $plainKeystorePassword
if ($aliases.Count -gt 0 -and $aliases -notcontains $KeyAlias) {
    if ($aliases.Count -eq 1) {
        Write-Warning "Configured key alias '$KeyAlias' was not found. Saving the only alias in the keystore instead: '$($aliases[0])'."
        $KeyAlias = $aliases[0]
    } else {
        throw "Configured key alias '$KeyAlias' was not found. Available aliases: $($aliases -join ', '). Re-run with -KeyAlias <alias>."
    }
}

[pscustomobject]@{
    KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path
    KeyAlias = $KeyAlias
    KeystorePassword = $keystorePassword
    KeyPassword = $keyPassword
} | Export-Clixml -LiteralPath $SecretFile

Write-Host "Saved encrypted mobile signing settings: $SecretFile"
Write-Host 'This file is encrypted for the current Windows user and machine.'
