# Vlugboek Database And Backup Runbook

This runbook covers the MVP data posture after PR-08.

## Runtime Modes

Vlugboek now uses Flyway migrations for schema creation. Existing H2 databases are protected by Flyway baseline-on-migrate, so the current production database can keep running without being recreated.

### Current H2 Mode

Default local and existing production mode:

```text
SPRING_PROFILES_ACTIVE=
VLUGBOEK_DATASOURCE_URL=jdbc:h2:file:/opt/vlugboek/shared/data/vlugboek;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE
```

Data that must be backed up:

- `/opt/vlugboek/shared/data`
- `/opt/vlugboek/shared/uploads`
- `/opt/vlugboek/shared/downloads`
- `/opt/vlugboekmailer/shared/.env`

For a consistent H2 backup, stop the Spring Boot service first:

```bash
systemctl stop vlugboek
mkdir -p /opt/vlugboek/backups
tar -czf /opt/vlugboek/backups/vlugboek-h2-$(date +%Y%m%d-%H%M%S).tar.gz \
  -C /opt/vlugboek/shared data uploads downloads
systemctl start vlugboek
```

Copy the backup off the server:

```powershell
scp root@vlugboek.co.za:/opt/vlugboek/backups/vlugboek-h2-*.tar.gz .\dist\backups\
```

## PostgreSQL Profile

The PostgreSQL profile is intended for the hardened production database path:

```powershell
.\scripts\deploy-linux.ps1 `
  -SpringProfilesActive postgres `
  -DatabaseUrl 'jdbc:postgresql://127.0.0.1:5432/vlugboek' `
  -DatabaseUser 'vlugboek' `
  -DatabasePassword '<password>' `
  -SeedAdmin:$true `
  -SeedAdminEmail 'admin@vlugboek.co.za' `
  -SeedAdminPassword '<one-time-admin-password>' `
  -SeedDemoUsers:$false `
  -SeedPdfImport:$false `
  -AuthenticatedHealthCheck:$false
```

PostgreSQL profile defaults are production-safe:

- reference federation/club/loft data is enabled
- PDF import seeding is disabled
- demo user seeding is disabled
- admin seeding is disabled unless explicitly enabled

After the first admin account is created, deploy again with admin seeding disabled:

```powershell
.\scripts\deploy-linux.ps1 `
  -SpringProfilesActive postgres `
  -DatabaseUrl 'jdbc:postgresql://127.0.0.1:5432/vlugboek' `
  -DatabaseUser 'vlugboek' `
  -DatabasePassword '<password>' `
  -SeedAdmin:$false `
  -SeedDemoUsers:$false `
  -SeedPdfImport:$false `
  -AuthenticatedHealthCheck:$false
```

If you have a known login account and want authenticated deploy checks:

```powershell
.\scripts\deploy-linux.ps1 `
  -SpringProfilesActive postgres `
  -DatabaseUrl 'jdbc:postgresql://127.0.0.1:5432/vlugboek' `
  -DatabaseUser 'vlugboek' `
  -DatabasePassword '<password>' `
  -SeedAdmin:$false `
  -SeedDemoUsers:$false `
  -SeedPdfImport:$false `
  -HealthLoginEmail '<email>' `
  -HealthLoginPassword '<password>'
```

### PostgreSQL Backup

Use `pg_dump` for the database and `tar` for uploaded files:

```bash
mkdir -p /opt/vlugboek/backups
PGPASSWORD='<password>' pg_dump \
  --host 127.0.0.1 \
  --port 5432 \
  --username vlugboek \
  --format custom \
  --file /opt/vlugboek/backups/vlugboek-postgres-$(date +%Y%m%d-%H%M%S).dump \
  vlugboek

tar -czf /opt/vlugboek/backups/vlugboek-files-$(date +%Y%m%d-%H%M%S).tar.gz \
  -C /opt/vlugboek/shared uploads downloads
```

Restore order:

1. Restore PostgreSQL with `pg_restore`.
2. Restore `/opt/vlugboek/shared/uploads`.
3. Restore `/opt/vlugboek/shared/downloads`.
4. Deploy the matching or newer application release.

## Seed Switches

The deploy script writes these values into the systemd service:

```text
VLUGBOEK_SEED_REFERENCE_DATA_ENABLED
VLUGBOEK_SEED_PDF_IMPORT_ENABLED
VLUGBOEK_SEED_ADMIN_ENABLED
VLUGBOEK_SEED_ADMIN_EMAIL
VLUGBOEK_SEED_ADMIN_NAME
VLUGBOEK_SEED_ADMIN_PASSWORD
VLUGBOEK_SEED_DEMO_USERS_ENABLED
VLUGBOEK_SEED_DEMO_EMAIL
VLUGBOEK_SEED_DEMO_NAME
VLUGBOEK_SEED_DEMO_PASSWORD
```

Seed operations are repeatable. Existing records are found before new rows are created.
