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

### Driver HR (absences, working days, pay rates, payments)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/drivers/{id}/absences?from=&to=` | `Absence[]`, newest first (`driver.driver/view`) |
| POST / PUT / DELETE | `/app/drivers/{id}/absences[/{absenceId}]` | `{kind, fromDate, toDate, paid?, note?}` -> `Absence` / 204 (`driver.absence/manage`) |
| GET | `/app/absences?from=&to=&kind=&driverId=` | `Absence[]` of the firma overlapping the range (`driver.driver/view`) |
| GET | `/app/drivers/{id}/days?month=YYYY-MM` | `{days: DriverDay[], summary: {workDays, restDays, absenceDays, holidayDays, drivingMin, workMin}}` (`driver.driver/view`) |
| PUT / DELETE | `/app/drivers/{id}/days/{YYYY-MM-DD}` | `{kind, note?}` -> `DriverDay` (manual entry / back to computed; `driver.driver/manage`) |
| GET | `/app/drivers/{id}/pay-rates` | `PayRate[]`, newest `validFrom` first (`driver.payment/view`) |
| POST / PUT / DELETE | `/app/drivers/{id}/pay-rates[/{rateId}]` | `{validFrom, monthlyBase?, perDiemHome?, perDiemAbroad?, perKm?, currency}` (`driver.payment/manage`) |
| GET | `/app/drivers/{id}/payments?from=YYYY-MM&to=YYYY-MM` | `Payment[]` (`driver.payment/view`) |
| POST / PUT / DELETE | `/app/drivers/{id}/payments[/{paymentId}]` | `{period, kind, amount, currency, paidAt?, note?}` (`driver.payment/manage`) |
| GET | `/app/payments?period=&driverId=&kind=` | `{items: Payment[], totals: [{currency, amount}]}` of the firma (`driver.payment/view`) |

`Absence = {id, driver: {id, name}, kind: SICK | VACATION | UNPAID | PARENTAL | OTHER, fromDate, toDate, days,
workingDays, paid, note, approvedBy: {id, name}, approvedAt, createdAt, updatedAt}`: both dates included, `days`
calendar days, `workingDays` Monday to Friday, `paid` false by default only for UNPAID, at most 366 days; absences of
a driver may not overlap (`toDate: driver.absence.overlap`); an absence entered by a manager is approved by them.
`DriverDay = {day, kind: WORK | REST | ABSENCE | HOLIDAY, source: TACHO | ABSENCE | MANUAL | NONE, absenceKind,
drivingMin, workMin, note}`: a manual entry (`driver_day`) wins, then an absence, then the tachograph (driving or work
= WORK, only rest recorded = REST, no data = REST with source NONE). `PayRate = {id, validFrom, monthlyBase,
perDiemHome, perDiemAbroad, perKm, currency, createdAt, updatedAt}`: one per `validFrom` and driver
(`validFrom: driver.pay_rate.date_taken`), at least one amount. `Payment = {id, driver, period, kind: SALARY |
PER_DIEM | BONUS | ADVANCE | DEDUCTION, amount, currency, paidAt, note, createdAt, updatedAt}`: amount above zero;
the totals count advances and deductions negative. Payments are hidden from roles without `driver.payment/view`
(only owner and accountant by default). Changes are written to `audit_log` (module `driver`).

### Contractors, orders and trips (`/app/contractors`, `/app/orders`, `/app/trips`)

Privileges: contractors are read with `trip.order/view` or `trip.contractor/manage` and changed with
`trip.contractor/manage`; orders `trip.order/view` / `trip.order/manage`; trips `trip.trip/view` /
`trip.trip/manage`; `POST /app/orders/{id}/trip` needs both manage keys.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/contractors?q=&role=CUSTOMER\|CARRIER` | `Contractor[]` by name, deleted excluded |
| GET / POST / PUT / DELETE | `/app/contractors[/{id}]` | `Contractor`; PUT is a full replacement; DELETE is a soft delete |
| GET | `/app/orders?status=&customerId=&q=&from=&to=&page=&size=` | `{items: Order[], total}`, newest `orderDate` first |
| GET / POST / PUT | `/app/orders[/{id}]` | `Order`; PUT is a full replacement |
| DELETE | `/app/orders/{id}` | 204; 409 `error.conflict` when the order is on a trip or invoiced |
| POST | `/app/orders/{id}/trip` | `{vehicleId, trailerId?, mainDriverId?, secondDriverId?, plannedStart?}` -> 201 `Trip` with the order's loading and unloading as stops |
| GET | `/app/trips?status=&vehicleId=&driverId=&from=&to=&q=&page=&size=` | `{items: Trip[], total}`; from/to overlap the planned period |
| GET / POST / PUT | `/app/trips[/{id}]` | `Trip`; PUT replaces crew, orders and stops |
| DELETE | `/app/trips/{id}` | 204 for PLANNED or CANCELLED trips (orders go back to NEW), else 409 |
| PATCH | `/app/trips/{id}/stops/{stopId}` | `{status: PLANNED\|DONE\|SKIPPED, actualAt?}` -> `TripStop` |

`Contractor = {id, name, vatId, country, address, phone, invoiceEmails[], paymentDays, lang, isCustomer, isCarrier,
notes, createdAt, updatedAt}`; the VAT id is stored upper-case without spaces, dots and hyphens and is unique among
the firma's non-deleted contractors (`vatId: trip.contractor.vat_taken`).
`Order = {id, number, orderDate, customer: {id, name}, customerRef, price, currency, cargo, weightKg, loading:
{place, country, date}, unloading: {place, country, date}, status, trip: {id, number, status} | null, notes,
createdAt, updatedAt}`; input `{number?, orderDate, customerId, customerRef?, price?, currency?, cargo?, weightKg?,
loadingPlace, loadingCountry, loadingDate?, unloadingPlace, unloadingCountry, unloadingDate?, cancelled?, notes?}`.
`Trip = {id, number, status: PLANNED | RUNNING | DONE | CANCELLED, vehicle: {id, plate}, trailer, drivers: [{id,
name, role: MAIN | SECOND}], plannedStart, plannedEnd, actualStart, actualEnd, kmPlanned, kmActual, orders: [{id,
number, customer, price, currency}], stops: TripStop[], notes, createdAt, updatedAt}`; `TripStop = {id, seq, kind:
LOADING | UNLOADING | BORDER | REST | FUEL | CUSTOMS | OTHER, orderId, name, address, country, plannedAt, actualAt,
status: PLANNED | DONE | SKIPPED}`; input `{number?, vehicleId, trailerId?, mainDriverId?, secondDriverId?, status?,
plannedStart?, plannedEnd?, actualStart?, actualEnd?, kmPlanned?, kmActual?, orderIds[], stops: [{id?, kind,
orderId?, name?, address, country, plannedAt?, actualAt?, status?}], notes?}`.

Rules: an empty number gets the next one of the year (`O-2026-0001`, `T-2026-0001`; orders count by the year of
`orderDate`); typed numbers are upper-cased and unique in the firma (`number: trip.order.number_taken |
trip.trip.number_taken`). The vehicle may not be a trailer and the trailer must be one; the second driver needs a
main driver and may not be the same person. An order's status is NEW without a trip and follows it otherwise
(PLANNED, RUNNING -> IN_PROGRESS, DONE); `cancelled: true` sets CANCELLED and takes it off its trip, a cancelled trip
lets its orders go back to NEW. A PUT of a trip keeps stops that carry their `id`, creates the others and deletes
the missing ones (`seq` = position); a stop may only name an order of the trip. RUNNING fills an empty
`actualStart`, DONE an empty `actualEnd`, a stop marked DONE an empty `actualAt`. Changes are written to
`audit_log` (module `trip`).

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

### Driver activity and 561/2006 (`/app/tacho/activity`, `/app/tacho/violations`)

Steps B and D of the import. Every imported file writes its activity intervals to `tacho_activity`
(`core.tacho.TachoFacts`, plain JDBC): a driver card all its intervals (card withdrawn without a manual entry =
UNKNOWN, vehicle from EF Vehicles_Used), a vehicle unit the intervals of slots with a driver card inserted, with that
card's number. Files stored before this step are extracted at the next start (`TachoFactsBackfill`,
`tacho_file.facts_version`). A driver's timeline is merged on read (`core.tacho.HosService`): the driver's card files
(the newest file wins per UTC day), and for days without card data the vehicle unit data of their cards (matched by
the driver identification, the first 14 characters of the card number), the newest file winning per moment.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/tacho/activity?driverId=&from=&to=` | `{driver: {id, name}, timezone, days: HosDay[], intervals: Interval[]}`, up to 31 days (`tacho.hos/view`) |
| GET | `/app/tacho/violations?driverId=&from=&to=&severity=&unacknowledged=true&page=&size=` | `{items: Violation[], total}`, newest first (`tacho.hos/view`) |
| GET | `/app/tacho/violations/{id}` | `Violation` (`tacho.hos/view`) |
| POST | `/app/tacho/violations/{id}/ack` | `{note?}` -> `Violation` (`tacho.violation/ack`) |
| DELETE | `/app/tacho/violations/{id}/ack` | `Violation`, acknowledgement removed (`tacho.violation/ack`) |

`Interval = {startAt, endAt, activity: DRIVING | WORK | AVAILABILITY | REST | UNKNOWN, slot: 1 | 2, vehicle: {id |
null, plate} | null}` (id null: the plate is not in the fleet). `HosDay = {day, drivingMin, workMin, availabilityMin,
restMin, dailyRestMin, extDrivingInWeek, reducedRestInWeek, weeklyDrivingMin, biweeklyDrivingMin, remainingDailyMin,
remainingWeeklyMin, nextWeeklyRestBy, violations}` for every day of the range, cut in the firma's time zone.
`Violation = {id, driver: {id, name}, rule, severity: MINOR | SERIOUS | VERY_SERIOUS | MOST_SERIOUS, startAt, endAt,
actualValue, limitValue (minutes), vehicle, details, ack: {by: {id, name}, at, note} | null}`.

Rules (`core.tacho.hos.HosEngine`, pure code): unrecorded time and UNKNOWN count as rest; a rest of 9 h ends a shift,
24 h is a weekly rest. `DAILY_DRIVING_9H` / `DAILY_DRIVING_10H` (two 10 h days per calendar week),
`BREAK_45_AFTER_4H30` (45 min, or 15 + 30), `DAILY_REST_11H` / `DAILY_REST_9H` (within 24 h of the shift start; 3 + 9
split; at most three reduced rests between weekly rests), `WEEKLY_DRIVING_56H`, `TWO_WEEK_DRIVING_90H`,
`WEEKLY_REST_24H` (within six 24-hour periods), `WEEKLY_REST_45H` (no two reduced weekly rests in a row). Weeks run
Monday to Sunday in the firma's time zone. Severity follows Regulation (EU) 2016/403, simplified to one threshold
table per rule (`HosEngine.severity`). Not modelled yet: compensation of reduced weekly rests, the 2020 exceptions
for international transport, ferry/train interruptions, multi-manning specifics. Violations are recomputed after
every import, assignment or deletion of a file and after a change of a driver's card; the same violation (driver,
rule, start) keeps its acknowledgement.

### Invoices, payments, debts and reminders (`/app/invoices`, `/app/debts`, `/app/reminders`)

Privileges: `finance.invoice/view` (lists, cards, debts), `finance.invoice/manage` (record, change, delete),
`finance.payment/manage` (payments), `finance.reminder/manage` (reminders by hand, the plan). Owner and accountant
have them by default; the API role reads.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/invoices?direction=SALES\|PURCHASE&status=&contractorId=&q=&from=&to=&overdue=&page=&size=` | `{items: Invoice[], total, totals: [{currency, gross, open}]}`, newest issue date first; `q` searches the number and the contractor |
| GET / POST / PUT | `/app/invoices[/{id}]` | `InvoiceDetail`; PUT is a full replacement |
| DELETE | `/app/invoices/{id}` | 204; 409 `error.conflict` with payments (billed orders follow their trips again) |
| POST / DELETE | `/app/invoices/{id}/payments[/{paymentId}]` | `{paidAt, amount, method?: TRANSFER\|CASH\|OFFSET, reference?, note?}` -> 201 `Payment` / 204 |
| GET / POST | `/app/invoices/{id}/reminders` | `Reminder[]` / 201 `Reminder` queued now (open sales invoice, else 409) |
| GET | `/app/debts?asOf=` | `DebtRow[]` per contractor and currency, most overdue first |
| GET / PUT | `/app/reminders/schedule` | `{sendHour, ccEmails[], stopWhenPaid, steps: [{offsetDays, escalate}]}` |

`Invoice = {id, direction, number, contractor: {id, name}, issueDate, dueDate, currency, net, vat, gross,
paidAmount, openAmount, status: OPEN | PARTIAL | PAID | OVERDUE | CANCELLED, daysOverdue, orders: [{id, number}],
trip: {id, number}, remindersOn, lastReminderAt, source: MANUAL, notes, createdAt, updatedAt}`;
`InvoiceDetail` = `Invoice` plus `lines: [{id, seq, description, qty, unit, unitPrice, vatCode, vatRate, net, vat,
gross}]`, `payments: Payment[]` and `reminders: Reminder[]`. Input `{direction?: SALES, number?, contractorId,
issueDate, dueDate?, currency, lines?: [{description, qty, unit?, unitPrice, vatCode?: RATE, vatRate?}], net?, vat?,
gross?, orderIds?, tripId?, remindersOn?, cancelled?, notes?}`. `Payment = {id, paidAt, amount, currency, method,
reference, note, createdAt}`; `Reminder = {id, offsetDays (null = by hand), sentAt, toEmails[], subject, status:
QUEUED | SENT | FAILED, error, createdAt}`; `DebtRow = {contractor, currency, open, overdue, invoices,
oldestDueDate, maxDaysOverdue, buckets: {notDue, d1to30, d31to60, d61to90, over90}}`.

Rules: a sales invoice without a number gets the next one of its issue month (`FV/2026/11/001`); a purchase invoice
needs the supplier's number; numbers are unique per direction (`number: finance.invoice.number_taken`). With lines
the totals are computed (net = qty x unit price, VAT per line for `vatCode` RATE with `vatRate` in percent; ZW
exempt, NP not taxable here, e.g. international transport, OO reverse charge carry no VAT); without lines `net` and
`gross` are required. The due date defaults to the issue date plus the contractor's payment days (14 without).
Only a sales invoice bills orders: of the same contractor, not cancelled, not on another invoice; billed orders are
INVOICED and can no longer be cancelled, orders taken off follow their trip again. The status is computed on today in
the firma's time zone: CANCELLED, PAID (paid >= gross), OVERDUE (due date passed), PARTIAL, OPEN; a payment may not
exceed the open amount (`amount: finance.payment.too_much`) and a cancelled invoice takes none. Reminders go to the
contractor's invoice e-mails in the contractor's language (else the firma's), texts `finance.reminder.mail.*`; without
e-mails a reminder by hand answers 422 `contractor: finance.reminder.no_email`. The plan (default steps -3, +1, +7,
+14 and +30 days from the due date, escalated steps copy the firma's e-mail) runs every hour from its `sendHour` in
the firma's time zone, once per invoice and step; with `stopWhenPaid` a partly paid invoice gets no more plan
reminders. E-mails wait in `email_outbox` and go out every minute once SMTP is configured (`SPRING_MAIL_HOST`,
`SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `RUTERA_MAIL_FROM`); a failure is retried after
5, 15, 45 and 135 minutes, then FAILED. Changes are written to `audit_log` (module `finance`). Not yet: currency
rates (NBP) for PLN VAT, credit notes, issuing PDFs, KSeF.

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
