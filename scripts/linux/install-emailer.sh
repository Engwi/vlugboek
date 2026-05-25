#!/usr/bin/env bash
set -Eeuo pipefail

BUNDLE_PATH="${1:?Usage: install-emailer.sh <bundle.tar.gz> <release-name>}"
RELEASE_NAME="${2:-$(date +%Y%m%d-%H%M%S)}"
MAILER_ROOT="${MAILER_ROOT:-/opt/vlugboekmailer}"
SERVICE_NAME="${SERVICE_NAME:-vlugboekmailer}"
MAILER_EXPECTED_PORT="${MAILER_EXPECTED_PORT:-8788}"

if [[ "$(id -u)" != "0" ]]; then
  echo "Run this installer as root." >&2
  exit 1
fi

RELEASE_DIR="$MAILER_ROOT/releases/$RELEASE_NAME"
SHARED_DIR="$MAILER_ROOT/shared"
CURRENT_LINK="$MAILER_ROOT/current"
ENV_FILE="$SHARED_DIR/.env"

env_value() {
  local key="$1"
  grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '\r'
}

echo "Installing mailer packages..."
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y curl tar gzip nodejs

if ! command -v node >/dev/null 2>&1; then
  echo "node was not found after installing nodejs." >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm was not found. Install Node.js from NodeSource or provide npm on PATH before deploying the mailer." >&2
  exit 1
fi

echo "Preparing mailer directories..."
mkdir -p "$MAILER_ROOT/incoming" "$MAILER_ROOT/releases" "$SHARED_DIR/logs"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Mailer env file missing at $ENV_FILE. Upload one before installing." >&2
  exit 1
fi

for required_key in MAIL_WEBHOOK_TOKEN; do
  if ! grep -Eq "^${required_key}=.+" "$ENV_FILE"; then
    echo "Mailer env file is missing $required_key." >&2
    exit 1
  fi
done

if [[ -z "$(env_value SMTP_USER)" && -z "$(env_value GMAIL_USER)" ]]; then
  echo "Mailer env file needs SMTP_USER or legacy GMAIL_USER." >&2
  exit 1
fi

if [[ -z "$(env_value SMTP_PASSWORD)" && -z "$(env_value GMAIL_APP_PASSWORD)" ]]; then
  echo "Mailer env file needs SMTP_PASSWORD or legacy GMAIL_APP_PASSWORD." >&2
  exit 1
fi

HOST_VALUE="$(env_value HOST)"
HOST_VALUE="${HOST_VALUE:-127.0.0.1}"
if [[ "$HOST_VALUE" != "127.0.0.1" && "$HOST_VALUE" != "localhost" ]]; then
  echo "Mailer HOST must stay private on 127.0.0.1 or localhost. Current value: $HOST_VALUE" >&2
  exit 1
fi

PORT="$(env_value PORT)"
PORT="${PORT:-8788}"
if [[ "$PORT" != "$MAILER_EXPECTED_PORT" ]]; then
  echo "Mailer env PORT is $PORT, but Vlugboek expects $MAILER_EXPECTED_PORT." >&2
  exit 1
fi

rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

echo "Extracting mailer release..."
tar -xzf "$BUNDLE_PATH" -C "$RELEASE_DIR" --strip-components=1
ln -sfn "$RELEASE_DIR" "$CURRENT_LINK"
ln -sfn "$ENV_FILE" "$CURRENT_LINK/.env"

echo "Installing mailer dependencies..."
cd "$CURRENT_LINK"
npm ci --omit=dev

echo "Writing mailer systemd service..."
systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true

cat >"/etc/systemd/system/$SERVICE_NAME.service" <<SERVICE
[Unit]
Description=Vlugboek PDF Mailer
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$CURRENT_LINK
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/node $CURRENT_LINK/server.mjs
Restart=on-failure
RestartSec=5
StandardOutput=append:$SHARED_DIR/logs/mailer.log
StandardError=append:$SHARED_DIR/logs/mailer.err

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl reset-failed "$SERVICE_NAME" >/dev/null 2>&1 || true
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

echo "Checking mailer health..."
for attempt in $(seq 1 30); do
  if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    systemctl --no-pager --full status "$SERVICE_NAME" >&2 || true
    journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
    exit 1
  fi

  if curl -fsS "http://127.0.0.1:$PORT/healthz" | grep -q '"service"[[:space:]]*:[[:space:]]*"vlugboek-mailer"'; then
    break
  fi
  if [[ "$attempt" == "30" ]]; then
    echo "Mailer health check failed." >&2
    journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
    exit 1
  fi
  sleep 1
done

echo "Checking mailer SMTP readiness..."
MAILER_TOKEN="$(env_value MAIL_WEBHOOK_TOKEN)"
if ! curl -fsS -H "Authorization: Bearer $MAILER_TOKEN" "http://127.0.0.1:$PORT/ready" | grep -q '"smtp"[[:space:]]*:[[:space:]]*"ready"'; then
  echo "Mailer SMTP readiness check failed." >&2
  journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
  exit 1
fi

echo "Vlugboek mailer deployed successfully."
echo "Current mailer release: $RELEASE_DIR"
