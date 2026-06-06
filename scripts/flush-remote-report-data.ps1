#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$RemoteRoot = '/opt/vlugboek',
    [string]$ServiceName = 'vlugboek',
    [ValidateSet('auto', 'postgres', 'h2')]
    [string]$Database = 'auto',
    [string]$SpringProfilesActive = '',
    [string]$DatabaseUrl = '',
    [string]$DatabaseUser = '',
    [string]$DatabasePassword = '',
    [switch]$SkipBackup,
    [switch]$KeepPdfSeederEnabled,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Target = "$User@$Server"

function ConvertTo-ShellLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "'`"'`"'") + "'"
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw 'OpenSSH ssh was not found on PATH.'
}

if (-not $Force) {
    Write-Warning "This will flush remote Vlugboek report/import data on $Target."
    Write-Warning "Documents, report datasets, classification snapshots, email audits, and uploaded PDF files will be deleted."
    Write-Warning "Users, federations, clubs, and lofts will NOT be changed."
    $answer = Read-Host "Type FLUSH REPORTS $Server to continue"
    if ($answer -ne "FLUSH REPORTS $Server") {
        Write-Host 'Cancelled.'
        return
    }
}

$RemoteScriptTemplate = @'
set -Eeuo pipefail

APP_ROOT=__APP_ROOT__
SERVICE_NAME=__SERVICE_NAME__
DATABASE_MODE=__DATABASE_MODE__
SPRING_PROFILE_OVERRIDE=__SPRING_PROFILE__
DB_URL_OVERRIDE=__DB_URL__
DB_USER_OVERRIDE=__DB_USER__
DB_PASSWORD_OVERRIDE=__DB_PASSWORD__
SKIP_BACKUP=__SKIP_BACKUP__
KEEP_PDF_SEEDER_ENABLED=__KEEP_PDF_SEEDER_ENABLED__

if [[ "$(id -u)" != "0" ]]; then
  echo "Run this script as root on the remote host." >&2
  exit 1
fi

SHARED_DIR="$APP_ROOT/shared"
CURRENT_LINK="$APP_ROOT/current"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups"
UPLOADS_DIR="$(readlink -m "$SHARED_DIR/uploads")"
EXPECTED_UPLOADS_DIR="$(readlink -m "$APP_ROOT/shared/uploads")"
mkdir -p "$BACKUP_DIR" "$UPLOADS_DIR"

if [[ "$UPLOADS_DIR" != "$EXPECTED_UPLOADS_DIR" ]]; then
  echo "Refusing to clear unexpected uploads directory: $UPLOADS_DIR" >&2
  exit 1
fi

systemd_env() {
  local name="$1"
  systemctl cat "$SERVICE_NAME" 2>/dev/null |
    sed -n "s/^Environment=\"$name=\(.*\)\"$/\1/p" |
    tail -n 1 |
    sed 's/%%/%/g; s/\\"/"/g; s/\\\\/\\/g'
}

SPRING_PROFILE="$SPRING_PROFILE_OVERRIDE"
if [[ -z "$SPRING_PROFILE" ]]; then
  SPRING_PROFILE="$(systemd_env SPRING_PROFILES_ACTIVE || true)"
fi

DATASOURCE_URL="$DB_URL_OVERRIDE"
if [[ -z "$DATASOURCE_URL" ]]; then
  if [[ "$SPRING_PROFILE" == *postgres* ]]; then
    DATASOURCE_URL="$(systemd_env VLUGBOEK_DB_URL || true)"
  else
    DATASOURCE_URL="$(systemd_env VLUGBOEK_DATASOURCE_URL || true)"
  fi
fi

DB_USER="$DB_USER_OVERRIDE"
if [[ -z "$DB_USER" && "$SPRING_PROFILE" == *postgres* ]]; then
  DB_USER="$(systemd_env VLUGBOEK_DB_USER || true)"
fi

DB_PASSWORD="$DB_PASSWORD_OVERRIDE"
if [[ -z "$DB_PASSWORD" && "$SPRING_PROFILE" == *postgres* ]]; then
  DB_PASSWORD="$(systemd_env VLUGBOEK_DB_PASSWORD || true)"
fi

if [[ -z "$DATASOURCE_URL" ]]; then
  DATASOURCE_URL="jdbc:h2:file:$SHARED_DIR/data/vlugboek;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
fi

if [[ "$DATABASE_MODE" == "auto" ]]; then
  if [[ "$SPRING_PROFILE" == *postgres* || "$DATASOURCE_URL" == jdbc:postgresql:* ]]; then
    DATABASE_MODE="postgres"
  elif [[ "$DATASOURCE_URL" == jdbc:h2:* ]]; then
    DATABASE_MODE="h2"
  else
    echo "Could not determine database mode from service environment." >&2
    echo "Datasource URL: $DATASOURCE_URL" >&2
    exit 1
  fi
fi

WAS_ACTIVE=false
if systemctl is-active --quiet "$SERVICE_NAME"; then
  WAS_ACTIVE=true
fi
FLUSH_SUCCEEDED=false

restart_if_needed() {
  if [[ "$WAS_ACTIVE" == "true" ]]; then
    if [[ "$FLUSH_SUCCEEDED" == "true" && "$KEEP_PDF_SEEDER_ENABLED" != "true" ]]; then
      echo "Disabling PDF seeder for report-data restart..."
      local override_dir="/etc/systemd/system/$SERVICE_NAME.service.d"
      mkdir -p "$override_dir"
      cat > "$override_dir/91-report-data-seed-disable.conf" <<'SEED_EOF'
[Service]
Environment="VLUGBOEK_SEED_PDF_IMPORT_ENABLED=false"
SEED_EOF
      systemctl daemon-reload >/dev/null 2>&1 || true
    fi
    systemctl start "$SERVICE_NAME" >/dev/null 2>&1 || true
  fi
}
trap restart_if_needed EXIT

echo "Stopping $SERVICE_NAME..."
systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true

H2_JAR=""
ensure_h2_jar() {
  H2_JAR="$SHARED_DIR/tmp/h2-tools.jar"
  if [[ -f "$H2_JAR" ]]; then
    return
  fi
  mkdir -p "$SHARED_DIR/tmp"
  if ! command -v unzip >/dev/null 2>&1; then
    apt-get update
    apt-get install -y unzip
  fi
  unzip -p "$CURRENT_LINK/app/vlugboek.jar" 'BOOT-INF/lib/h2-*.jar' > "$H2_JAR"
}

run_h2_sql() {
  local sql="$1"
  ensure_h2_jar
  java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$DATASOURCE_URL" \
    -user sa \
    -sql "$sql"
}

run_h2_scalar() {
  local sql="$1"
  run_h2_sql "$sql" | grep -E '^[[:space:]]*[0-9]+[[:space:]]*$' | tail -n 1 | tr -d '[:space:]'
}

parse_postgres_url() {
  local jdbc="${DATASOURCE_URL#jdbc:postgresql://}"
  local without_query="${jdbc%%\?*}"
  PG_HOSTPORT="${without_query%%/*}"
  PG_DATABASE="${without_query#*/}"
  PG_HOST="${PG_HOSTPORT%%:*}"
  if [[ "$PG_HOSTPORT" == *:* ]]; then
    PG_PORT="${PG_HOSTPORT#*:}"
  else
    PG_PORT="5432"
  fi
  if [[ -z "$PG_HOST" || -z "$PG_DATABASE" || "$PG_DATABASE" == "$PG_HOSTPORT" ]]; then
    echo "Unsupported PostgreSQL JDBC URL: $DATASOURCE_URL" >&2
    exit 1
  fi
  if [[ -z "$DB_USER" ]]; then
    echo "Database user is required for PostgreSQL report-data flush." >&2
    exit 1
  fi
}

run_pg_sql() {
  local sql="$1"
  PGPASSWORD="$DB_PASSWORD" psql \
    -v ON_ERROR_STOP=1 \
    --host "$PG_HOST" \
    --port "$PG_PORT" \
    --username "$DB_USER" \
    --dbname "$PG_DATABASE" \
    --command "$sql"
}

run_pg_scalar() {
  local sql="$1"
  PGPASSWORD="$DB_PASSWORD" psql \
    -v ON_ERROR_STOP=1 \
    -tA \
    --host "$PG_HOST" \
    --port "$PG_PORT" \
    --username "$DB_USER" \
    --dbname "$PG_DATABASE" \
    --command "$sql"
}

REPORT_FLUSH_SQL="
begin;
delete from email_delivery_audits;
delete from classification_snapshots;
delete from report_cells;
delete from report_columns;
delete from report_rows;
delete from report_datasets;
delete from documents;
commit;
"

count_upload_files() {
  find "$UPLOADS_DIR" -mindepth 1 -type f 2>/dev/null | wc -l | tr -d '[:space:]'
}

clear_uploads() {
  echo "Clearing uploaded PDF files from $UPLOADS_DIR..."
  find "$UPLOADS_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
}

backup_uploads() {
  local backup_path="$BACKUP_DIR/vlugboek-uploads-before-report-flush-$STAMP.tar.gz"
  echo "Writing uploads backup: $backup_path"
  tar -czf "$backup_path" -C "$SHARED_DIR" uploads
}

echo "Database mode: $DATABASE_MODE"
UPLOADS_BEFORE="$(count_upload_files)"

if [[ "$DATABASE_MODE" == "postgres" ]]; then
  parse_postgres_url
  if ! command -v psql >/dev/null 2>&1; then
    apt-get update
    apt-get install -y postgresql-client
  fi

  if [[ "$SKIP_BACKUP" != "true" ]]; then
    if ! command -v pg_dump >/dev/null 2>&1; then
      apt-get update
      apt-get install -y postgresql-client
    fi
    BACKUP_PATH="$BACKUP_DIR/vlugboek-postgres-before-report-flush-$STAMP.dump"
    echo "Writing PostgreSQL backup: $BACKUP_PATH"
    PGPASSWORD="$DB_PASSWORD" pg_dump \
      --host "$PG_HOST" \
      --port "$PG_PORT" \
      --username "$DB_USER" \
      --format custom \
      --file "$BACKUP_PATH" \
      "$PG_DATABASE"
    backup_uploads
  fi

  echo "Flushing report/import rows..."
  run_pg_sql "$REPORT_FLUSH_SQL"
  clear_uploads
  REMAINING_DOCUMENTS="$(run_pg_scalar "select count(*) from documents;")"
  REMAINING_DATASETS="$(run_pg_scalar "select count(*) from report_datasets;")"
  REMAINING_EMAIL_AUDITS="$(run_pg_scalar "select count(*) from email_delivery_audits;")"
  REMAINING_USERS="$(run_pg_scalar "select count(*) from app_users;")"
  REMAINING_FEDERATIONS="$(run_pg_scalar "select count(*) from federations;")"
  REMAINING_CLUBS="$(run_pg_scalar "select count(*) from clubs;")"
  REMAINING_LOFTS="$(run_pg_scalar "select count(*) from lofts;")"
else
  if [[ "$SKIP_BACKUP" != "true" ]]; then
    BACKUP_PATH="$BACKUP_DIR/vlugboek-h2-before-report-flush-$STAMP.tar.gz"
    echo "Writing H2 and uploads backup: $BACKUP_PATH"
    tar -czf "$BACKUP_PATH" -C "$SHARED_DIR" data uploads
  fi

  echo "Flushing report/import rows..."
  run_h2_sql "$REPORT_FLUSH_SQL"
  clear_uploads
  REMAINING_DOCUMENTS="$(run_h2_scalar "select count(*) from documents;")"
  REMAINING_DATASETS="$(run_h2_scalar "select count(*) from report_datasets;")"
  REMAINING_EMAIL_AUDITS="$(run_h2_scalar "select count(*) from email_delivery_audits;")"
  REMAINING_USERS="$(run_h2_scalar "select count(*) from app_users;")"
  REMAINING_FEDERATIONS="$(run_h2_scalar "select count(*) from federations;")"
  REMAINING_CLUBS="$(run_h2_scalar "select count(*) from clubs;")"
  REMAINING_LOFTS="$(run_h2_scalar "select count(*) from lofts;")"
fi

UPLOADS_AFTER="$(count_upload_files)"
FLUSH_SUCCEEDED=true

echo "Report-data flush complete."
echo "Remaining documents: $REMAINING_DOCUMENTS"
echo "Remaining datasets: $REMAINING_DATASETS"
echo "Remaining email audits: $REMAINING_EMAIL_AUDITS"
echo "Uploaded files before flush: $UPLOADS_BEFORE"
echo "Uploaded files after flush: $UPLOADS_AFTER"
echo "Users preserved: $REMAINING_USERS"
echo "Federations preserved: $REMAINING_FEDERATIONS"
echo "Clubs preserved: $REMAINING_CLUBS"
echo "Lofts preserved: $REMAINING_LOFTS"
'@

$RemoteScript = $RemoteScriptTemplate.
    Replace('__APP_ROOT__', (ConvertTo-ShellLiteral $RemoteRoot)).
    Replace('__SERVICE_NAME__', (ConvertTo-ShellLiteral $ServiceName)).
    Replace('__DATABASE_MODE__', (ConvertTo-ShellLiteral $Database)).
    Replace('__SPRING_PROFILE__', (ConvertTo-ShellLiteral $SpringProfilesActive)).
    Replace('__DB_URL__', (ConvertTo-ShellLiteral $DatabaseUrl)).
    Replace('__DB_USER__', (ConvertTo-ShellLiteral $DatabaseUser)).
    Replace('__DB_PASSWORD__', (ConvertTo-ShellLiteral $DatabasePassword)).
    Replace('__SKIP_BACKUP__', (ConvertTo-ShellLiteral ($SkipBackup.IsPresent.ToString().ToLowerInvariant()))).
    Replace('__KEEP_PDF_SEEDER_ENABLED__', (ConvertTo-ShellLiteral ($KeepPdfSeederEnabled.IsPresent.ToString().ToLowerInvariant())))
$RemoteScript = $RemoteScript -replace "`r`n", "`n" -replace "`r", ''

if ($PSCmdlet.ShouldProcess($Target, 'Flush remote Vlugboek report/import data while preserving users and organisation master data')) {
    $RemoteScript | & ssh $Target 'bash -s'
    if ($LASTEXITCODE -ne 0) {
        throw 'Remote report-data flush failed.'
    }
}
