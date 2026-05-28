 # Vlugboek

Vlugboek is a mobile-first pigeon racing results platform for official PDF report ingestion, result browsing, leaderboards, downloads, and email requests.

## What is included

- Spring Boot REST API on port `8081`
- H2 local database for zero-config development
- Flyway-managed H2/PostgreSQL schema migrations
- PostgreSQL-ready profile via `VLUGBOEK_DB_URL`, `VLUGBOEK_DB_USER`, and `VLUGBOEK_DB_PASSWORD`
- React + Tailwind frontend on port `5173`
- Seeded PWDF organisation data, clubs, lofts, users, and supplied PDFs under `Docs/Uitslae`
- Dynamic document datasets with ordered columns, rows, and cells
- Current leaderboard snapshots for classification reports
- Afrikaans and English UI switching

## Run locally

From `C:\Development\Vlugboek`:

```powershell
.\scripts\start-local.ps1
```

Stop it with:

```powershell
.\scripts\stop-local.ps1
```

Open:

```text
http://127.0.0.1:5173
```

The normal `npm` shim on this machine points at a missing user install, so the direct Node/npm command above is the reliable path.

The lower-level packaged-jar helper scripts are also available after `mvn package -DskipTests`:

```powershell
.\scripts\run-backend.cmd
.\scripts\run-frontend.cmd
```

## Deployment

Build a release bundle only:

```powershell
.\scripts\build-release.ps1
```

Deploy to the Ubuntu server:

```powershell
.\scripts\deploy-linux.ps1
```

Deploy against PostgreSQL:

```powershell
.\scripts\deploy-linux.ps1 `
  -SpringProfilesActive postgres `
  -DatabaseUrl 'jdbc:postgresql://127.0.0.1:5432/vlugboek' `
  -DatabaseUser 'vlugboek' `
  -DatabasePassword '<password>' `
  -SeedAdmin:$true `
  -SeedAdminPassword '<one-time-admin-password>' `
  -SeedDemoUsers:$false `
  -SeedPdfImport:$false `
  -AuthenticatedHealthCheck:$false
```

The deploy script uses OpenSSH `scp` and `ssh`. It intentionally does not store the root password in source; enter it when prompted, or use an SSH key.

What deployment does:

- builds the backend jar and frontend static assets
- bundles `app/`, `frontend/`, and `Docs/Uitslae`
- uploads the bundle to `/opt/vlugboek/incoming`
- extracts to `/opt/vlugboek/releases/<release>`
- points `/opt/vlugboek/current` at that release
- installs Java 21, nginx, curl, tar, and gzip where needed
- writes `/etc/systemd/system/vlugboek.service`
- updates `/etc/nginx/sites-available/vlugboek.co.za`
- runs the backend behind nginx on port `18081` by default
- starts/restarts the app
- checks `/api/healthz`, `/healthz`, login, and authenticated `/api/dashboard`

Database and backup guidance is in `Docs/vlugboek_database_and_backup.md`.

Remote health check:

```powershell
.\scripts\remote-health.ps1
```

## Android APK Builds

The app is Capacitor-enabled under `frontend/android`.

Prerequisites:

- Android Studio installed
- Android SDK installed
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` set, or SDK at `%LOCALAPPDATA%\Android\Sdk`
- Java 21 available

Sync the web app into Android:

```powershell
.\scripts\sync-android.ps1
```

Build debug APK:

```powershell
.\scripts\build-android-debug.ps1
```

Create a release keystore:

```powershell
.\scripts\create-android-keystore.ps1
```

Build release APK:

```powershell
.\scripts\build-android-release.ps1
```

Store release signing settings encrypted for this Windows user:

```powershell
.\scripts\save-mobile-signing-secret.ps1 -UseSamePassword
```

The existing `C:\Development\Vlugboek\vlugboekkeystore` file uses alias `key0`.
The release build validates the alias before Gradle runs and uses the only alias in the keystore when there is a mismatch.

For signed release builds, pass signing parameters or set:

```powershell
$env:VLUGBOEK_ANDROID_KEYSTORE='C:\Development\Vlugboek\mobile-keystores\vlugboek-release.jks'
$env:VLUGBOEK_ANDROID_KEY_ALIAS='vlugboek'
$env:VLUGBOEK_ANDROID_KEYSTORE_PASSWORD='<password>'
$env:VLUGBOEK_ANDROID_KEY_PASSWORD='<password>'
```

APK outputs are copied to `dist\mobile`.

Regenerate the web favicon and Android launcher icons from the hero pigeon image:

```powershell
.\scripts\generate-icons.ps1
```

Publish the mobile APK to the server download page:

```powershell
.\scripts\deploy-mobile-client.ps1 -BuildType release
```

To publish an APK that already exists:

```powershell
.\scripts\deploy-mobile-client.ps1 -ApkPath .\dist\mobile\vlugboek-debug.apk
```

The APK and download page are published under `/opt/vlugboek/shared/downloads` and are available at:

```text
https://vlugboek.co.za/downloads/
```

## Demo accounts

```text
demo@vlugboek.local / demo123
admin@vlugboek.local / admin123
```

## Build checks

```powershell
mvn test
cd frontend
node "C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js" run build
```
