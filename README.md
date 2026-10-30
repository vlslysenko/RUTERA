# RUTERA

Cloud TMS and fleet management for small transport companies (5 to 30 vehicles).
Requirements: `docs/REQUIREMENTS.md`; database sketch: `docs/DB_SCHEMA.md`; AI role guides: `docs/ai-roles/`.

## Layout

| Path | Content |
|---|---|
| `pom.xml` | Maven parent (Spring Boot 4.0.x, Java 25) |
| `common/` | JPA entities, repositories, Flyway migrations (`db/migration`), i18n bundles (`i18n/messages_xx.properties`, 26 languages), tenant context, privilege catalogue |
| `core/` | Spring Boot application on Jetty: web layer, security, internal API for the React client (`/app/...`) |
| `frontend/` | React + TypeScript client (Vite, Mantine, PWA) |
| `Dockerfile` | Standalone image: client + backend in one jar on a JRE |
| `compose.yaml` | Standalone stack (MySQL 8.4 + RUTERA); its `mysql` service alone is the development database |
| `docs/` | Requirements, DB schema, AI role guides, UI mockups |

## Standalone with one command (Docker)

```bash
cp .env.example .env          # optional: port, database credentials, first firma and owner
docker compose up -d --build  # builds the image (client + jar), starts MySQL and RUTERA
docker compose exec rutera grep "generated password" logs/rutera.log   # owner admin, first start
```

RUTERA is then at `http://localhost:8080` (`RUTERA_PORT` changes the published port; MySQL is published on
`RUTERA_DB_PORT`, default 3307). The `rutera` service waits for a healthy MySQL and is healthy itself once
`/actuator/health` answers. Volumes: `rutera-mysql-data` (database), `rutera-conf` (`/opt/rutera/conf`, optional
`application.properties` / `logback.xml`), `rutera-logs` (`/opt/rutera/logs`). JVM options go to
`RUTERA_JAVA_OPTS`. Behind an HTTPS reverse proxy set `RUTERA_COOKIE_SECURE=true`. Update: `git pull` and the same
`docker compose up -d --build`; `docker compose down -v` removes everything including the data.

## Build and run (development)

Prerequisites: JDK 25, Maven 3.9+, Docker; Node 22+ for the client.

```bash
docker compose up -d mysql    # MySQL 8.4 only, on localhost:3307 (3306 is often taken by another MySQL)
mvn verify                    # builds both modules (API only, no client in the jar)
java -jar core/target/rutera.jar
```

The client runs from the Vite dev server (`cd frontend && npm run dev`, http://localhost:5173), which proxies
`/app` to the backend (`VITE_API_PROXY` in `frontend/.env.local`, see `frontend/README.md`).
`mvn -Pfrontend package` also runs `npm ci` + `npm run build` in `frontend/` and puts `frontend/dist` into the jar
(`classpath:/static/`); with `-Dexec.skip=true` npm is skipped and an existing `frontend/dist` is taken. The jar
then serves the client itself (`core.config.FrontendConfig`): files from the build, hashed `/assets/**` cached for
a year, everything else revalidated; any other GET whose last path segment has no extension (a client route such
as `/fleet/12`) gets `index.html`. `/app/**`, `/actuator/**` and missing files (`/assets/x.js`) are never answered
with the page.

Configuration defaults live in `core/src/main/resources/application.properties`. An installation overrides
them in `conf/application.properties` next to the jar or with environment variables (`RUTERA_DB_HOST`,
`RUTERA_DB_PORT`, `RUTERA_DB_NAME`, `RUTERA_DB_USER`, `RUTERA_DB_PASSWORD`, `RUTERA_PORT`). `conf/logback.xml`,
when present, replaces the built-in logging configuration. If `DOCKER_HOST` points at a remote engine, the
compose database is published there: set `RUTERA_DB_HOST` to that host.

Schema changes are Flyway migrations in `common/src/main/resources/db/migration/`; Hibernate runs with
`ddl-auto=validate`, so entities and migrations cannot drift apart unnoticed.

`mvn verify` runs the integration tests of `core` against a MySQL 8.4 started by Testcontainers, so Docker must
be reachable (`DOCKER_HOST` needs a scheme, e.g. `tcp://host:2375`; Testcontainers rejects a bare `host:port`).

### First start

On an empty database the application creates the first firma and its owner account from the
`RUTERA_BOOTSTRAP_*` variables (see `.env.example`). Without `RUTERA_BOOTSTRAP_ADMIN_PASSWORD` a random password
is generated and written to the log once (`First start: ... generated password`); change it after signing in.

## Internal API for the React client (`/app`)

Session cookie `RUTERA_SESSION` (HttpOnly, SameSite=Lax) + CSRF: cookie `XSRF-TOKEN` (readable by JavaScript) is
mirrored in header `X-XSRF-TOKEN` on every POST/PUT/PATCH/DELETE, login included. The first call (for example
`GET /app/auth/me`) hands out the CSRF cookie. After five wrong passwords the account is locked for 15 minutes
(`RUTERA_LOCKOUT_ATTEMPTS`, `RUTERA_LOCKOUT_DURATION`).

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/auth/login` | `{login, password}` -> `User`; 401 `{code: auth.login.invalid \| auth.login.locked \| auth.login.disabled \| auth.login.firma_inactive}` |
| POST | `/app/auth/logout` | 204 |
| GET | `/app/auth/me` | `User`; 401 `{code: auth.unauthenticated}`; also refreshes the session's privilege snapshot |
| PUT | `/app/me/settings` | `{language?, startScreen?}` -> `User`; 422 `{code: error.validation, details: {field: validation.*}}` |
| PUT | `/app/me/password` | `{currentPassword, newPassword}` -> 204; 422 details `currentPassword: auth.password.wrong_current`, `newPassword: validation.required \| validation.too_short \| validation.too_long` |
| GET | `/app/i18n/{lang}` | flat `{key: text}` dictionary of `messages_<lang>.properties` (public) |
| GET | `/actuator/health` | liveness (public) |

`User = {id, login, name, role, firma: {id, name} | null, language, startScreen | null, privileges: string[]}`;
`privileges` is the effective set of `module.entity/action` keys (empty for the superuser, who bypasses checks),
so the client can hide what the user may not do. Every 4xx/5xx body is `{code, message?, details?}` where
`code` is an i18n key; `message` is that text in the language of the request (the user's language, otherwise
`Accept-Language`).

### User administration (`/app/admin/users`, privileges `admin.user/view` and `admin.user/manage`)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/admin/users` | `AdminUser[]` of the own firma, sorted by name; deleted users excluded |
| GET | `/app/admin/users/{id}` | `AdminUser`; 404 for unknown ids and users of other firmas |
| POST | `/app/admin/users` | `{login, firstName, lastName?, role, customRoleId?, phone?, language?, password}` -> 201 `AdminUser` |
| PUT | `/app/admin/users/{id}` | `{firstName, lastName?, role, customRoleId?, phone?, language, active}` -> `AdminUser` |
| PUT | `/app/admin/users/{id}/password` | `{newPassword}` -> 204 (also lifts a lockout) |
| POST | `/app/admin/users/{id}/unlock` | 204: clears `locked_until` and the failure counter |
| DELETE | `/app/admin/users/{id}` | 204: soft delete; the login stays reserved |

`AdminUser = {id, login, firstName, lastName, name, role, customRoleId, phone, language, active, locked,
failedLogins, createdAt, updatedAt}`. `role` is one of `OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT, DRIVER,
CUSTOM` (`CUSTOM` needs a `customRoleId` of the firma); `login` is trimmed and lower-cased, 3 to 254 characters
of letters, digits and `. _ @ + -`, globally unique; `language` defaults to the firma language on creation.
Passwords are 10 (`RUTERA_PASSWORD_MIN_LENGTH`) to 72 characters.

Rules: a user may manage only users whose role level is greater than or equal to their own and may assign only
such roles (`Role.getLevel()`: OWNER 1, DISPATCHER/LOGISTICIAN/ACCOUNTANT 3, CUSTOM its own level, DRIVER 9),
otherwise 403 `admin.user.role_not_allowed`. Nobody changes their own role or `active` flag, resets their own
password here or deletes themselves: 403 `admin.user.self_forbidden` (own name, phone and language are fine).
422 `error.validation` carries per-field codes, including `login: admin.user.login_taken`. Deactivating or
deleting a user ends their session on their next request; a role change takes effect for an open session on its
next `GET /app/auth/me` (the client calls it on every load).

### Fleet (`/app/fleet/vehicles`, privileges `fleet.vehicle/view`, `fleet.vehicle/manage`, `fleet.document/manage`)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/fleet/vehicles` | `Vehicle[]` of the own firma, sorted by plate; deleted vehicles excluded |
| GET | `/app/fleet/vehicles/{id}` | `Vehicle`; 404 for unknown ids and vehicles of other firmas |
| POST | `/app/fleet/vehicles` | `VehicleInput` -> 201 `Vehicle` (manage) |
| PUT | `/app/fleet/vehicles/{id}` | `VehicleInput` (full replacement) -> `Vehicle` (manage) |
| DELETE | `/app/fleet/vehicles/{id}` | 204: soft delete, the plate becomes free (manage) |
| GET | `/app/fleet/vehicles/{id}/documents` | `VehicleDocument[]`, nearest `validTo` first, without deadline last (view) |
| POST | `/app/fleet/vehicles/{id}/documents` | `VehicleDocumentInput` -> 201 `VehicleDocument` (document manage) |
| PUT | `/app/fleet/vehicles/{id}/documents/{docId}` | `VehicleDocumentInput` (full replacement) -> `VehicleDocument` |
| DELETE | `/app/fleet/vehicles/{id}/documents/{docId}` | 204 (physical delete) |

`Vehicle = {id, kind, plate, vin, make, model, euroClass, axles, grossWeightKg, heightMm, avgL100km, fuelType,
tachoGen, odometerKm, status, defaultDriver: {id, name} | null, defaultTrailer: {id, plate} | null,
lastVuDownloadAt, nextDocument: {kind, validTo} | null, createdAt, updatedAt}`; `VehicleInput` = the editable
fields plus `defaultDriverId`, `defaultTrailerId`. `kind` is one of `TRUCK, TRACTOR, VAN, TRAILER, SEMI_TRAILER`,
`euroClass` `EURO_3..EURO_6`, `fuelType` `DIESEL, HVO, LNG, CNG, PETROL, ELECTRIC`, `tachoGen` `NONE, GEN1, GEN2`
(default NONE), `status` `ACTIVE, SERVICE, INACTIVE` (default ACTIVE); an unknown value is a per-field
`validation.invalid_value`. `VehicleDocument = {id, kind, number, validFrom, validTo, cost, currency, remindDays}`
with `kind` one of `REGISTRATION, INSURANCE_OC, INSURANCE_AC, INSPECTION, TACHO_CALIBRATION, LICENSE_COPY,
TOLL_DEVICE, OTHER`, dates `YYYY-MM-DD`, `currency` required with `cost`, `remindDays` 0..365 (default 30).

Rules: the plate is stored trimmed, upper-cased and with single spaces (letters, digits, space, hyphen; up to 20)
and is unique among the firma's non-deleted vehicles regardless of case, spaces and hyphens (`plate:
fleet.vehicle.plate_taken`). The VIN loses its spaces and is upper-cased (letters and digits, up to 17). Trailers
have no engine: the server stores `tachoGen` NONE and drops fuel, consumption, odometer, driver and trailer. A
default trailer must be a non-deleted trailer of the firma; deleting a trailer detaches it from its tractors.
`defaultDriverId` must be a non-deleted driver of the firma (`defaultDriver: {id, name}`); this link is the only
place where a driver's usual vehicle is stored. `nextDocument` is the document with the earliest `validTo`, expired
ones included. Changes are written to `audit_log` (module `fleet`).

### Drivers (`/app/drivers`, privileges `driver.driver/view` and `driver.driver/manage`)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/drivers` | `Driver[]` of the own firma, sorted by last and first name; deleted drivers excluded |
| GET | `/app/drivers/{id}` | `Driver`; 404 for unknown ids and drivers of other firmas |
| POST | `/app/drivers` | `DriverInput` -> 201 `Driver` (manage) |
| PUT | `/app/drivers/{id}` | `DriverInput` (full replacement, card included) -> `Driver` (manage) |
| DELETE | `/app/drivers/{id}` | 204: soft delete; detached from vehicles, card number freed, login unlinked (manage) |
| GET | `/app/drivers/{id}/documents` | `DriverDocument[]`, nearest `validTo` first, without deadline last (view) |
| POST | `/app/drivers/{id}/documents` | `DriverDocumentInput` -> 201 `DriverDocument` (manage) |
| PUT | `/app/drivers/{id}/documents/{docId}` | `DriverDocumentInput` (full replacement) -> `DriverDocument` (manage) |
| DELETE | `/app/drivers/{id}/documents/{docId}` | 204 (physical delete, manage) |

`Driver = {id, firstName, lastName, name, birthDate, phone, email, country, employmentFrom, employmentTo,
status: ACTIVE | INACTIVE, defaultVehicle: {id, plate} | null, card: {cardNumber, issuingCountry, validTo,
lastDownloadAt} | null, user: {id, login} | null, notes, nextDocument: {kind, validTo} | null, createdAt,
updatedAt}`. `DriverInput = {firstName, lastName, birthDate?, phone?, email?, country?, employmentFrom?,
employmentTo?, status?, notes?, cardNumber?, cardIssuingCountry?, cardValidTo?}`. `DriverDocument = {id, kind,
number, validFrom, validTo, remindDays}` with `kind` one of `LICENSE, CPC_CODE95, MEDICAL, PSYCHO, ADR, PASSPORT,
VISA, WORK_PERMIT, OTHER`; input `{kind, number?, validFrom?, validTo?, remindDays?}` (0..365, default 30).

Rules: first and last name are required (up to 100); `country` and `cardIssuingCountry` are ISO alpha-2,
upper-cased; `email` must look like an address; `employmentTo` / `validTo` may not lie before their start. The
card is edited with the driver: an empty `cardNumber` removes it, a new number overwrites it and resets
`lastDownloadAt` (written by the DDD import later); the number loses spaces and hyphens, is upper-cased, and is
unique in the firma (`cardNumber: driver.card.number_taken`). `defaultVehicle` is the first vehicle (by plate)
whose `defaultDriverId` is this driver. `nextDocument` is the earliest `validTo` among the documents and the card
(`kind: DRIVER_CARD`). Changes are written to `audit_log` (module `driver`).

### Tachograph files (`/app/tacho/files`, privileges `tacho.file/upload` and `tacho.hos/view`)

Step A of `docs/TACHO_IMPORT_API.md` (project files): the uploaded DDD file, its summary, the driver or vehicle it
belongs to and `lastDownloadAt`; facts (activities, places, events) and 561/2006 come in later steps. The parser is
module `tacho/` (plain Java). Real downloads hold personal data: they never go into the repository (`.gitignore`,
`.dockerignore`); the tests use the synthetic files of the tacho test-jar (`TachoTestFiles`).

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/tacho/files` | `multipart/form-data`, part `files` (1..50) -> `TachoImportResult[]` in the order of the files (upload) |
| GET | `/app/tacho/files` | `?kind=&driverId=&vehicleId=&unassigned=true&from=&to=&page=0&size=50` -> `{items: TachoFile[], total}`, newest upload first (view or upload) |
| GET | `/app/tacho/files/{id}` | `TachoFileDetail` = `TachoFile` + `{warnings, counts, suggestion, drivers}` (view or upload) |
| GET | `/app/tacho/files/{id}/content` | the original bytes as an attachment (upload) |
| PUT | `/app/tacho/files/{id}/assignment` | `{driverId}` for a card, `{vehicleId}` for a vehicle unit -> `TachoFile`, `match: MANUAL` (upload) |
| POST | `/app/tacho/files/{id}/driver` | 201 `{driver, file}`: driver created from the card (upload + `driver.driver/manage`) |
| POST | `/app/tacho/files/{id}/vehicle` | 201 `{vehicle, file}`: vehicle (kind TRUCK) created from the unit (upload + `fleet.vehicle/manage`) |
| DELETE | `/app/tacho/files/{id}` | 204: the file and what was derived from it; `lastDownloadAt` recalculated; audit entry (upload) |

`TachoImportResult = {fileName, status: IMPORTED | DUPLICATE | REJECTED, file: TachoFile | null, error: {code,
message} | null}`; rejection codes `tacho.file.not_tachograph | truncated | unsupported | corrupt | too_large`
(20 MB per file); more than 50 files is 413 `tacho.file.too_many`, none is 422 `files: validation.required`.
`TachoFile = {id, kind: DRIVER_CARD | VEHICLE_UNIT, generation: GEN1 | GEN2 | GEN2V2, originalName, sizeBytes,
sha256, cardNumber, cardIssuingCountry, cardValidTo, vin, plate, plateCountry, periodFrom, periodTo, downloadedAt,
warningsCount, driver: {id, name} | null, vehicle: {id, plate} | null, match: CARD_NUMBER | DRIVER_IDENTIFICATION |
VIN | PLATE | MANUAL | NONE, uploadedBy: {id, name}, uploadedAt}`. `from`/`to` select files whose period overlaps.

Rules: every file is imported in its own transaction; the same bytes (sha256 per firma) are a `DUPLICATE` and
change nothing; rejected files are not stored. A card is matched by its 16-character number, otherwise by the first
14 (driver identification: a renewed or replaced card, which then replaces the driver's card when its expiry is
later); a vehicle unit by VIN, otherwise by plate (filling an empty VIN), and a vehicle with `tachoGen` NONE takes
the generation of the file. Manual assignment of a card to a driver without a card creates the card.
`driver.card.lastDownloadAt` / `vehicle.lastVuDownloadAt` are the newest `downloadedAt` among the files of that card
or vehicle. Accepted files are stored by `core.storage.FileStorage` under `rutera.storage.dir`
(`RUTERA_STORAGE_DIR`, default `data/files` next to the working directory; volume `rutera-data` in Docker). Names
and card numbers from the files never go to the log files.

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
