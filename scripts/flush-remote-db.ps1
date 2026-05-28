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
    [string]$PreserveAdminEmail = 'admin@vlugboek.local',
    [switch]$SkipBackup,
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
    Write-Warning "This will flush remote Vlugboek data on $Target. Reports, datasets, email audits, organisation records, and non-admin users will be deleted."
    Write-Warning "Only the matching system admin account is preserved. Federation admins, federations, clubs, and lofts are removed."
    $answer = Read-Host "Type FLUSH $Server to continue"
    if ($answer -ne "FLUSH $Server") {
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
PRESERVE_ADMIN_EMAIL=__PRESERVE_ADMIN_EMAIL__
SKIP_BACKUP=__SKIP_BACKUP__

if [[ "$(id -u)" != "0" ]]; then
  echo "Run this script as root on the remote host." >&2
  exit 1
fi

SHARED_DIR="$APP_ROOT/shared"
CURRENT_LINK="$APP_ROOT/current"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups"
mkdir -p "$BACKUP_DIR"

systemd_env() {
  local name="$1"
  systemctl cat "$SERVICE_NAME" 2>/dev/null |
    sed -n "s/^Environment=\"$name=\(.*\)\"$/\1/p" |
    tail -n 1 |
    sed 's/%%/%/g; s/\\"/"/g; s/\\\\/\\/g'
}

sql_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
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
    if [[ "$FLUSH_SUCCEEDED" == "true" ]]; then
      echo "Disabling seeders for clean-slate restart..."
      local override_dir="/etc/systemd/system/$SERVICE_NAME.service.d"
      mkdir -p "$override_dir"
      cat > "$override_dir/90-clean-slate-seed-disable.conf" <<'SEED_EOF'
[Service]
Environment="VLUGBOEK_SEED_REFERENCE_DATA_ENABLED=false"
Environment="VLUGBOEK_SEED_DEMO_USERS_ENABLED=false"
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
    echo "Database user is required for PostgreSQL flush." >&2
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

ADMIN_ROLE_LIST="'ADMIN','SYSTEM_ADMIN'"
ADMIN_CONDITION="role in ($ADMIN_ROLE_LIST)"
if [[ -n "$PRESERVE_ADMIN_EMAIL" ]]; then
  ADMIN_CONDITION="$ADMIN_CONDITION and lower(email) = lower('$(sql_escape "$PRESERVE_ADMIN_EMAIL")')"
fi

FLUSH_SQL="
begin;
delete from email_delivery_audits;
delete from classification_snapshots;
delete from report_cells;
delete from report_columns;
delete from report_rows;
delete from report_datasets;
delete from documents;
delete from app_users where not ($ADMIN_CONDITION);
update app_users set federation_id = null, club_id = null, loft_id = null where $ADMIN_CONDITION;
delete from lofts;
delete from clubs;
delete from federations;
commit;
"

echo "Database mode: $DATABASE_MODE"

if [[ "$DATABASE_MODE" == "postgres" ]]; then
  parse_postgres_url
  if ! command -v psql >/dev/null 2>&1; then
    apt-get update
    apt-get install -y postgresql-client
  fi

  ADMIN_COUNT="$(run_pg_scalar "select count(*) from app_users where $ADMIN_CONDITION;")"
  if [[ "$ADMIN_COUNT" == "0" ]]; then
    echo "No matching admin user found. Aborting before flush." >&2
    exit 1
  fi

  if [[ "$SKIP_BACKUP" != "true" ]]; then
    if ! command -v pg_dump >/dev/null 2>&1; then
      apt-get update
      apt-get install -y postgresql-client
    fi
    BACKUP_PATH="$BACKUP_DIR/vlugboek-postgres-before-flush-$STAMP.dump"
    echo "Writing PostgreSQL backup: $BACKUP_PATH"
    PGPASSWORD="$DB_PASSWORD" pg_dump \
      --host "$PG_HOST" \
      --port "$PG_PORT" \
      --username "$DB_USER" \
      --format custom \
      --file "$BACKUP_PATH" \
      "$PG_DATABASE"
  fi

  echo "Flushing database rows..."
  run_pg_sql "$FLUSH_SQL"
  REMAINING_ADMINS="$(run_pg_scalar "select count(*) from app_users where $ADMIN_CONDITION;")"
  REMAINING_USERS="$(run_pg_scalar "select count(*) from app_users;")"
  REMAINING_DOCUMENTS="$(run_pg_scalar "select count(*) from documents;")"
  REMAINING_FEDERATIONS="$(run_pg_scalar "select count(*) from federations;")"
  REMAINING_CLUBS="$(run_pg_scalar "select count(*) from clubs;")"
  REMAINING_LOFTS="$(run_pg_scalar "select count(*) from lofts;")"
else
  ADMIN_COUNT="$(run_h2_scalar "select count(*) from app_users where $ADMIN_CONDITION;")"
  if [[ "$ADMIN_COUNT" == "0" ]]; then
    echo "No matching admin user found. Aborting before flush." >&2
    exit 1
  fi

  if [[ "$SKIP_BACKUP" != "true" ]]; then
    BACKUP_PATH="$BACKUP_DIR/vlugboek-h2-before-flush-$STAMP.tar.gz"
    echo "Writing H2 backup: $BACKUP_PATH"
    tar -czf "$BACKUP_PATH" -C "$SHARED_DIR" data
  fi

  echo "Flushing database rows..."
  run_h2_sql "$FLUSH_SQL"
  REMAINING_ADMINS="$(run_h2_scalar "select count(*) from app_users where $ADMIN_CONDITION;")"
  REMAINING_USERS="$(run_h2_scalar "select count(*) from app_users;")"
  REMAINING_DOCUMENTS="$(run_h2_scalar "select count(*) from documents;")"
  REMAINING_FEDERATIONS="$(run_h2_scalar "select count(*) from federations;")"
  REMAINING_CLUBS="$(run_h2_scalar "select count(*) from clubs;")"
  REMAINING_LOFTS="$(run_h2_scalar "select count(*) from lofts;")"
fi

FLUSH_SUCCEEDED=true

echo "Flush complete."
echo "Admin users preserved: $REMAINING_ADMINS"
echo "Remaining users: $REMAINING_USERS"
echo "Remaining documents: $REMAINING_DOCUMENTS"
echo "Remaining federations: $REMAINING_FEDERATIONS"
echo "Remaining clubs: $REMAINING_CLUBS"
echo "Remaining lofts: $REMAINING_LOFTS"
'@

$RemoteScript = $RemoteScriptTemplate.
    Replace('__APP_ROOT__', (ConvertTo-ShellLiteral $RemoteRoot)).
    Replace('__SERVICE_NAME__', (ConvertTo-ShellLiteral $ServiceName)).
    Replace('__DATABASE_MODE__', (ConvertTo-ShellLiteral $Database)).
    Replace('__SPRING_PROFILE__', (ConvertTo-ShellLiteral $SpringProfilesActive)).
    Replace('__DB_URL__', (ConvertTo-ShellLiteral $DatabaseUrl)).
    Replace('__DB_USER__', (ConvertTo-ShellLiteral $DatabaseUser)).
    Replace('__DB_PASSWORD__', (ConvertTo-ShellLiteral $DatabasePassword)).
    Replace('__PRESERVE_ADMIN_EMAIL__', (ConvertTo-ShellLiteral $PreserveAdminEmail)).
    Replace('__SKIP_BACKUP__', (ConvertTo-ShellLiteral ($SkipBackup.IsPresent.ToString().ToLowerInvariant())))
$RemoteScript = $RemoteScript -replace "`r`n", "`n" -replace "`r", ''

if ($PSCmdlet.ShouldProcess($Target, 'Flush remote Vlugboek database to a system-admin-only clean slate')) {
    $RemoteScript | & ssh $Target 'bash -s'
    if ($LASTEXITCODE -ne 0) {
        throw 'Remote DB flush failed.'
    }
}
