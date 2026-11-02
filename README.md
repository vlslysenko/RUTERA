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

### CMR consignment notes (`/app/cmr`)

Privileges: read with `trip.order/view` or `trip.cmr/manage`, everything else `trip.cmr/manage`.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/cmr?status=&orderId=&tripId=&q=&from=&to=&page=&size=` | `{items: Cmr[], total}`, newest first; from/to bound the loading date; `q` searches number, parties and places |
| GET / POST | `/app/cmr[/{id}]` | `Cmr` |
| PUT / DELETE | `/app/cmr/{id}` | full replacement / 204, only while DRAFT (else 409) |
| POST | `/app/cmr/from-order/{orderId}` | 201 `Cmr`: a draft prefilled from the order |
| POST | `/app/cmr/from-trip/{tripId}` | 201 `Cmr[]`: one draft per order of the trip (without orders: one from its first LOADING and last UNLOADING stop) |
| POST | `/app/cmr/{id}/issue` | `Cmr` ISSUED: number frozen, PDF of the four copies stored; 422 when boxes are missing |
| POST | `/app/cmr/{id}/cancel` | `Cmr` CANCELLED (409 when already cancelled) |
| GET | `/app/cmr/{id}/pdf?copies=1..4` | `application/pdf`: a draft is drawn now with a DRAFT watermark and `copies` pages; issued and cancelled notes return the stored PDF |

`Cmr = {id, number, status: DRAFT | ISSUED | CANCELLED, order: {id, number}, trip: {id, number}, sender, senderContractorId,
consignee, consigneeContractorId, carrier, successiveCarriers[], deliveryPlace, deliveryCountry, loadingPlace,
loadingCountry, loadingDate, documentsAttached, lines: [{marks, packages, packing, nature, statNumber, grossWeightKg,
volumeM3}], senderInstructions, paymentTerms: PAID | FORWARD | null, paymentInstructions, cashOnDelivery: {amount,
currency} | null, carrierReservations, specialAgreements, establishedPlace, establishedDate, vehiclePlates,
signatures: {sender, carrier, consignee: {name, place, date}}, notes, hasPdf, issuedAt, createdAt, updatedAt}`; a party
is `{name, address (line breaks allowed), country, vatId}`. The input has the same fields (ids instead of `order` and
`trip`: `orderId`, `tripId`; `paymentTerms` as text) and is a full replacement.

Rules: an empty number gets the next one of the loading year (`CMR/2026/0001`, the current year without a loading
date); typed numbers are upper-cased and unique in the firma (`number: trip.cmr.number_taken`). Countries are
ISO alpha-2 (`sender.country`, `deliveryCountry`, ... `validation.invalid_format`). Prefill from an order: sender =
the customer (name, address, country, VAT id), consignee = the unloading place, carrier = the own firma, places,
countries and the loading date from the order, one goods line with cargo and weight, box 21 = town of loading and
the loading date, plates = vehicle / trailer of the order's trip. Issuing needs `sender.name`, `carrier.name`,
`consignee.name` (or a delivery place), `loadingPlace`, `deliveryPlace` and a goods line with `nature`. The PDF
(`core.trip.CmrPdf`, Apache PDFBox 3.0.7) draws the 24 boxes of the international form with English, Polish and
German captions; font Rubik (SIL Open Font License 1.1, `core/src/main/resources/fonts`, licence note in NOTICE.txt),
characters outside it print as `?`. Changes are written to `audit_log` (module `trip`). Not yet: scans with OCR,
e-CMR, linking a CMR to an invoice.

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

### Fuel cards and imports of provider exports (`/app/fuel/...`)

Privileges: `fuel.transaction/view` reads transactions, cards and the import history; `fuel.import/manage` imports,
undoes imports, edits cards, mappings and transactions (owner and accountant by default; dispatcher reads).

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/fuel/imports/preview` | multipart: part `file`, form fields `provider?`, `mapping?` (JSON `{field: header}`), `options?` (JSON) -> `Preview` |
| POST | `/app/fuel/imports` | same fields plus `saveMapping?` (default true) -> 201 `FuelImport` |
| GET | `/app/fuel/imports?page=&size=` | `{items: FuelImport[], total}`, newest first |
| GET / DELETE | `/app/fuel/imports/{id}` | `FuelImport` / 204 (its transactions and the stored file go; cards stay) |
| GET / PUT | `/app/fuel/mappings/{provider}` | `{provider, columns: {field: header}, options: {dateFormat, decimalComma, currency}}` |
| GET | `/app/fuel/transactions?from=&to=&vehicleId=&driverId=&cardId=&tripId=&importId=&product=&country=&unmatched=&q=&page=&size=` | `{items: FuelTransaction[], total, totals: [{currency, net, vat, gross}], quantities: [{product, unit, quantity}]}`, newest first; from/to are days in the firma zone; `unmatched=true` = no vehicle |
| GET / POST | `/app/fuel/transactions[/{id}]` | `FuelTransaction`; POST enters one by hand (source MANUAL) |
| PUT | `/app/fuel/transactions/{id}` | full replacement of a MANUAL transaction; 409 for imported ones |
| PATCH | `/app/fuel/transactions/{id}` | `{vehicleId, driverId, tripId, notes}` replaces the assignment of any transaction (match MANUAL, or NONE when cleared) |
| DELETE | `/app/fuel/transactions/{id}` | 204 |
| GET / POST / PUT / DELETE | `/app/fuel/cards[/{id}]` | `FuelCard`; input `{provider, cardNumber, vehicleId?, driverId?, validTo?, active?, notes?}`; DELETE leaves its transactions without a card |

Providers: DKV, UTA, SHELL, BP, ORLEN, E100, AS24, OMV, OTHER. Fields (keys of `mapping`, `values` and row errors):
cardNumber, plate, date, time, transactionId, station, country, product, quantity, unit, unitPrice, net, vat, gross,
currency, odometer. Required columns: date, product, gross or net, cardNumber or plate.

`Preview = {provider (given, else detected from the file name and first rows, else OTHER), headerRow (1-based),
headers[] in file order, mapping: {field: header | null} for every field, missing: [field], options, dataRows,
rows: [{row (1-based), values: {at, cardNumber (masked), plate, transactionId, station, country, product (group),
productRaw, quantity, unit, unitPrice, net, vat, gross, currency, odometer}, errors: {field: code}}]}` (first 10
data rows). `FuelImport = {id, provider, fileName, periodFrom, periodTo, rowsTotal, rowsNew, rowsDup, rowsError,
status: DONE, errors: [{row, field, code}] (first 100), mapping: {field: header}, importedBy: {id, name},
createdAt}`. `FuelTransaction = {id, at, provider, externalId, card: {id, masked}, vehicle: {id, name = plate},
driver: {id, name}, trip: {id, name = number}, station, country, product: DIESEL | ADBLUE | PETROL | LNG | CNG |
TOLL | SERVICE | OTHER, productRaw, quantity, unit: L | KG | PCS, unitPrice, net, vat, gross, currency, odometerKm,
plateRaw, match: CARD | PLATE | MANUAL | NONE, source: IMPORT | MANUAL, importId, notes, createdAt}`. `FuelCard = {id,
provider, cardNumberMasked, vehicle: {id, name}, driver: {id, name}, validTo, active, notes, createdAt, updatedAt}`;
the card number is write-only: only the masked form and a SHA-256 hash (for matching) are stored, a PUT without
`cardNumber` keeps it (a new provider needs the number), a known number answers `cardNumber: fuel.card.number_taken`.

Reading: CSV (separator `;` `,` or tab detected, quotes, UTF-8 with or without BOM, UTF-16 with BOM, else
Windows-1250) or XLSX (first sheet; date-formatted cells become `yyyy-MM-dd HH:mm:ss`); old binary `.xls` is rejected;
at most 20 MB and 50 000 rows. The header row is the one among the first 15 where most columns are recognised (at
least three); the given mapping, else the firma's saved mapping of the provider, overrides the recognition by
header names (English, German, Polish, French and other common names). A given mapping is merged field by field over
the saved or recognised one; a field mapped to null or "" is left out. Dates: `yyyy-MM-dd`, `dd.MM.yyyy`,
`dd/MM/yyyy`, `dd-MM-yyyy`, `yyyy/MM/dd`, two-digit years, with `HH:mm[:ss]` in the same cell or the time column;
`options.dateFormat` (a Java `DateTimeFormatter` pattern) overrides the guess. The time is the station's local time
read in the firma's time zone and stored as an instant. Amounts: spaces and apostrophes are ignored; with both `,`
and `.` the last one is the decimal separator; a lone comma is decimal unless `options.decimalComma=false`, a lone
point unless `options.decimalComma=true`; two of net, vat and gross give the third. The currency comes from the
column, else `options.currency`, else the firma's base currency. The product group is guessed from the product
text (diesel, ON, gazole, AdBlue, Maut, wash and so on), the unit defaults to L for liquid fuels and KG for gas.

Rules: every row has a duplicate key `externalId` = the provider's transaction id (when the export has one) plus a
hash of card, plate, time, product, quantity and amount, unique per firma and provider: importing a file again only
counts duplicates. A card number not seen before creates a card (linked to the vehicle of the row's plate). Vehicle:
the card's vehicle (match CARD), else the vehicle with the row's plate (PLATE, spaces and hyphens ignored); trip: that
vehicle's trip whose actual (else planned) period contains the time; driver: the card's driver, else the trip's main
driver. Setting a vehicle or driver on a card fills its transactions that have none. Row errors use the field keys
with `validation.required`, `validation.invalid_format` or `validation.invalid_value`; file errors (422 `details`):
`file: validation.required | fuel.import.unreadable | fuel.import.too_large | fuel.import.columns_missing` (with
`columns.<field>: validation.required` per missing field), `provider: validation.invalid_value`, `mapping` or
`options: validation.invalid_format`. Changes are written to `audit_log` (module `fuel`). Not yet: provider APIs,
VAT/excise refund reports, consumption per 100 km, `trip.cost_fuel`.

### Trip calculator (`/app/calc/trip`, `/app/calc/toll-tariffs`)

Privileges: `trip.trip/view` calculates and reads tariffs, `trip.trip/manage` edits tariffs; driver pay lines need
`driver.payment/view` (otherwise they are left out and `payHidden` is true).

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/calc/trip` | `TripInput` -> `Plan` |
| GET | `/app/calc/toll-tariffs` | `TollTariff[]` by country, newest `validFrom` first |
| POST / PUT / DELETE | `/app/calc/toll-tariffs[/{id}]` | `{country, euroClass?, axlesMin?, weightMinKg?, ratePerKm, currency, validFrom?, notes?}` -> `TollTariff` / 204 |

`TripInput = {start (instant), vehicleId?, driverIds?: [id, id?], crewSize?: 1 | 2 (default: number of drivers or
1), driverState?: TACHO | FRESH | MANUAL (default TACHO with drivers, else FRESH), manualState?: {drivingSinceBreakMin,
drivingInShiftMin, shiftStart, weekDrivingMin, fortnightDrivingMin, extensionsLeft, reducedRestsLeft,
lastWeeklyRestEnd} (for every driver), legs: [{name?, distanceKm, durationMin? (else distance / avgSpeedKmh),
countries?: [{country, km}] (must add up to the distance within 1 km; else all km in options.country or the firma's
country), serviceMin? (loading or unloading at the end of the leg)}], options?: {avgSpeedKmh? (70), extendedDriving?
(true), reducedRests? (true), reducedWeeklyRest? (false), consumptionL100km?, fuelPrice?, fuelCurrency?, country?,
otherCosts?: [{label, amount, currency?}]}}`.

`Plan = {start, eta, totals: {distanceKm, drivingMin, breakMin, restMin, workMin, durationMin, dailyRests,
weeklyRests, days}, timeline: [{kind: DRIVE | BREAK | DAILY_REST | REDUCED_DAILY_REST | WEEKLY_REST |
REDUCED_WEEKLY_REST | SERVICE, start, end, minutes, kmFrom, kmTo, legIndex, driver: 1 | 2 | null}], driverStates:
[{driverId, source, dataUntil, drivingSinceBreakMin, drivingInShiftMin, shiftStart, weekDrivingMin,
fortnightDrivingMin, extensionsLeft, reducedRestsLeft, weeklyRestDueAt}], costs: {lines: [{kind: FUEL | DRIVER_KM |
DRIVER_PER_DIEM | DRIVER_BASE | TOLL | OTHER, label, country, quantity, unit, rate, amount, currency}], totals:
[{currency, amount}], missing: [code]}, payHidden, warnings: [code]}`.

Breaks and rests (`core.calc.plan`, pure Java): the state of each driver comes from the merged tachograph activity
of the last 35 days (gaps and the time up to the start count as rest), FRESH = just after a regular weekly rest, or
MANUAL. The planner drives while every limit allows and then inserts what the binding limit needs: a 45 min break
after 4.5 h, a daily rest after 9 h (10 h while extensions are left and allowed) or when the 24 h shift window would
close (11 h rest, 9 h while reductions are left and allowed; loading or unloading counts inside the shift and moves
behind the rest when it would not fit), a weekly rest when it is due (144 h after the last one) or the 56 h / 90 h
driving is used up (45 h, 24 h when allowed, longer until Monday when only the new week frees the driving). A crew
of two swaps at the wheel, co-driving counts as the passive driver's break and the crew rests 9 h within 30 h. Not
modelled: split daily rest, ferries and trains, compensation of reduced weekly rests, opening hours of loading places.

Costs: fuel = km x consumption (options, else the vehicle's `avgL100km`) / 100 x price (options, else the average
net price per litre of the firma's DIESEL fuel transactions of the last 30 days in the base currency); per driver
with the pay rate valid on the start day: `perKm` x km, per diem (abroad when any km is outside the firma's
country, else home) x started 24 h periods, `monthlyBase` / 30 x the same days; tolls = km per country x the
matching tariff (rows whose Euro class, axles and weight conditions hold for the vehicle; a condition on a value
the vehicle lacks, or without a vehicle, does not hold; the most specific row, then the latest `validFrom`); other
costs as given. A crew of two: DRIVER_KM counts the km of each driver's own DRIVE items; a crew slot without a
chosen driver gets no pay lines and no missing code. Units of the cost lines: FUEL `L`, DRIVER_KM and TOLL `KM`, DRIVER_PER_DIEM and DRIVER_BASE `DAY`;
`days` = started 24 h periods from the start to the arrival (at least 1). Loading before the first drive: a first
leg with `distanceKm: 0` and `serviceMin`. `timeline.driver` and `driverStates` follow the crew order (`driverIds[0]`
= 1, `driverIds[1]` = 2; a crew slot without an id is planned FRESH). No currency conversion: totals per currency.
`missing`: `calc.missing.consumption`, `calc.missing.fuel_price`, `calc.missing.pay_rate`,
`calc.missing.toll_tariff` (the TOLL line of that country has no rate), `calc.missing.tacho_data` (the driver is
planned as FRESH); `warnings`: `calc.warning.tacho_old` (newest record older than 24 h),
`calc.warning.weekly_rest_needed`. Routing is pluggable (`core.calc.RouteProvider`); until a routing server exists
the legs come with the request. Nothing is saved yet (trip_plan and `trip.cost_*` come later).

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
