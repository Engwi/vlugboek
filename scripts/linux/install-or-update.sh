#!/usr/bin/env bash
set -Eeuo pipefail

BUNDLE_PATH="${1:?Usage: install-or-update.sh <bundle.tar.gz> <release-name>}"
RELEASE_NAME="${2:-$(date +%Y%m%d-%H%M%S)}"
APP_ROOT="${APP_ROOT:-/opt/vlugboek}"
DOMAIN="${DOMAIN:-vlugboek.co.za}"
VLUGBOEK_PUBLIC_URL="${VLUGBOEK_PUBLIC_URL:-https://$DOMAIN}"
BACKEND_PORT="${BACKEND_PORT:-18081}"
SERVICE_NAME="${SERVICE_NAME:-vlugboek}"
MAILER_ROOT="${MAILER_ROOT:-/opt/vlugboekmailer}"
MAILER_URL="${MAILER_URL:-http://127.0.0.1:8788/send-document}"
MAILER_TOKEN="${MAILER_TOKEN:-}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-}"
VLUGBOEK_DATASOURCE_URL="${VLUGBOEK_DATASOURCE_URL:-jdbc:h2:file:${APP_ROOT}/shared/data/vlugboek;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE}"
VLUGBOEK_DB_URL="${VLUGBOEK_DB_URL:-}"
VLUGBOEK_DB_USER="${VLUGBOEK_DB_USER:-}"
VLUGBOEK_DB_PASSWORD="${VLUGBOEK_DB_PASSWORD:-}"
VLUGBOEK_SEED_REFERENCE_DATA_ENABLED="${VLUGBOEK_SEED_REFERENCE_DATA_ENABLED:-true}"
VLUGBOEK_SEED_PDF_IMPORT_ENABLED="${VLUGBOEK_SEED_PDF_IMPORT_ENABLED:-true}"
VLUGBOEK_SEED_ADMIN_ENABLED="${VLUGBOEK_SEED_ADMIN_ENABLED:-true}"
VLUGBOEK_SEED_ADMIN_EMAIL="${VLUGBOEK_SEED_ADMIN_EMAIL:-admin@vlugboek.local}"
VLUGBOEK_SEED_ADMIN_NAME="${VLUGBOEK_SEED_ADMIN_NAME:-Admin}"
VLUGBOEK_SEED_ADMIN_PASSWORD="${VLUGBOEK_SEED_ADMIN_PASSWORD:-admin123}"
VLUGBOEK_SEED_DEMO_USERS_ENABLED="${VLUGBOEK_SEED_DEMO_USERS_ENABLED:-true}"
VLUGBOEK_SEED_DEMO_EMAIL="${VLUGBOEK_SEED_DEMO_EMAIL:-demo@vlugboek.local}"
VLUGBOEK_SEED_DEMO_NAME="${VLUGBOEK_SEED_DEMO_NAME:-Demo Fancier}"
VLUGBOEK_SEED_DEMO_PASSWORD="${VLUGBOEK_SEED_DEMO_PASSWORD:-demo123}"
AUTH_HEALTH_CHECK="${AUTH_HEALTH_CHECK:-true}"
HEALTH_LOGIN_EMAIL="${HEALTH_LOGIN_EMAIL:-admin@vlugboek.local}"
HEALTH_LOGIN_PASSWORD="${HEALTH_LOGIN_PASSWORD:-admin123}"
ADMIN_HEALTH_CHECK="${ADMIN_HEALTH_CHECK:-false}"
HEALTH_ADMIN_EMAIL="${HEALTH_ADMIN_EMAIL:-admin@vlugboek.local}"
HEALTH_ADMIN_PASSWORD="${HEALTH_ADMIN_PASSWORD:-admin123}"
ROLLBACK_ON_FAILURE="${ROLLBACK_ON_FAILURE:-true}"

if [[ -z "$MAILER_TOKEN" ]]; then
  for candidate in "$MAILER_ROOT/shared/.env" "$MAILER_ROOT/.env"; do
    if [[ -f "$candidate" ]]; then
      MAILER_TOKEN="$(grep -E '^MAIL_WEBHOOK_TOKEN=' "$candidate" | tail -n 1 | cut -d= -f2- | tr -d '\r')"
      if [[ -n "$MAILER_TOKEN" ]]; then
        break
      fi
    fi
  done
fi

if [[ "$(id -u)" != "0" ]]; then
  echo "Run this installer as root." >&2
  exit 1
fi

RELEASE_DIR="$APP_ROOT/releases/$RELEASE_NAME"
SHARED_DIR="$APP_ROOT/shared"
CURRENT_LINK="$APP_ROOT/current"
PREVIOUS_LINK="$APP_ROOT/previous"
PREVIOUS_RELEASE_DIR="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"
ROLLBACK_ARMED=false

systemd_env_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//%/%%}"
  printf '%s' "$value"
}

write_systemd_env() {
  local name="$1"
  local value="$2"
  printf 'Environment="%s=%s"\n' "$name" "$(systemd_env_value "$value")"
}

print_service_diagnostics() {
  echo "Service status:" >&2
  systemctl --no-pager --full status "$SERVICE_NAME" >&2 || true

  echo "Recent systemd journal:" >&2
  journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true

  if [[ -f "$SHARED_DIR/logs/backend.err" ]]; then
    echo "Recent backend stderr:" >&2
    tail -n 160 "$SHARED_DIR/logs/backend.err" >&2 || true
  fi

  if [[ -f "$SHARED_DIR/logs/backend.log" ]]; then
    echo "Recent backend stdout:" >&2
    tail -n 120 "$SHARED_DIR/logs/backend.log" >&2 || true
  fi
}

link_shared_downloads() {
  if [[ -d "$CURRENT_LINK/frontend" ]]; then
    if [[ -e "$CURRENT_LINK/frontend/downloads" && ! -L "$CURRENT_LINK/frontend/downloads" ]]; then
      echo "$CURRENT_LINK/frontend/downloads exists and is not a symlink; leaving shared downloads unlinked." >&2
    else
      ln -sfnT "$SHARED_DIR/downloads" "$CURRENT_LINK/frontend/downloads"
    fi
  fi
}

rollback_release() {
  local reason="$1"
  if [[ "$ROLLBACK_ON_FAILURE" != "true" ]]; then
    echo "Automatic rollback disabled; leaving attempted release in place." >&2
    return 1
  fi
  if [[ -z "$PREVIOUS_RELEASE_DIR" || ! -d "$PREVIOUS_RELEASE_DIR" || "$PREVIOUS_RELEASE_DIR" == "$RELEASE_DIR" ]]; then
    echo "No previous release is available for rollback." >&2
    return 1
  fi

  echo "Rolling back because: $reason" >&2
  echo "Previous release: $PREVIOUS_RELEASE_DIR" >&2
  ln -sfn "$PREVIOUS_RELEASE_DIR" "$CURRENT_LINK"
  link_shared_downloads
  systemctl restart "$SERVICE_NAME" >/dev/null 2>&1 || true
  systemctl reload nginx >/dev/null 2>&1 || true

  local rollback_health_url="http://127.0.0.1:$BACKEND_PORT/api/healthz"
  for attempt in $(seq 1 20); do
    if curl -fsS "$rollback_health_url" >/dev/null 2>&1; then
      echo "Rollback health check passed." >&2
      return 0
    fi
    sleep 1
  done

  echo "Rollback was attempted, but the previous release did not pass health checks." >&2
  print_service_diagnostics
  return 1
}

fail_deploy() {
  local message="$1"
  echo "$message" >&2
  rollback_release "$message" || true
  exit 1
}

on_error() {
  local line="$1"
  local status="$2"
  if [[ "$ROLLBACK_ARMED" == "true" ]]; then
    rollback_release "installer failed at line $line with exit code $status" || true
  fi
}

trap 'on_error "$LINENO" "$?"' ERR

echo "Installing packages..."
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y nginx curl tar gzip iproute2 procps
if [[ "$SPRING_PROFILES_ACTIVE" == *postgres* ]]; then
  apt-get install -y postgresql-client
fi
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q 'version "21'; then
  apt-get install -y openjdk-21-jre-headless
fi

echo "Preparing directories..."
mkdir -p "$APP_ROOT/incoming" "$APP_ROOT/releases" "$SHARED_DIR/data" "$SHARED_DIR/uploads" "$SHARED_DIR/logs" "$SHARED_DIR/tmp" "$SHARED_DIR/downloads"
if [[ ! -f "$SHARED_DIR/downloads/index.html" ]]; then
  cat >"$SHARED_DIR/downloads/index.html" <<DOWNLOADS
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Vlugboek Android Downloads</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 24px;
      background: #f8f6f1;
      color: #182331;
      font-family: "Segoe UI", Arial, sans-serif;
    }
    main {
      width: min(640px, 100%);
      padding: 24px;
      border: 1px solid #d9d2c5;
      border-radius: 8px;
      background: white;
    }
    h1 {
      margin: 0 0 12px;
      color: #0b1623;
      font: 700 2.2rem Georgia, "Times New Roman", serif;
    }
    p {
      margin: 0;
      line-height: 1.55;
    }
  </style>
</head>
<body>
  <main>
    <h1>Vlugboek Android</h1>
    <p>The Android APK has not been published yet. Run the mobile deploy script to publish the download.</p>
  </main>
</body>
</html>
DOWNLOADS
fi
chmod 755 "$APP_ROOT" "$SHARED_DIR" "$SHARED_DIR/downloads"
chmod 644 "$SHARED_DIR/downloads/index.html"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

echo "Extracting release..."
tar -xzf "$BUNDLE_PATH" -C "$RELEASE_DIR" --strip-components=1
if [[ -n "$PREVIOUS_RELEASE_DIR" && -d "$PREVIOUS_RELEASE_DIR" && "$PREVIOUS_RELEASE_DIR" != "$RELEASE_DIR" ]]; then
  ln -sfn "$PREVIOUS_RELEASE_DIR" "$PREVIOUS_LINK"
fi
ln -sfn "$RELEASE_DIR" "$CURRENT_LINK"
link_shared_downloads
ROLLBACK_ARMED=true

echo "Writing systemd service..."
systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true
if ss -ltnp "sport = :$BACKEND_PORT" 2>/dev/null | grep -q LISTEN; then
  echo "Port $BACKEND_PORT is already occupied after stopping $SERVICE_NAME." >&2
  echo "Choose another port with deploy-linux.ps1 -BackendPort <port>, or stop the process below:" >&2
  ss -ltnp "sport = :$BACKEND_PORT" >&2 || true
  fail_deploy "Port $BACKEND_PORT is already occupied after stopping $SERVICE_NAME."
fi

{
cat <<SERVICE
[Unit]
Description=Vlugboek Spring Boot API
After=network.target
StartLimitIntervalSec=120
StartLimitBurst=6

[Service]
Type=simple
User=root
WorkingDirectory=$CURRENT_LINK
SERVICE
write_systemd_env "SERVER_PORT" "$BACKEND_PORT"
if [[ -n "$SPRING_PROFILES_ACTIVE" ]]; then
  write_systemd_env "SPRING_PROFILES_ACTIVE" "$SPRING_PROFILES_ACTIVE"
fi
write_systemd_env "VLUGBOEK_DATASOURCE_URL" "$VLUGBOEK_DATASOURCE_URL"
if [[ -n "$VLUGBOEK_DB_URL" ]]; then
  write_systemd_env "VLUGBOEK_DB_URL" "$VLUGBOEK_DB_URL"
fi
if [[ -n "$VLUGBOEK_DB_USER" ]]; then
  write_systemd_env "VLUGBOEK_DB_USER" "$VLUGBOEK_DB_USER"
fi
if [[ -n "$VLUGBOEK_DB_PASSWORD" ]]; then
  write_systemd_env "VLUGBOEK_DB_PASSWORD" "$VLUGBOEK_DB_PASSWORD"
fi
write_systemd_env "VLUGBOEK_H2_CONSOLE_ENABLED" "false"
write_systemd_env "VLUGBOEK_PUBLIC_URL" "$VLUGBOEK_PUBLIC_URL"
write_systemd_env "VLUGBOEK_UPLOADS_DIR" "$SHARED_DIR/uploads"
write_systemd_env "VLUGBOEK_SEED_PDF_ROOT" "$CURRENT_LINK/Docs/Uitslae"
write_systemd_env "VLUGBOEK_SEED_REFERENCE_DATA_ENABLED" "$VLUGBOEK_SEED_REFERENCE_DATA_ENABLED"
write_systemd_env "VLUGBOEK_SEED_PDF_IMPORT_ENABLED" "$VLUGBOEK_SEED_PDF_IMPORT_ENABLED"
write_systemd_env "VLUGBOEK_SEED_ADMIN_ENABLED" "$VLUGBOEK_SEED_ADMIN_ENABLED"
write_systemd_env "VLUGBOEK_SEED_ADMIN_EMAIL" "$VLUGBOEK_SEED_ADMIN_EMAIL"
write_systemd_env "VLUGBOEK_SEED_ADMIN_NAME" "$VLUGBOEK_SEED_ADMIN_NAME"
write_systemd_env "VLUGBOEK_SEED_ADMIN_PASSWORD" "$VLUGBOEK_SEED_ADMIN_PASSWORD"
write_systemd_env "VLUGBOEK_SEED_DEMO_USERS_ENABLED" "$VLUGBOEK_SEED_DEMO_USERS_ENABLED"
write_systemd_env "VLUGBOEK_SEED_DEMO_EMAIL" "$VLUGBOEK_SEED_DEMO_EMAIL"
write_systemd_env "VLUGBOEK_SEED_DEMO_NAME" "$VLUGBOEK_SEED_DEMO_NAME"
write_systemd_env "VLUGBOEK_SEED_DEMO_PASSWORD" "$VLUGBOEK_SEED_DEMO_PASSWORD"
write_systemd_env "VLUGBOEK_MAILER_URL" "$MAILER_URL"
write_systemd_env "VLUGBOEK_MAILER_TOKEN" "$MAILER_TOKEN"
cat <<SERVICE
ExecStart=/usr/bin/java -Djava.io.tmpdir=$SHARED_DIR/tmp -jar $CURRENT_LINK/app/vlugboek.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
StandardOutput=append:$SHARED_DIR/logs/backend.log
StandardError=append:$SHARED_DIR/logs/backend.err

[Install]
WantedBy=multi-user.target
SERVICE
} >"/etc/systemd/system/$SERVICE_NAME.service"

systemctl daemon-reload
systemctl reset-failed "$SERVICE_NAME" >/dev/null 2>&1 || true
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

echo "Writing nginx site..."
if [[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" && -f "/etc/letsencrypt/live/$DOMAIN/privkey.pem" ]]; then
  cat >"/etc/nginx/sites-available/$DOMAIN" <<NGINX
server {
    listen 443 ssl http2;
    server_name $DOMAIN www.$DOMAIN;

    root $CURRENT_LINK/frontend;
    index index.html;
    client_max_body_size 30m;
    add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; worker-src 'self' blob:; manifest-src 'self'; upgrade-insecure-requests" always;
    add_header Strict-Transport-Security "max-age=15768000" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;

    location = /sw.js {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /manifest.webmanifest {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /site.webmanifest {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /downloads {
        return 301 /downloads/;
    }

    location /downloads/ {
        alias $SHARED_DIR/downloads/;
        index index.html;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:$BACKEND_PORT/api/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port \$server_port;
    }

    location = /healthz {
        add_header Content-Type text/plain;
        return 200 "ok\\n";
    }

    location /h2-console {
        return 404;
    }

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
}

server {
    listen 80;
    server_name $DOMAIN www.$DOMAIN;
    return 301 https://\$host\$request_uri;
}
NGINX
else
  cat >"/etc/nginx/sites-available/$DOMAIN" <<NGINX
server {
    listen 80;
    server_name $DOMAIN www.$DOMAIN;

    root $CURRENT_LINK/frontend;
    index index.html;
    client_max_body_size 30m;
    add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; worker-src 'self' blob:; manifest-src 'self'" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;

    location = /sw.js {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /manifest.webmanifest {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /site.webmanifest {
        add_header Cache-Control "no-cache";
        try_files \$uri =404;
    }

    location = /downloads {
        return 301 /downloads/;
    }

    location /downloads/ {
        alias $SHARED_DIR/downloads/;
        index index.html;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:$BACKEND_PORT/api/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port \$server_port;
    }

    location = /healthz {
        add_header Content-Type text/plain;
        return 200 "ok\\n";
    }

    location /h2-console {
        return 404;
    }
}
NGINX
fi

ln -sfn "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
nginx -t
systemctl reload nginx

echo "Running health checks..."
HEALTH_URL="http://127.0.0.1:$BACKEND_PORT/api/healthz"
for attempt in $(seq 1 40); do
  if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    print_service_diagnostics
    fail_deploy "$SERVICE_NAME is not active."
  fi

  RESPONSE="$(curl -s -w '\n%{http_code}' "$HEALTH_URL" 2>/dev/null || true)"
  STATUS_CODE="$(printf '%s' "$RESPONSE" | tail -n 1)"
  BODY="$(printf '%s' "$RESPONSE" | sed '$d')"
  if [[ -z "$STATUS_CODE" ]]; then
    STATUS_CODE="000"
  fi

  if [[ "$STATUS_CODE" == "200" ]] && printf '%s' "$BODY" | grep -q '"service"[[:space:]]*:[[:space:]]*"vlugboek"'; then
    break
  fi

  if [[ "$STATUS_CODE" == "401" ]]; then
    echo "Health endpoint returned 401 from $HEALTH_URL." >&2
    echo "That usually means this port is being served by another app or an auth layer, not the Vlugboek API." >&2
    echo "Response body:" >&2
    printf '%s\n' "$BODY" >&2
    echo "Listening process:" >&2
    ss -ltnp "sport = :$BACKEND_PORT" >&2 || true
    print_service_diagnostics
    fail_deploy "Health endpoint returned 401 from $HEALTH_URL."
  fi

  if [[ "$attempt" == "40" ]]; then
    print_service_diagnostics
    echo "Backend health check failed. Last status from $HEALTH_URL: $STATUS_CODE" >&2
    printf '%s\n' "$BODY" >&2
    fail_deploy "Backend health check failed."
  fi
  sleep 2
done

if [[ -n "$MAILER_TOKEN" ]]; then
  MAILER_HEALTH_URL="${MAILER_URL%/send-document}/healthz"
  echo "Checking mailer health..."
  if ! curl -fsS "$MAILER_HEALTH_URL" | grep -q '"service"[[:space:]]*:[[:space:]]*"vlugboek-mailer"'; then
    echo "Mailer health check failed at $MAILER_HEALTH_URL." >&2
    echo "Run scripts/deploy-emailer-linux.ps1 first, then rerun deploy-linux.ps1." >&2
    fail_deploy "Mailer health check failed."
  fi
else
  echo "Mailer token was not found in $MAILER_ROOT/shared/.env." >&2
  echo "Run scripts/deploy-emailer-linux.ps1 first, then rerun deploy-linux.ps1." >&2
  fail_deploy "Mailer token was not found."
fi

check_login() {
  local label="$1"
  local url="$2"
  shift 2
  local login_body
  local response status_code body
  printf -v login_body '{"email":"%s","password":"%s","language":"en"}' "$HEALTH_LOGIN_EMAIL" "$HEALTH_LOGIN_PASSWORD"

  response="$(curl -sS -w '\n%{http_code}' "$@" \
    -H 'Content-Type: application/json' \
    -d "$login_body" \
    "$url" || true)"
  status_code="$(printf '%s' "$response" | tail -n 1)"
  body="$(printf '%s' "$response" | sed '$d')"

  if [[ "$status_code" != "200" ]] || ! printf '%s' "$body" | grep -q '"token"'; then
    echo "$label login check failed with HTTP $status_code." >&2
    printf '%s\n' "$body" >&2
    return 1
  fi

  printf '%s' "$body" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

USER_HEALTH_LOGIN_EMAIL="$HEALTH_LOGIN_EMAIL"
USER_HEALTH_LOGIN_PASSWORD="$HEALTH_LOGIN_PASSWORD"

if [[ "$AUTH_HEALTH_CHECK" == "true" ]]; then
  echo "Checking backend login..."
  if ! DIRECT_TOKEN="$(check_login "Direct backend" "http://127.0.0.1:$BACKEND_PORT/api/auth/login")"; then
    fail_deploy "Direct backend login health check failed."
  fi
  echo "Checking authenticated backend dashboard..."
  curl -fsS -H "Authorization: Bearer $DIRECT_TOKEN" "http://127.0.0.1:$BACKEND_PORT/api/dashboard" >/dev/null
else
  echo "Authenticated health checks are disabled."
fi

if [[ "$ADMIN_HEALTH_CHECK" == "true" ]]; then
  HEALTH_LOGIN_EMAIL="$HEALTH_ADMIN_EMAIL"
  HEALTH_LOGIN_PASSWORD="$HEALTH_ADMIN_PASSWORD"
  echo "Checking backend admin login..."
  if ! DIRECT_ADMIN_TOKEN="$(check_login "Direct backend admin" "http://127.0.0.1:$BACKEND_PORT/api/auth/login")"; then
    fail_deploy "Direct backend admin login health check failed."
  fi
  echo "Checking backend admin documents..."
  curl -fsS -H "Authorization: Bearer $DIRECT_ADMIN_TOKEN" "http://127.0.0.1:$BACKEND_PORT/api/documents" >/dev/null
  echo "Checking backend admin organisations..."
  curl -fsS -H "Authorization: Bearer $DIRECT_ADMIN_TOKEN" "http://127.0.0.1:$BACKEND_PORT/api/admin/organisations" >/dev/null
  HEALTH_LOGIN_EMAIL="$USER_HEALTH_LOGIN_EMAIL"
  HEALTH_LOGIN_PASSWORD="$USER_HEALTH_LOGIN_PASSWORD"
fi

if [[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
  echo "Checking nginx HTTPS /healthz..."
  curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/healthz" >/dev/null
  echo "Checking nginx HTTPS /api/healthz..."
  curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/api/healthz" >/dev/null
  if [[ "$AUTH_HEALTH_CHECK" == "true" ]]; then
    echo "Checking nginx HTTPS login..."
    if ! NGINX_TOKEN="$(check_login "Nginx HTTPS" "https://$DOMAIN/api/auth/login" -k --resolve "$DOMAIN:443:127.0.0.1" -H "Origin: https://$DOMAIN")"; then
      fail_deploy "Nginx HTTPS login health check failed."
    fi
    echo "Checking nginx HTTPS authenticated dashboard..."
    curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $NGINX_TOKEN" "https://$DOMAIN/api/dashboard" >/dev/null
  fi
  if [[ "$ADMIN_HEALTH_CHECK" == "true" ]]; then
    HEALTH_LOGIN_EMAIL="$HEALTH_ADMIN_EMAIL"
    HEALTH_LOGIN_PASSWORD="$HEALTH_ADMIN_PASSWORD"
    echo "Checking nginx HTTPS admin login..."
    if ! NGINX_ADMIN_TOKEN="$(check_login "Nginx HTTPS admin" "https://$DOMAIN/api/auth/login" -k --resolve "$DOMAIN:443:127.0.0.1" -H "Origin: https://$DOMAIN")"; then
      fail_deploy "Nginx HTTPS admin login health check failed."
    fi
    echo "Checking nginx HTTPS admin documents..."
    curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $NGINX_ADMIN_TOKEN" "https://$DOMAIN/api/documents" >/dev/null
    echo "Checking nginx HTTPS admin organisations..."
    curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $NGINX_ADMIN_TOKEN" "https://$DOMAIN/api/admin/organisations" >/dev/null
    HEALTH_LOGIN_EMAIL="$USER_HEALTH_LOGIN_EMAIL"
    HEALTH_LOGIN_PASSWORD="$USER_HEALTH_LOGIN_PASSWORD"
  fi
else
  echo "Checking nginx HTTP /healthz..."
  curl -fsS -H "Host: $DOMAIN" "http://127.0.0.1/healthz" >/dev/null
  echo "Checking nginx HTTP /api/healthz..."
  curl -fsS -H "Host: $DOMAIN" "http://127.0.0.1/api/healthz" >/dev/null
  if [[ "$AUTH_HEALTH_CHECK" == "true" ]]; then
    echo "Checking nginx HTTP login..."
    if ! NGINX_TOKEN="$(check_login "Nginx HTTP" "http://127.0.0.1/api/auth/login" -H "Host: $DOMAIN" -H "Origin: http://$DOMAIN")"; then
      fail_deploy "Nginx HTTP login health check failed."
    fi
    echo "Checking nginx HTTP authenticated dashboard..."
    curl -fsS -H "Host: $DOMAIN" -H "Authorization: Bearer $NGINX_TOKEN" "http://127.0.0.1/api/dashboard" >/dev/null
  fi
  if [[ "$ADMIN_HEALTH_CHECK" == "true" ]]; then
    HEALTH_LOGIN_EMAIL="$HEALTH_ADMIN_EMAIL"
    HEALTH_LOGIN_PASSWORD="$HEALTH_ADMIN_PASSWORD"
    echo "Checking nginx HTTP admin login..."
    if ! NGINX_ADMIN_TOKEN="$(check_login "Nginx HTTP admin" "http://127.0.0.1/api/auth/login" -H "Host: $DOMAIN" -H "Origin: http://$DOMAIN")"; then
      fail_deploy "Nginx HTTP admin login health check failed."
    fi
    echo "Checking nginx HTTP admin documents..."
    curl -fsS -H "Host: $DOMAIN" -H "Authorization: Bearer $NGINX_ADMIN_TOKEN" "http://127.0.0.1/api/documents" >/dev/null
    echo "Checking nginx HTTP admin organisations..."
    curl -fsS -H "Host: $DOMAIN" -H "Authorization: Bearer $NGINX_ADMIN_TOKEN" "http://127.0.0.1/api/admin/organisations" >/dev/null
    HEALTH_LOGIN_EMAIL="$USER_HEALTH_LOGIN_EMAIL"
    HEALTH_LOGIN_PASSWORD="$USER_HEALTH_LOGIN_PASSWORD"
  fi
fi

ROLLBACK_ARMED=false
echo "Vlugboek deployed successfully."
echo "Current release: $RELEASE_DIR"
