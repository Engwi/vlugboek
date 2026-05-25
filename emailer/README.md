# Vlugboek Mailer

PR-06 sidecar for sending a signed-in user's requested PDF by email.

The Spring Boot API calls this service on `POST /send-document` with a bearer token, recipient address, message text, and a base64 PDF attachment. Keep this service bound to `127.0.0.1:8788` behind the main Vlugboek API.

## Local

1. Create `.env` from `.env.example` and keep `PORT=8788` and `HOST=127.0.0.1`.
2. Run `npm install`.
3. Run `npm start`.

## Production

Use `scripts/deploy-emailer-linux.ps1`. It installs the service under `/opt/vlugboekmailer`, copies the local `.env`, runs `npm ci --omit=dev`, and registers `vlugboekmailer.service`.

The Spring Boot API now writes an audit row for every email attempt before calling the sidecar, then marks it sent or failed when the sidecar responds. Keep this service private on localhost; nginx should never proxy it directly.

Preferred SMTP settings are `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_HOST`, `SMTP_PORT`, and `MAIL_FROM_ADDRESS`. The older `GMAIL_USER` and `GMAIL_APP_PASSWORD` names still work as aliases for the current production setup.

Health endpoints:

- `GET /healthz` is unauthenticated and only reports process/config shape.
- `GET /ready` requires the bearer token and verifies SMTP connectivity.
