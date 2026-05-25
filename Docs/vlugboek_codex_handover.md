# Vlugboek — CodeX Handover Pack

## 1. Application Name

**Vlugboek**

A mobile-first pigeon racing results platform for viewing, searching, filtering, downloading, and emailing official race result reports.

## 2. Purpose

Vlugboek must allow registered pigeon fanciers to access official race results and standings in a simple mobile-friendly format.

The system does **not calculate results**.  
It ingests official PDF reports that are already calculated, structured, and published by the existing race result source.

The core principle:

> Store the official PDF, extract the structured data, display it faithfully, and preserve the point-in-time record.

---

# 3. Organisation Structure

The racing organisation hierarchy is:

```text
SANPO
 └── Federation / Union
      └── Club
           └── Loft
                └── User
```

Example:

```text
SANPO
 └── PWDF - Pretoria Wedvlug Duiwe Federasie
      ├── Zwartkops
      ├── Magalies
      ├── Eureka
      └── Wes-Moot
```

Users register with an email address and must link themselves to:

- Federation / Union
- Club
- Loft Name

Loft names are predefined, strictly named, and linked to a club. Users may not free-type loft names.

---

# 4. Core Technology Stack

## Backend

Use:

```text
Java Spring Boot
PostgreSQL
JPA / Hibernate
REST APIs
PDF parsing service
Email service
```

Recommended backend modules:

```text
auth-service
organisation-service
document-ingestion-service
pdf-parser-service
result-query-service
leaderboard-service
email-service
```

## Frontend

Use:

```text
React
Mobile-first responsive UI
Capacitor-ready structure
Local storage / cookie support
i18n support for Afrikaans and English
```

The app must be designed as a web application first, but structured so that the same React codebase can later be wrapped into Android/iOS using Capacitor.

---

# 5. Language Support

The application must support:

- Afrikaans
- English

Language selection must be a user preference.

Store preference in:

- user profile in database, and/or
- browser local storage,
- Capacitor local storage for mobile.

Only UI labels, buttons, headings, menu items, and system messages need translation. Race result data remains exactly as supplied in the PDF.

---

# 6. Major Report Families

## 6.1 Distance Log Reports

These are reports such as:

- Short Distance Log - All Races
- Middle Distance Log - All Races
- Long Distance Log - All Races

Requirement:

> Ingest the report exactly as supplied, including title, column names, column order, row order, and values.

Use `Report Created` as the official report date.

---

## 6.2 Race Detail Reports

These are individual race result reports.

Examples:

- Britstown 1 JO
- Britstown 2 OPEN
- Christiana 1 JO
- De Aar 1 JO
- Victoria West 1 OPEN1

Use the **Liberated date** as the official race date.

Ignore the time for business filtering, but store the full timestamp for audit.

---

## 6.3 Classification / Leaderboard Reports

These are point-in-time leaderboard snapshots.

Classifications:

- Loft Points / Hok Punte
- Open Points
- Member Points / Lede Punte
- Year Old Points / JO Punte

Important rule:

> Every upload is stored as a historical snapshot, but the latest valid snapshot per classification becomes the current leaderboard.

This allows:

- current leaderboard display,
- historical comparison,
- trend analysis later.

---

## 6.4 Combine Results

Not yet supplied.

Design the ingestion model so Combine Results can be added later using the same principles:

- store PDF,
- recognise report type,
- parse rows/columns,
- display,
- preserve snapshot where applicable.

---

# 7. Data Integrity Principle

Admins may upload PDFs but may not manually edit parsed result data.

If something is wrong:

- upload corrected PDF,
- reprocess document,
- create new snapshot/version.

The PDF remains the official source of truth.

---

# 8. Upload / Ingestion Flow

```text
Admin uploads PDF
 ↓
System stores original PDF
 ↓
System parses header
 ↓
System recognises report type
 ↓
System displays upload confirmation
 ↓
Admin confirms import
 ↓
System stores parsed dataset
 ↓
System marks report available to users
```

---

# 9. Recommended Data Model

## Core Entities

```text
User
Role
Federation
Club
Loft
Document
ReportDataset
ReportColumn
ReportRow
ReportCell
ClassificationCategory
ClassificationSnapshot
Race
RaceResult
```

---

# 10. Leaderboard Snapshot Model

When a new valid classification report is imported:

```text
1. Store PDF
2. Parse data
3. Create snapshot
4. Mark previous latest snapshot as false
5. Mark new snapshot as latest
```

---

# 11. User Features

Registered users must be able to:

- log in with email address,
- view results linked to their federation/club/loft,
- search race reports,
- filter by date, race, club, loft, category,
- view original PDF,
- download PDF,
- download structured data,
- email PDF to themselves,
- view current leaderboards,
- switch language between Afrikaans and English.

---

# 12. Admin Features

Admins must be able to:

- manage federations,
- manage clubs,
- manage loft names,
- upload PDFs,
- view processing status,
- see recognised report type,
- approve import,
- reprocess failed upload,
- view imported historical reports,
- view latest leaderboard snapshots.

Admins must not edit parsed race result data.

---

# 13. Mobile-First UI Notes

Use:

- sticky first column,
- horizontal swipe,
- compact row view,
- search within table,
- “card view” option for race detail rows,
- download/email buttons near report header.

---

# 14. Suggested API Endpoints

```text
POST /api/auth/register
POST /api/auth/login
GET /api/federations
GET /api/clubs?federationId=
GET /api/lofts?clubId=
POST /api/documents/upload
GET /api/documents
GET /api/documents/{id}
GET /api/documents/{id}/pdf
POST /api/documents/{id}/email
GET /api/reports
GET /api/reports/{id}
GET /api/leaderboards
GET /api/races
```

---

# 15. MVP Scope

## Must Have

- User registration/login
- Federation/club/loft setup
- Admin PDF upload
- PDF storage
- Report type recognition
- Dynamic table ingestion
- Result browsing
- Search/filter
- PDF download
- Email PDF
- Afrikaans/English UI
- Current classification leaderboards

## Later

- Combine results
- Trend analytics
- Loft performance dashboards
- Bird/ring history
- Push notifications
- Capacitor mobile packaging
- Advanced statistics

---

# 16. Development Instruction to CodeX

Build Vlugboek as a clean, mobile-first, document-driven result ingestion and display platform.

Do not over-normalize race data too early.

Use a flexible dataset model that preserves the exact structure of every PDF report while still allowing search, filtering, downloads, leaderboards, and later analytics.

Primary design principle:

> The official PDF is the source of truth. The parsed database representation exists to make the data searchable, displayable, downloadable, and analyzable.
