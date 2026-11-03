# Changelog

All notable changes of RUTERA. Versions follow semantic versioning; a release is the git tag `v<version>`, which
publishes the image `ghcr.io/vlslysenko/rutera:<version>` (`.github/workflows/release.yml`).

## Unreleased (0.1.0 candidate)

First version for the pilot: a standalone installation for one small road carrier, and the operator side of a
cloud installation. The API of every module is described in `README.md`; the state per module and what comes later
is in `docs/REQUIREMENTS.md`, section 17.

### Platform

- Spring Boot 4.0 on Java 25 and Jetty, MySQL 8.4 with Flyway migrations V1-V14, Hibernate `@TenantId` on every
  firma table (one database for all firmas).
- Sign-in with a session cookie and CSRF, roles OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT, DRIVER, CUSTOM and
  SUPERUSER with privilege keys per role, user administration, own profile, language and password.
- Security: account lockout after failed passwords, sign-in throttle per client address (429
  `auth.login.throttled`), sessions with idle timeout and absolute lifetime, Content-Security-Policy and other
  security headers, only the health endpoint of the actuator exposed, uploads checked by their content.
- Audit journal of every change with the channel it came through (web application, public API, MCP, system); list
  for owners and the operator, filter for the actions via AI.
- 26 languages (the 24 official EU languages, Ukrainian, Russian) in the web application, the e-mails and the
  error texts.
- Web application (React, TypeScript, Mantine) installable as a PWA, laid out for desktop and phone.

### Fleet and drivers

- Vehicles and trailers with Euro class, axles, weight, height, consumption and tachograph generation; documents
  with deadlines for vehicles and drivers.
- Drivers with their tachograph card, documents, absences (entered by the office or requested by the driver and
  decided), working-day calendar from tachograph data and manual entries, pay rates and payments.

### Tachograph

- Upload of DDD files (driver cards and vehicle units, Gen1, Gen2, Gen2v2) with an own Java parser, matching to the
  driver or vehicle, journal of the files.
- Activity timeline per driver and violations of Regulation (EC) 561/2006 with the severities of Regulation
  (EU) 2016/403, acknowledgement by the office.

### Orders, trips and documents

- Contractors, transport orders, trips with crew, stops and orders; an order becomes a trip in one step.
- CMR notes issued by RUTERA: drafts from orders and trips, numbering, PDF of the four copies.
- Photos and documents of a trip uploaded by the driver or the office.

### Money

- Recording of sales and purchase invoices with lines and VAT codes, payments, debts with ageing, payment reminder
  plans sent by e-mail through an outbox with retries.
- Fuel cards and the import of provider exports (CSV, XLSX) with column mapping, preview and undo, matching of the
  transactions to vehicle, driver and trip.

### Trip calculator

- Planner of breaks and rests under 561/2006 for one driver or a crew of two (state from the tachograph data),
  costs of fuel, driver pay, tolls (own tariffs of the firma) and other costs.
- Truck routing with Valhalla (optional service) and geocoding with Nominatim; route map in the web application.

### Driver cabinet

- Own data only: today and the own trips with their stops, hours and violations, absence requests, photos and
  documents for the trip, own pay when the firma allows it.
- Works without a connection on the phone: saved data is shown, marked stops are sent when the connection is back;
  install prompt for the home screen.

### AI access (paid add-on per firma)

- Public REST API `/api/v1` with an OpenAPI 3 document, personal API tokens, a call log and a rate limit per token.
- MCP server at `/mcp` with 109 tools generated from the API, and the AI role guides (`docs/ai-roles`) as resources.
- OAuth 2.1 (dynamic client registration, PKCE, consent page) for the claude.ai connector and other MCP clients;
  connections are listed and revoked like tokens.

### Operator and installation

- Operator console for SUPERUSER: firmas with users and vehicles, plan, vehicle limit, AI access, blocking, end
  date, onboarding of a firma with its first owner, audit journal of any firma.
- Standalone package `docker-compose.standalone.yml`: MySQL, the RUTERA image, nightly backups of the database and
  the uploaded files with tested archives and restore, optional routing, settings in `.env`; paid options of a
  standalone installation from its environment.
- GitHub Actions: CI on every push (backend tests with Testcontainers, client lint, type check and build) and the
  release of the image on tags.
