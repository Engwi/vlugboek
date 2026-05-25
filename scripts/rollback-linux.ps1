#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$RemoteRoot = '/opt/vlugboek',
    [string]$Domain = 'vlugboek.co.za',
    [int]$BackendPort = 18081,
    [string]$ServiceName = 'vlugboek',
    [string]$ReleaseName
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

$releaseLiteral = if ($ReleaseName) { ConvertTo-ShellLiteral $ReleaseName } else { "''" }
$remoteScript = @"
set -Eeuo pipefail

APP_ROOT=$(ConvertTo-ShellLiteral $RemoteRoot)
DOMAIN=$(ConvertTo-ShellLiteral $Domain)
BACKEND_PORT=$(ConvertTo-ShellLiteral ([string]$BackendPort))
SERVICE_NAME=$(ConvertTo-ShellLiteral $ServiceName)
REQUESTED_RELEASE=$releaseLiteral
CURRENT_LINK="`$APP_ROOT/current"
PREVIOUS_LINK="`$APP_ROOT/previous"
SHARED_DIR="`$APP_ROOT/shared"

if [ -n "`$REQUESTED_RELEASE" ]; then
  TARGET_RELEASE="`$APP_ROOT/releases/`$REQUESTED_RELEASE"
else
  TARGET_RELEASE="`$(readlink -f "`$PREVIOUS_LINK" 2>/dev/null || true)"
fi

if [ -z "`$TARGET_RELEASE" ] || [ ! -d "`$TARGET_RELEASE" ]; then
  echo "Rollback target was not found: `$TARGET_RELEASE" >&2
  echo "Available releases:" >&2
  ls -1 "`$APP_ROOT/releases" >&2 || true
  exit 1
fi

CURRENT_TARGET="`$(readlink -f "`$CURRENT_LINK" 2>/dev/null || true)"
if [ -n "`$CURRENT_TARGET" ] && [ "`$CURRENT_TARGET" != "`$TARGET_RELEASE" ]; then
  ln -sfn "`$CURRENT_TARGET" "`$PREVIOUS_LINK"
fi

echo "Rolling back to `$TARGET_RELEASE"
ln -sfn "`$TARGET_RELEASE" "`$CURRENT_LINK"
if [ -d "`$CURRENT_LINK/frontend" ]; then
  if [ -e "`$CURRENT_LINK/frontend/downloads" ] && [ ! -L "`$CURRENT_LINK/frontend/downloads" ]; then
    echo "`$CURRENT_LINK/frontend/downloads exists and is not a symlink; leaving shared downloads unlinked." >&2
  else
    ln -sfnT "`$SHARED_DIR/downloads" "`$CURRENT_LINK/frontend/downloads"
  fi
fi

systemctl restart "`$SERVICE_NAME"

HEALTH_URL="http://127.0.0.1:`$BACKEND_PORT/api/healthz"
for attempt in `$(seq 1 30); do
  if curl -fsS "`$HEALTH_URL" | grep -q '"service"[[:space:]]*:[[:space:]]*"vlugboek"'; then
    break
  fi
  if [ "`$attempt" = "30" ]; then
    systemctl --no-pager --full status "`$SERVICE_NAME" >&2 || true
    tail -n 120 "`$SHARED_DIR/logs/backend.log" >&2 || true
    tail -n 120 "`$SHARED_DIR/logs/backend.err" >&2 || true
    echo "Rollback health check failed." >&2
    exit 1
  fi
  sleep 2
done

if [ -f "/etc/letsencrypt/live/`$DOMAIN/fullchain.pem" ]; then
  curl -kfsS --resolve "`$DOMAIN:443:127.0.0.1" "https://`$DOMAIN/api/healthz" >/dev/null
else
  curl -fsS -H "Host: `$DOMAIN" "http://127.0.0.1/api/healthz" >/dev/null
fi

echo "Rollback complete."
echo "Current release: `$TARGET_RELEASE"
"@

$remoteScript = $remoteScript -replace "`r`n", "`n" -replace "`r", ''
$remoteScript | & ssh $Target 'bash -s'
if ($LASTEXITCODE -ne 0) {
    throw 'Remote rollback failed.'
}
