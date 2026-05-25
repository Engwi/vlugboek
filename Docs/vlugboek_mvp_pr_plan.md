# Vlugboek MVP PR Plan

This is the working PR sequence for locking down the MVP. Each PR should leave the app deployable.

## PR-01 Security Foundation

Status: complete.

- Add Spring Security with stateless token authentication.
- Replace plain SHA-256 password hashing with BCrypt while allowing legacy hashes to migrate on login.
- Require authentication for result/report APIs.
- Restrict PDF upload to admins.
- Send auth tokens from the web and mobile frontend.
- Keep health, login, registration, and organisation lookup available without a token.

## PR-02 Admin Import Confirmation

Status: complete for the MVP confirmation path. Full failed-upload reprocess tooling remains tied to PR-03 parser hardening.

- Split upload from import: upload, recognise, preview, confirm.
- Store pending imports and only publish after admin confirmation.
- Add failed/reprocess states.
- Make the admin upload screen show recognised type, official date, column preview, and row count.

## PR-03 Real PDF Recognition And Parsing

Status: complete for the supplied MVP PDF set.

- Parse PDF text/content instead of synthesising rows.
- Recognise report family and dates from PDF content first, filename second.
- Preserve columns, order, rows, values, and source PDF.
- Add supplied PDFs as parser fixtures with regression tests.
- Covered layouts: PWDF race detail, overall points, distance logs, and GWC combine results.

## PR-04 Result Metadata And Filtering

Status: complete for the MVP filter path. Club/loft metadata is now indexed and filterable; user access is scoped at federation level until PR-07 aligns organisation administration with official report naming.

- Persist searchable metadata for federation, club, loft, race point, category, and official date.
- Add API filters for date range, report family, category, club, loft, and race title.
- Scope non-admin users to their federation/club/loft where applicable.
- Add matching UI filters without crowding the mobile layout.

## PR-05 Mobile Result UX

Status: complete for the MVP mobile result path.

- Tighten the three-tap journey to current results.
- Improve empty, loading, forbidden, and failed-import states.
- Add table-in-report search and stronger card view for phones.
- Hide admin-only actions for normal users.

## PR-06 Email Delivery

Status: hardened for MVP with a private sidecar mailer. The Spring Boot API now keeps an audit row for every email attempt, records sent/failed status, and returns delivery identifiers. The sidecar remains the recommended MVP mailer boundary.

- Add SMTP configuration with generic `SMTP_*` settings while preserving the current Gmail aliases.
- Email PDFs to the signed-in user through the localhost-only sidecar.
- Add delivery status responses, request ids, and safer error handling.
- Keep an audit trail of email requests.
- Add mailer health and authenticated SMTP readiness checks to deployment diagnostics.

## PR-07 Organisation Admin

Status: complete for MVP organisation control.

- Add admin CRUD for federations, clubs, and lofts.
- Prevent free-typed organisation details during registration by requiring official federation, club, and loft selections.
- Add safeguards for deleting or renaming entities already tied to users/results.
- Add an admin web workbench for adding, editing, and deleting unlocked organisation records.

## PR-08 Database And Production Data Hardening

Status: complete for MVP hardening.

- Add database migrations for H2/PostgreSQL.
- Make seed data explicit, repeatable, and production-safe.
- Add backup guidance for database and uploaded PDFs.
- Make the PostgreSQL profile deployable from a clean database.

## PR-09 Release And Observability

Status: complete for MVP release safety and diagnostics.

- Add deploy rollback support.
- Add version/build info to health output.
- Add structured logs for auth, upload, parsing, and email events.
- Expand deploy health checks to authenticated and admin-only paths.

## PR-10 Mobile Release Polish

Status: complete for MVP mobile release packaging.

- Version APK builds.
- Keep API URL, icons, signing, and download page reproducible.
- Add a smoke check for installed mobile web assets.
