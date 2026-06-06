#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$DatabasePath = '',
    [string]$DatabaseUrl = '',
    [string]$UploadsDir = '',
    [string]$BackupDir = '',
    [int]$BackendPort = 8081,
    [string]$JavaExe = '',
    [switch]$SkipBackup,
    [switch]$KeepPdfSeederEnabled,
    [switch]$NoRestart,
    [switch]$StopBackendPortOwner,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Root 'logs'
$Tmp = Join-Path $Root 'tmp'
$BackendPidFile = Join-Path $Logs 'backend.pid'
$RunBackend = Join-Path $PSScriptRoot 'run-backend.cmd'
$LocalBackendEnv = Join-Path $PSScriptRoot 'local-backend.env.cmd'

if (-not $DatabasePath) {
    $DatabasePath = Join-Path $Root 'data\vlugboek'
}
if (-not $DatabaseUrl) {
    $h2Path = ([IO.Path]::GetFullPath($DatabasePath)) -replace '\\', '/'
    $DatabaseUrl = "jdbc:h2:file:$h2Path;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
}
if (-not $UploadsDir) {
    $UploadsDir = Join-Path $Root 'data\uploads'
}
if (-not $BackupDir) {
    $BackupDir = Join-Path $Root 'dist\backups'
}

function Get-H2DatabasePathFromUrl {
    param([string]$Url)

    if ($Url -notlike 'jdbc:h2:file:*') {
        throw "Only local H2 file databases are supported by this script. URL was: $Url"
    }

    $rawPath = $Url.Substring('jdbc:h2:file:'.Length)
    $rawPath = ($rawPath -split ';', 2)[0]
    if ($rawPath.StartsWith('./') -or $rawPath.StartsWith('.\')) {
        $rawPath = Join-Path $Root $rawPath.Substring(2)
    } elseif (-not [IO.Path]::IsPathRooted($rawPath)) {
        $rawPath = Join-Path $Root $rawPath
    }

    return [IO.Path]::GetFullPath(($rawPath -replace '/', [IO.Path]::DirectorySeparatorChar))
}

function Get-JavaExecutable {
    param([string]$Requested)

    if ($Requested) {
        if (Test-Path -LiteralPath $Requested) { return $Requested }
        throw "Java executable not found at $Requested"
    }

    if ($env:JAVA_HOME) {
        $fromJavaHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $fromJavaHome) { return $fromJavaHome }
    }

    $fromPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $defaultJdk = 'C:\Program Files\Java\jdk-21\bin\java.exe'
    if (Test-Path -LiteralPath $defaultJdk) { return $defaultJdk }

    throw 'Java was not found. Set JAVA_HOME, add java.exe to PATH, or pass -JavaExe.'
}

function Ensure-H2Jar {
    $h2Jar = Join-Path $Tmp 'h2-tools.jar'
    if (Test-Path -LiteralPath $h2Jar) {
        return $h2Jar
    }

    $appJar = Join-Path $Root 'target\vlugboek-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $appJar)) {
        throw "Backend jar not found at $appJar. Run .\scripts\start-local.ps1 -Build first."
    }

    New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($appJar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/h2-*.jar' } | Select-Object -First 1
        if (-not $entry) {
            throw "Could not find H2 jar inside $appJar"
        }

        $source = $entry.Open()
        try {
            $target = [IO.File]::Create($h2Jar)
            try {
                $source.CopyTo($target)
            } finally {
                $target.Dispose()
            }
        } finally {
            $source.Dispose()
        }
    } finally {
        $zip.Dispose()
    }

    return $h2Jar
}

function Invoke-H2Sql {
    param(
        [string]$Sql,
        [switch]$Scalar
    )

    $h2Jar = Ensure-H2Jar
    $java = Get-JavaExecutable -Requested $JavaExe
    $output = & $java -cp $h2Jar org.h2.tools.Shell -url $DatabaseUrl -user sa -sql $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | ForEach-Object { Write-Host $_ }
        throw "H2 SQL failed with exit code $LASTEXITCODE"
    }

    if ($Scalar) {
        $value = $output | Where-Object { $_ -match '^\s*[0-9]+\s*$' } | Select-Object -Last 1
        if ($null -eq $value) { return '0' }
        return ([string]$value).Trim()
    }

    $output | ForEach-Object { Write-Host $_ }
}

function Test-ListeningPort {
    param([int]$Port)

    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $connection) { return $true }

    $escapedPort = [regex]::Escape([string]$Port)
    return $null -ne ((netstat -ano 2>$null) | Select-String -Pattern "^\s*TCP\s+\S+:$escapedPort\s+\S+\s+LISTENING\s+\d+" | Select-Object -First 1)
}

function Get-PortOwners {
    param([int]$Port)

    $owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -gt 0) { return $owners }

    $escapedPort = [regex]::Escape([string]$Port)
    return @((netstat -ano 2>$null) |
        Select-String -Pattern "^\s*TCP\s+\S+:$escapedPort\s+\S+\s+LISTENING\s+(\d+)" |
        ForEach-Object { [int]$_.Matches[0].Groups[1].Value } |
        Select-Object -Unique)
}

function Stop-ProcessTree {
    param([int]$ProcessId)

    $existing = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $existing) { return }
    & taskkill /PID $ProcessId /T /F | Out-Host
}

function Stop-BackendIfNeeded {
    $wasRunning = $false
    if (Test-Path -LiteralPath $BackendPidFile) {
        $pidValue = (Get-Content -LiteralPath $BackendPidFile -Raw).Trim()
        if ($pidValue -match '^\d+$') {
            $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
            if ($process) {
                $wasRunning = $true
                Write-Host "Stopping local backend PID $pidValue..."
                Stop-ProcessTree -ProcessId ([int]$pidValue)
            }
        }
        Remove-Item -LiteralPath $BackendPidFile -Force -ErrorAction SilentlyContinue
    }

    if (-not $wasRunning -and (Test-ListeningPort -Port $BackendPort)) {
        if (-not $StopBackendPortOwner) {
            throw "Backend port $BackendPort is listening, but logs\backend.pid was not usable. Stop the backend first, or pass -StopBackendPortOwner."
        }

        $owners = Get-PortOwners -Port $BackendPort
        foreach ($owner in $owners) {
            $wasRunning = $true
            Write-Host "Stopping local backend port owner PID $owner..."
            Stop-ProcessTree -ProcessId ([int]$owner)
        }
    }

    return $wasRunning
}

function Start-Backend {
    param([bool]$DisablePdfSeeder)

    if (-not (Test-Path -LiteralPath $RunBackend)) {
        Write-Warning "Cannot restart backend because $RunBackend was not found."
        return
    }

    New-Item -ItemType Directory -Force -Path $Logs | Out-Null
    $wrapperName = 'vlugboek-backend-restart-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.cmd'
    $wrapperFile = Join-Path $Logs $wrapperName
    $wrapperLines = @('@echo off')
    if ($DisablePdfSeeder) {
        $wrapperLines += 'set "VLUGBOEK_SEED_PDF_IMPORT_ENABLED=false"'
    }
    $wrapperLines += "call `"$RunBackend`""
    $wrapperLines | Set-Content -LiteralPath $wrapperFile -Encoding ASCII

    $process = Start-Process -FilePath $wrapperFile -WorkingDirectory $Root -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $BackendPidFile -Value $process.Id -Encoding ASCII
    Write-Host "Local backend restarted. PID $($process.Id)"
}

function Write-LocalSeederOverride {
    @(
        '@echo off',
        'rem Created by flush-local-report-data.ps1 to stop reports being recreated after a local report-data flush.',
        'set "VLUGBOEK_SEED_PDF_IMPORT_ENABLED=false"'
    ) | Set-Content -LiteralPath $LocalBackendEnv -Encoding ASCII
    Write-Host "Wrote local backend override: $LocalBackendEnv"
}

function Backup-LocalData {
    param(
        [string]$H2BasePath,
        [string]$UploadsPath
    )

    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stage = Join-Path $Tmp "local-report-flush-$stamp"
    $stageFull = [IO.Path]::GetFullPath($stage)
    $tmpFull = [IO.Path]::GetFullPath($Tmp)
    if (-not $stageFull.StartsWith($tmpFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to prepare backup staging outside tmp: $stageFull"
    }

    if (Test-Path -LiteralPath $stageFull) {
        Remove-Item -LiteralPath $stageFull -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $stageFull | Out-Null

    $databaseStage = Join-Path $stageFull 'data'
    New-Item -ItemType Directory -Force -Path $databaseStage | Out-Null
    $databaseDir = Split-Path -Parent $H2BasePath
    $databaseName = Split-Path -Leaf $H2BasePath
    Get-ChildItem -LiteralPath $databaseDir -Force -Filter "$databaseName*.db" -ErrorAction SilentlyContinue |
        Copy-Item -Destination $databaseStage -Force

    if (Test-Path -LiteralPath $UploadsPath) {
        Copy-Item -LiteralPath $UploadsPath -Destination (Join-Path $stageFull 'uploads') -Recurse -Force
    }

    $backupPath = Join-Path $BackupDir "vlugboek-local-report-data-before-flush-$stamp.zip"
    if (Test-Path -LiteralPath $backupPath) {
        Remove-Item -LiteralPath $backupPath -Force
    }
    Compress-Archive -Path (Join-Path $stageFull '*') -DestinationPath $backupPath -Force
    Remove-Item -LiteralPath $stageFull -Recurse -Force
    Write-Host "Backup created: $backupPath"
}

function Clear-Uploads {
    param([string]$Path)

    New-Item -ItemType Directory -Force -Path $Path | Out-Null
    $uploadsFull = [IO.Path]::GetFullPath($Path)
    $rootFull = [IO.Path]::GetFullPath($Root)
    if (-not $uploadsFull.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clear uploads outside the repository: $uploadsFull"
    }

    Write-Host "Clearing uploaded files from $uploadsFull..."
    Get-ChildItem -LiteralPath $uploadsFull -Force | Remove-Item -Recurse -Force
}

$h2BasePath = Get-H2DatabasePathFromUrl -Url $DatabaseUrl
$h2File = "$h2BasePath.mv.db"
$uploadsFullPath = [IO.Path]::GetFullPath($UploadsDir)

if (-not (Test-Path -LiteralPath $h2File)) {
    throw "Local H2 database file not found at $h2File"
}

if (-not $Force) {
    Write-Warning "This will flush local Vlugboek report/import data in $h2File."
    Write-Warning "Documents, report datasets, classification snapshots, email audits, and uploaded PDF files will be deleted."
    Write-Warning "Users, federations, clubs, and lofts will NOT be changed."
    $answer = Read-Host 'Type FLUSH LOCAL REPORTS to continue'
    if ($answer -ne 'FLUSH LOCAL REPORTS') {
        Write-Host 'Cancelled.'
        return
    }
}

$reportFlushSql = @'
begin;
delete from email_delivery_audits;
delete from classification_snapshots;
delete from report_cells;
delete from report_columns;
delete from report_rows;
delete from report_datasets;
delete from documents;
commit;
'@

$backendWasRunning = $false
$flushSucceeded = $false

if ($PSCmdlet.ShouldProcess($h2File, 'Flush local Vlugboek report/import data while preserving users and organisation master data')) {
    try {
        $backendWasRunning = Stop-BackendIfNeeded

        $uploadsBefore = 0
        if (Test-Path -LiteralPath $uploadsFullPath) {
            $uploadsBefore = @(Get-ChildItem -LiteralPath $uploadsFullPath -Recurse -File -ErrorAction SilentlyContinue).Count
        }

        if (-not $SkipBackup) {
            Backup-LocalData -H2BasePath $h2BasePath -UploadsPath $uploadsFullPath
        }

        Write-Host 'Flushing local report/import rows...'
        Invoke-H2Sql -Sql $reportFlushSql
        Clear-Uploads -Path $uploadsFullPath

        if (-not $KeepPdfSeederEnabled) {
            Write-LocalSeederOverride
        }

        $remainingDocuments = Invoke-H2Sql -Sql 'select count(*) from documents;' -Scalar
        $remainingDatasets = Invoke-H2Sql -Sql 'select count(*) from report_datasets;' -Scalar
        $remainingEmailAudits = Invoke-H2Sql -Sql 'select count(*) from email_delivery_audits;' -Scalar
        $remainingUsers = Invoke-H2Sql -Sql 'select count(*) from app_users;' -Scalar
        $remainingFederations = Invoke-H2Sql -Sql 'select count(*) from federations;' -Scalar
        $remainingClubs = Invoke-H2Sql -Sql 'select count(*) from clubs;' -Scalar
        $remainingLofts = Invoke-H2Sql -Sql 'select count(*) from lofts;' -Scalar
        $uploadsAfter = @(Get-ChildItem -LiteralPath $uploadsFullPath -Recurse -File -ErrorAction SilentlyContinue).Count

        $flushSucceeded = $true
        Write-Host 'Local report-data flush complete.'
        Write-Host "Remaining documents: $remainingDocuments"
        Write-Host "Remaining datasets: $remainingDatasets"
        Write-Host "Remaining email audits: $remainingEmailAudits"
        Write-Host "Uploaded files before flush: $uploadsBefore"
        Write-Host "Uploaded files after flush: $uploadsAfter"
        Write-Host "Users preserved: $remainingUsers"
        Write-Host "Federations preserved: $remainingFederations"
        Write-Host "Clubs preserved: $remainingClubs"
        Write-Host "Lofts preserved: $remainingLofts"
    } finally {
        if ($backendWasRunning -and $flushSucceeded -and -not $NoRestart) {
            Start-Backend -DisablePdfSeeder:(-not $KeepPdfSeederEnabled)
        } elseif ($backendWasRunning -and -not $flushSucceeded) {
            Write-Warning 'The backend was stopped, but the flush did not complete. Start it manually after checking the error above.'
        }
    }
}
