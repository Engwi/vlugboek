#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Server = 'vlugboek.co.za',
    [string]$User = 'root',
    [string]$Domain = 'vlugboek.co.za',
    [int]$BackendPort = 18081,
    [int]$MailerPort = 8788,
    [int]$EmailDocumentId = 0,
    [string]$LoginEmail = 'admin@vlugboek.local',
    [string]$LoginPassword = 'admin123',
    [switch]$AdminChecks,
    [string]$AdminLoginEmail = 'admin@vlugboek.local',
    [string]$AdminLoginPassword = 'admin123'
)

$ErrorActionPreference = 'Stop'
$Target = "$User@$Server"
$LoginJson = @{ email = $LoginEmail; password = $LoginPassword; language = 'af' } | ConvertTo-Json -Compress
$LoginJsonBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($LoginJson))
$AdminLoginJson = @{ email = $AdminLoginEmail; password = $AdminLoginPassword; language = 'af' } | ConvertTo-Json -Compress
$AdminLoginJsonBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($AdminLoginJson))

$RemoteCommandTemplate = @'
set -Eeuo pipefail

DOMAIN="__DOMAIN__"
BACKEND_PORT="__BACKEND_PORT__"
MAILER_PORT="__MAILER_PORT__"
LOGIN_JSON="$(printf '%s' '__LOGIN_JSON_B64__' | base64 -d)"
ADMIN_LOGIN_JSON="$(printf '%s' '__ADMIN_LOGIN_JSON_B64__' | base64 -d)"

echo '--- backend service ---'
systemctl --no-pager --full status vlugboek

echo '--- backend service file (mailer token redacted) ---'
systemctl cat vlugboek | while IFS= read -r line; do
  case "$line" in
    *VLUGBOEK_MAILER_TOKEN*) echo 'Environment="VLUGBOEK_MAILER_TOKEN=<redacted>"' ;;
    *) printf '%s\n' "$line" ;;
  esac
done || true

echo '--- backend stderr ---'
tail -n 160 /opt/vlugboek/shared/logs/backend.err 2>/dev/null || true

echo '--- backend stdout ---'
tail -n 120 /opt/vlugboek/shared/logs/backend.log 2>/dev/null || true

echo '--- mailer service ---'
systemctl --no-pager --full status vlugboekmailer || true

echo '--- mailer env shape ---'
if [ -f /opt/vlugboekmailer/shared/.env ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      PORT|HOST|NODE_ENV|MAIL_JSON_LIMIT|MAIL_FROM_NAME|MAIL_FROM_ADDRESS|ALLOWED_TO_DOMAIN|SMTP_HOST|SMTP_PORT|SMTP_SECURE|SMTP_CONNECTION_TIMEOUT|SMTP_GREETING_TIMEOUT|SMTP_SOCKET_TIMEOUT)
        printf '%s=%s\n' "$key" "$value"
        ;;
      MAIL_WEBHOOK_TOKEN|SMTP_USER|SMTP_PASSWORD|GMAIL_USER|GMAIL_APP_PASSWORD)
        printf '%s=<set length %s>\n' "$key" "${#value}"
        ;;
    esac
  done < /opt/vlugboekmailer/shared/.env
else
  echo 'missing /opt/vlugboekmailer/shared/.env'
fi

echo '--- mailer logs ---'
tail -n 120 /opt/vlugboekmailer/shared/logs/mailer.err 2>/dev/null || true
tail -n 80 /opt/vlugboekmailer/shared/logs/mailer.log 2>/dev/null || true

echo '--- outbound SMTP connectivity ---'
node - <<'NODE' || true
const net = require("node:net");
const checks = [
  ["smtp.gmail.com", 587],
  ["smtp.gmail.com", 465]
];
let remaining = checks.length;
function finish() {
  remaining -= 1;
  if (remaining === 0) process.exit(0);
}
for (const [host, port] of checks) {
  const socket = net.createConnection({ host, port, timeout: 8000 }, () => {
    console.log(`${host}:${port} reachable`);
    socket.destroy();
    finish();
  });
  socket.on("timeout", () => {
    console.log(`${host}:${port} timeout`);
    socket.destroy();
    finish();
  });
  socket.on("error", (err) => {
    console.log(`${host}:${port} ${err.code || err.message}`);
    finish();
  });
}
setTimeout(() => process.exit(0), 9000);
NODE

echo '--- direct mailer health ---'
curl -i "http://127.0.0.1:$MAILER_PORT/healthz"

echo '--- direct mailer SMTP readiness ---'
MAILER_TOKEN="$(grep -E '^MAIL_WEBHOOK_TOKEN=' /opt/vlugboekmailer/shared/.env 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '\r')"
if [ -n "$MAILER_TOKEN" ]; then
  curl -i -H "Authorization: Bearer $MAILER_TOKEN" "http://127.0.0.1:$MAILER_PORT/ready" || true
else
  echo 'missing mailer token; skipping /ready'
fi

echo '--- direct backend health ---'
curl -i "http://127.0.0.1:$BACKEND_PORT/api/healthz"

echo '--- nginx health ---'
curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/healthz"
curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/api/healthz"
curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/downloads/" >/dev/null

echo '--- nginx login ---'
LOGIN_RESPONSE="$(curl -ksS -w '\n%{http_code}' --resolve "$DOMAIN:443:127.0.0.1" -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" -d "$LOGIN_JSON" "https://$DOMAIN/api/auth/login")"
LOGIN_STATUS="$(printf '%s' "$LOGIN_RESPONSE" | tail -n 1)"
LOGIN_BODY="$(printf '%s' "$LOGIN_RESPONSE" | sed '$d')"
if [ "$LOGIN_STATUS" != "200" ]; then
  echo "Login failed with HTTP $LOGIN_STATUS." >&2
  printf '%s\n' "$LOGIN_BODY" >&2
  exit 1
fi
echo 'Login ok.'
TOKEN="${LOGIN_BODY#*\"token\":\"}"
[ "$TOKEN" != "$LOGIN_BODY" ] && TOKEN="${TOKEN%%\"*}" || TOKEN=""
if [ -z "$TOKEN" ]; then
  echo 'Login response did not contain a token.' >&2
  exit 1
fi

curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $TOKEN" "https://$DOMAIN/api/dashboard" >/dev/null

__ADMIN_CHECK__

__EMAIL_CHECK__
'@

$AdminCheck = ''
if ($AdminChecks) {
    $AdminCheck = @'
echo '--- nginx admin checks ---'
ADMIN_LOGIN_RESPONSE="$(curl -ksS -w '\n%{http_code}' --resolve "$DOMAIN:443:127.0.0.1" -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" -d "$ADMIN_LOGIN_JSON" "https://$DOMAIN/api/auth/login")"
ADMIN_LOGIN_STATUS="$(printf '%s' "$ADMIN_LOGIN_RESPONSE" | tail -n 1)"
ADMIN_LOGIN_BODY="$(printf '%s' "$ADMIN_LOGIN_RESPONSE" | sed '$d')"
if [ "$ADMIN_LOGIN_STATUS" != "200" ]; then
  echo "Admin login failed with HTTP $ADMIN_LOGIN_STATUS." >&2
  printf '%s\n' "$ADMIN_LOGIN_BODY" >&2
  exit 1
fi
ADMIN_TOKEN="${ADMIN_LOGIN_BODY#*\"token\":\"}"
[ "$ADMIN_TOKEN" != "$ADMIN_LOGIN_BODY" ] && ADMIN_TOKEN="${ADMIN_TOKEN%%\"*}" || ADMIN_TOKEN=""
if [ -z "$ADMIN_TOKEN" ]; then
  echo 'Admin login response did not contain a token.' >&2
  exit 1
fi
curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $ADMIN_TOKEN" "https://$DOMAIN/api/documents" >/dev/null
curl -kfsS --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $ADMIN_TOKEN" "https://$DOMAIN/api/admin/organisations" >/dev/null
echo 'Admin checks ok.'
'@
}

$EmailCheck = ''
if ($EmailDocumentId -gt 0) {
    $EmailCheckTemplate = @'
echo '--- nginx PDF email check ---'
EMAIL_RESPONSE="$(curl -ksS -w '\n%{http_code}' --resolve "$DOMAIN:443:127.0.0.1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Origin: https://$DOMAIN" -X POST "https://$DOMAIN/api/documents/__EMAIL_DOCUMENT_ID__/email")"
EMAIL_STATUS="$(printf '%s' "$EMAIL_RESPONSE" | tail -n 1)"
EMAIL_BODY="$(printf '%s' "$EMAIL_RESPONSE" | sed '$d')"
printf '%s\n' "$EMAIL_BODY"
echo "HTTP_STATUS=$EMAIL_STATUS"
printf '%s' "$EMAIL_STATUS" | grep -Eq '^2[0-9][0-9]$'
exit 0
'@
    $EmailCheck = $EmailCheckTemplate.Replace('__EMAIL_DOCUMENT_ID__', [string]$EmailDocumentId)
}

$RemoteCommand = $RemoteCommandTemplate.Replace('__MAILER_PORT__', [string]$MailerPort).Replace('__BACKEND_PORT__', [string]$BackendPort).Replace('__DOMAIN__', $Domain).Replace('__LOGIN_JSON_B64__', $LoginJsonBase64).Replace('__ADMIN_LOGIN_JSON_B64__', $AdminLoginJsonBase64).Replace('__ADMIN_CHECK__', $AdminCheck).Replace('__EMAIL_CHECK__', $EmailCheck)
$RemoteCommand = $RemoteCommand -replace "`r`n", "`n" -replace "`r", ''

$RemoteCommand | & ssh $Target 'bash -s'
if ($LASTEXITCODE -ne 0) {
    throw 'Remote health check failed.'
}
