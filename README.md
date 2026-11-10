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
| `docker-compose.standalone.yml` | Installation from the released image: MySQL, RUTERA, nightly backups, optional routing (`.env.standalone.example`) |
| `compose.yaml` | Stack built from source (MySQL 8.4 + RUTERA); its `mysql` service alone is the development database |
| `.github/workflows/` | CI (`mvn verify`, client build) and the release of the image to ghcr.io |
| `docs/` | Requirements, DB schema, AI role guides, UI mockups |
| `CHANGELOG.md` | What each release contains (the next one: "Unreleased") |

## Deploy (standalone)

One server with Docker and the Compose plugin, 2 CPU and 4 GB RAM are enough for a small firma
(add ~4 GB for the optional routing). Nothing has to be built: the image comes from the GitHub Container Registry.

```bash
mkdir rutera && cd rutera
curl -fsSLO https://raw.githubusercontent.com/vlslysenko/RUTERA/main/docker-compose.standalone.yml
curl -fsSL -o .env https://raw.githubusercontent.com/vlslysenko/RUTERA/main/.env.standalone.example
#   edit .env: RUTERA_DB_PASSWORD and RUTERA_DB_ROOT_PASSWORD (required), RUTERA_VERSION, RUTERA_TZ, SMTP ...
docker compose -f docker-compose.standalone.yml up -d
docker compose -f docker-compose.standalone.yml logs rutera | grep "generated password"   # first start only
```

RUTERA is then at `http://<host>:18090` (`RUTERA_PORT`, `RUTERA_BIND_ADDRESS`); sign in as `admin` (or
`RUTERA_BOOTSTRAP_ADMIN_LOGIN`) with the generated password, or set `RUTERA_BOOTSTRAP_ADMIN_PASSWORD` before the
first start, and change it afterwards. The first firma comes from `RUTERA_BOOTSTRAP_*`. Every setting is described
in `.env.standalone.example`; lines left commented out keep the built-in defaults.

| Service | Image | Role |
|---|---|---|
| `mysql` | `mysql:8.4.11` | Database, volume `mysql-data`; not published, only the stack reaches it |
| `rutera` | `ghcr.io/vlslysenko/rutera:${RUTERA_VERSION}` | Client and backend on port 8080 inside, published on `RUTERA_PORT`; volumes `conf` (`/opt/rutera/conf`: optional `application.properties`, `logback.xml`), `logs`, `data` (uploaded files) |
| `backup` | `mysql:8.4.11` | Nightly database dump and archive of the uploaded files into `./backups` |
| `valhalla` | `ghcr.io/valhalla/valhalla-scripted:3.9.0` | Truck routing, only with `--profile routing` |

Volumes are named `rutera-standalone_<volume>`; `docker compose ... down` keeps them, `down -v` deletes all data.

**HTTPS.** Put a reverse proxy (Caddy, nginx, Traefik) in front, publish RUTERA only to it
(`RUTERA_BIND_ADDRESS=127.0.0.1` when it runs on the same host), set `RUTERA_COOKIE_SECURE=true`,
`RUTERA_PUBLIC_URL=https://<host>` (the OAuth issuer of the claude.ai connector) and `RUTERA_FORWARD_HEADERS=native`
(client addresses for the sign-in protection; see "Security"). The proxy has
to allow request bodies of 200 MB (tachograph import).

**Paid options.** A standalone installation has no operator console: `RUTERA_AI_ACCESS=true` (public API, MCP
server, claude.ai connector), `RUTERA_PLAN` and `RUTERA_VEHICLE_LIMIT` in `.env` are written to the firma at every
start; left empty, the database keeps its values, which can also be set with SQL:

```bash
docker compose -f docker-compose.standalone.yml exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "UPDATE firma SET ai_access = TRUE"'
```

**Web Push.** Notifications of the driver cabinet need a VAPID key pair of the installation, made once:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
openssl ec -in vapid.pem -outform DER 2>/dev/null | tail -c +8 | head -c 32 | base64 | tr '+/' '-_' | tr -d '=\n'
openssl ec -in vapid.pem -pubout -outform DER 2>/dev/null | tail -c 65 | base64 | tr '+/' '-_' | tr -d '=\n'
```

The first line printed is `RUTERA_PUSH_VAPID_PRIVATE`, the second `RUTERA_PUSH_VAPID_PUBLIC`; set
`RUTERA_PUSH_SUBJECT` to a contact (`mailto:admin@your-firm.example` or the https address). Keep the keys: new keys
invalidate every subscription. The server must reach the push services of the browsers over HTTPS (Google,
Mozilla, Apple), and the web application must be served over HTTPS for browsers to allow push.

**E-mail.** Payment reminders go out once `SPRING_MAIL_HOST` (and port, user, password, STARTTLS or SSL) is set;
until then they wait in the outbox and are sent afterwards.

**Routing.** `docker compose -f docker-compose.standalone.yml --profile routing up -d` and
`RUTERA_ROUTING_URL=http://valhalla:8002` in `.env`. The first start downloads `RUTERA_OSM_EXTRACT_URL` (Poland
~1.9 GB, Geofabrik) and builds the tiles (30-60 minutes); until then route requests answer 502 `calc.routing.failed`.
Geocoding uses the public Nominatim (1 request/s, put a contact into `RUTERA_GEOCODING_USER_AGENT`) unless
`RUTERA_GEOCODING_URL` points at an own instance.

**Update.** Set `RUTERA_VERSION` to the new release (pinning a version is safer than `latest`), then

```bash
docker compose -f docker-compose.standalone.yml run --rm backup now   # a backup first
docker compose -f docker-compose.standalone.yml pull
docker compose -f docker-compose.standalone.yml up -d
```

Flyway migrates the database at start. Going back to an older version needs the backup taken before the update.

**Backups.** The `backup` service writes `rutera-db-<yyyy-mm-dd_hhmm>.sql.gz` (mysqldump in one transaction, RUTERA
keeps running) and `rutera-files-<stamp>.tar.gz` (uploaded files) every night at `RUTERA_BACKUP_TIME` (`RUTERA_TZ`,
default 02:30) into `RUTERA_BACKUP_DIR` (`./backups`) and deletes those older than `RUTERA_BACKUP_KEEP_DAYS` (14).
Both archives are tested after writing; a failed backup leaves `.last-failed` (time and reason) in the folder and
the `backup` service shows as unhealthy in `docker compose ps` until the next good backup.
Copy that folder off the server (rsync, restic, a NAS): a backup on the same disk does not survive a disk failure.
The files hold personal data (drivers, tachograph downloads); keep them encrypted and access-restricted.

```bash
docker compose -f docker-compose.standalone.yml run --rm backup now      # one backup at once
docker compose -f docker-compose.standalone.yml run --rm backup list     # what is there
docker compose -f docker-compose.standalone.yml stop rutera              # restore: RUTERA must be stopped
docker compose -f docker-compose.standalone.yml run --rm backup restore 2026-11-02_0230
docker compose -f docker-compose.standalone.yml start rutera
```

`restore <stamp>` drops and recreates the database from `rutera-db-<stamp>.sql.gz` and replaces the uploaded files
with `rutera-files-<stamp>.tar.gz`; it refuses while RUTERA is still connected to the database.

**Logs and health.** `docker compose -f docker-compose.standalone.yml logs -f rutera`; the files are in the `logs`
volume (daily, 30 days, 1 GB at most); the container output of every service is rotated by Docker (5 x 10 MB). `GET /actuator/health` answers `{"status":"UP"}` (the container health check uses it).

**Releases.** A tag `v<major>.<minor>.<patch>` runs `.github/workflows/release.yml`: CI first, then the image
`ghcr.io/vlslysenko/rutera:<version>` (plus `<major>.<minor>` and `latest`, linux/amd64). Every push and pull
request to `main` runs `.github/workflows/ci.yml` (`mvn verify` with Testcontainers, type check and build of the
client). Base images and actions are pinned to releases older than 30 days; bump them together.

### From source with one command (Docker)

To try a working copy: `docker compose up -d --build` builds the image from the sources (`compose.yaml`, settings
from `.env`, see `.env.example`) and starts MySQL and RUTERA on `http://localhost:18090` (`RUTERA_PORT`; MySQL is
published on `RUTERA_DB_PORT`, default 3308, for development). The owner's generated password:
`docker compose exec rutera grep "generated password" logs/rutera.log`. `docker compose down -v` removes everything
including the data.

## Build and run (development)

Prerequisites: JDK 25, Maven 3.9+, Docker; Node 22+ for the client.

```bash
docker compose up -d mysql    # MySQL 8.4 only, on localhost:3308 (3306 and 3307 are often taken by other MySQL servers)
mvn verify                    # builds common, core and tacho (API only, no client in the jar)
RUTERA_PORT=18082 java -jar core/target/rutera.jar   # default port 8080; any free port works
```

The client runs from the Vite dev server (`cd frontend && npm run dev`, http://localhost:5173), which proxies
`/app` to the backend (`VITE_API_PROXY` in `frontend/.env.local`, e.g. `http://localhost:18082`, see
`frontend/README.md`).
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

`User = {id, login, name, role, firma: {id, name, timezone, driversSeePay} | null, language, startScreen | null,
privileges: string[], driver: {id, name} | null}` (`driver`: the driver record linked to a DRIVER login);
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

### Audit journal (`/app/admin/audit`, privilege `admin.audit/view`)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/admin/audit?from=&to=&userId=&module=&channel=&q=&firmaId=&page=&size=` | `{items: Entry[], total}`, newest first; size 1..200 (default 50) |

`Entry = {id, at, firmaId, userId, userLogin, channel: UI | API | MCP | SYSTEM, module, action, entityType, entityId,
summary, details (JSON) | null, ip, requestId}`. `channel` tells where the action came from: the web application
(UI), the public API or OAuth (API), a tool of the MCP server (MCP), a scheduled job (SYSTEM); API and MCP are the
actions "via AI". `channel` takes one value or a comma list (`channel=API,MCP` = everything via AI). `from` / `to`
are days in the firma's time zone (both included), `module` e.g. `fleet`, `driver`, `trip`, `finance`, `fuel`, `api`,
`operator`; `q` searches summary, action and entity type; an unknown channel is 422 `channel: validation.invalid_value`. Only the own firma: another `firmaId` is 403; the operator (SUPERUSER) passes
`firmaId` or reads all firmas (days in UTC then). Also `GET /api/v1/audit` (MCP tool `audit_list`), the one admin
function of the public API. The journal is never changed or deleted through the API.

### Operator console (`/app/operator/firmas`, role SUPERUSER)

For the operator of a cloud installation: logins with role `SUPERUSER` and no firma (`/app/auth/me` answers
`role: "SUPERUSER"`, `firma: null`). Everyone else gets 403 `auth.access_denied`.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/operator/firmas?q=&page=&size=` | `{items: FirmaItem[], total}` by id; `q` searches name and short code |
| POST | `/app/operator/firmas` | onboarding, see below -> 201 `{firma: FirmaItem, ownerId, ownerLogin, password}` |
| GET | `/app/operator/firmas/{id}` | `FirmaItem` |
| PUT | `/app/operator/firmas/{id}` | `{plan, vehicleLimit, aiAccess, active, expiresAt?}` -> `FirmaItem`; 422 per field (`plan`: letters, digits, `_`, up to 32, upper-cased; `vehicleLimit` 0..100000) |

`FirmaItem = {id, name, shortCode, country, plan, vehicleLimit, aiAccess, active, test, expiresAt, users, vehicles,
createdAt}`; `users` = active logins, `vehicles` = vehicles not deleted. A firma with `active: false` or a past
`expiresAt` cannot sign in (`auth.login.firma_inactive`; open sessions end on their next request). Changes are
written to the firma's audit log (module `operator`). `vehicleLimit` is shown, not enforced.

Onboarding body: `{name, shortCode, country, lang? (en), timezone? (UTC), baseCurrency? (EUR), plan? (STANDARD),
vehicleLimit? (10), aiAccess?, test?, expiresAt?, owner: {login, firstName, lastName?, password?}}`. The short code
(letters, digits, `-`, `_`, 2 to 32, upper-cased) is unique (`shortCode: operator.firma.short_code_taken`), the
owner's login too (`owner.login: admin.user.login_taken`); per-field codes as in user administration. Without
`owner.password` a 16-character password is generated and returned in `password` this once (never logged); a given
password is checked like any other (`owner.password: validation.too_short`) and not echoed (`password: null`). The operator account:
`RUTERA_BOOTSTRAP_OPERATOR_LOGIN` (and `RUTERA_BOOTSTRAP_OPERATOR_PASSWORD`, else generated and logged once like the
owner's) creates the SUPERUSER at start when that login does not exist yet; empty (the default) creates nothing. A
standalone installation sets these options from its environment instead (`RUTERA_AI_ACCESS` and the others win
again at every start when set).

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
`lastDownloadAt` (written by the tachograph import); the number loses spaces and hyphens, is upper-cased, and is
unique in the firma (`cardNumber: driver.card.number_taken`). `defaultVehicle` is the first vehicle (by plate)
whose `defaultDriverId` is this driver. `nextDocument` is the earliest `validTo` among the documents and the card
(`kind: DRIVER_CARD`). Changes are written to `audit_log` (module `driver`).

### Driver cabinet (logins of drivers)

A login is linked to a driver with `userId` in the driver input (`POST`/`PUT /app/drivers`; a login of the firma
with the role DRIVER, else `userId: validation.invalid_value`; linked to one driver only: `userId:
driver.user.taken`; null unlinks). `GET /app/auth/me` then carries
`driver: {id, name}` (null for other logins). With `driver_app.self/use` (role DRIVER) and without the full privilege
a login reads only its own driver, on the usual endpoints: `GET /app/drivers/{own}` and its `/documents`,
`/absences`, `/days?month=`; `GET /app/trips?driverId={own}` (driverId required) and `GET /app/trips/{id}` of trips
where it is MAIN or SECOND driver; `PATCH /app/trips/{id}/stops/{stopId}` with DONE or SKIPPED on those trips;
`GET /app/tacho/activity` and `/app/tacho/violations` with `driverId={own}`. Anything else answers 403
`auth.access_denied`; pay rates and payments of the own driver only when the firma allows it (`driversSeePay`).

Absence requests: the driver's `POST /app/drivers/{own}/absences` creates an absence with `status: REQUESTED`
(entries of the office are APPROVED at once). The office decides with `PATCH /app/drivers/{id}/absences/{absenceId}`
`{status: APPROVED | REJECTED, note?}` (`driver.absence/manage`; `approvedBy`/`approvedAt` = who decided and when,
`decisionNote`); the driver may withdraw its request while REQUESTED (`DELETE`, else 409). REQUESTED and APPROVED
absences block overlapping ones (`driver.absence.overlap`), REJECTED do not; only APPROVED ones count in the
working-day calendar. `GET /app/absences` and `/app/drivers/{id}/absences` filter with `status=`.

Trip files (photos and documents from the road):

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/trips/{id}/files` | `[{id, kind, note, stop: {id, seq}, fileName, contentType, size, uploadedBy: {id, name}, createdAt}]` |
| POST | `/app/trips/{id}/files` | multipart: `file`, `kind?` (CMR_PHOTO, DELIVERY_NOTE, RECEIPT, OTHER), `note?`, `stopId?` -> 201 |
| GET | `/app/trips/{id}/files/{fileId}/content` | the file inline |
| DELETE | `/app/trips/{id}/files/{fileId}` | 204 |

The office (`trip.trip/view` to read, `trip.trip/manage` or `trip.cmr/upload` to upload, `trip.trip/manage` to
delete) sees all files; a driver only those of trips where it is on the crew, uploads while the trip is not DONE or
CANCELLED (409 `trip.file.trip_closed`) and deletes only its own uploads then. JPEG, PNG, WebP, HEIC and PDF by
content (`file: trip.file.type`), at most 15 MB (`file: trip.file.too_large`).

Firma settings (`admin.firma/manage`): `GET /app/admin/firma` -> `{id, name, shortCode, country, vatId, address,
email, lang, timezone, baseCurrency, plan, vehicleLimit, aiAccess, driversSeePay}`; `PUT` the editable part `{name,
vatId?, address?, email?, lang, timezone, baseCurrency, driversSeePay?}` (plan, vehicle limit and the AI access add-on
belong to billing). `/app/auth/me` carries `firma.driversSeePay`.

Offline outbox of the cabinet: `POST /app/trips/{id}/files` and `POST /app/drivers/{id}/absences` accept an
`Idempotency-Key` header (1-64 letters, digits, `-`, `_`; a uuid). The first request with a key runs as usual and
its status and body are kept for 24 hours; the same key from the same user then gets the same answer
(`Idempotency-Replayed: true`) and nothing is created again. While the first request still runs, another one gets
409 `request.in_progress`; the same key on another path is 409 `error.conflict`; 5xx answers are not kept. Without
the header (or with a malformed one) nothing changes; another user's key is a new request. Table
`idempotency_record`.

#### Web Push (`/app/push`)

Notifications to the phones and browsers of a user, also while RUTERA is closed (standard Web Push with VAPID, no
paid service: the browser vendor's push service delivers them). Switched on by the server's VAPID keys
(`RUTERA_PUSH_VAPID_PUBLIC`, `RUTERA_PUSH_VAPID_PRIVATE`, contact `RUTERA_PUSH_SUBJECT`, see "Deploy (standalone)");
without them every endpoint answers 404 `push.disabled`.

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/push/key` | `{publicKey}`: the `applicationServerKey` for `PushManager.subscribe` (base64url, uncompressed P-256) |
| POST | `/app/push/subscriptions` | `PushSubscription.toJSON()`: `{endpoint, keys: {p256dh, auth}}` -> 204; upsert by endpoint for the current user (a device that changes user moves over); at most 10 devices per user, the oldest go; 422 `endpoint` (https URL, up to 1000 characters), `keys.p256dh` (65-byte P-256 point), `keys.auth` (16 bytes) |
| DELETE | `/app/push/subscriptions` | `{endpoint}` -> 204, also when unknown |
| POST | `/app/push/test` | `{sent, failed}`: a test notification to the caller's own devices |

Any signed-in user of a firma (`profile.self/manage`). Notifications go to the login linked to a driver, never to
the user who made the change: a trip created with the driver on the crew (`trip.assigned`), a saved trip (added:
`trip.assigned`, removed: `trip.unassigned`, staying: `trip.changed`, status CANCELLED: `trip.cancelled` for the
whole crew), the office's decision on an absence request (`absence.approved`, `absence.rejected`). The payload,
encrypted per device (RFC 8291, aes128gcm), is `{type, title, body, url, tag}`: title and body in the user's
language (`notify.*` keys), times in the firma's zone, `url` relative (`/driver`, `/driver/me`; the test notification `/`, the start screen of the role), `tag` `trip-<id>` or
`absence-<id>` (also the push `Topic`, so an undelivered older one is replaced). They are sent after the commit by
one background thread; a failing push service never fails the request. 404 / 410 from the push service remove the
device, so do 10 failures in a row. Table `push_subscription`.

Reminders of expiring documents go out the same way (`core.push.DeadlineNotices`, every hour at minute 20, from
`RUTERA_PUSH_DEADLINE_HOUR`, default 8, for 12 hours of the firma's time zone). An item gets one notification per
lead time (`RUTERA_PUSH_DEADLINE_DAYS`, default `30,14,7,1`; 20 days left belongs to the 30-day one, and a day
missed is made up the next time):

| `type` | Item | To | `url` | `tag` |
|---|---|---|---|---|
| `deadline.document` | driver document | the login of the driver | `/driver/documents` | `deadline-document-<id>` |
| `deadline.card` | driver card | the login of the driver | `/driver/documents` | `deadline-card-<id>` |
| `deadline.vehicle_document` | vehicle document | owners and dispatchers | `/fleet/<vehicleId>` | `deadline-vehicle-<id>` |

The `remind_days` of a document drops the longer lead times and is a lead time itself (a document with 60 also
reminds 60 days ahead, whatever the list says); 0 switches its reminders off. Only the current item counts: the
newest card of a driver, and per kind (except `OTHER`) the document with the latest date. Expired items, inactive
drivers and inactive vehicles get nothing. Table `push_deadline_sent` keeps what went out per item, date and lead
time, so a new date starts again. Nothing is recorded while no recipient has a device, so whoever subscribes later
still gets the current reminder. Texts: `notify.deadline.*`.

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
belongs to and `lastDownloadAt`; the facts read from it and the 561/2006 check are in the next section. The parser is
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
| GET / POST | `/app/fuel/cards` | `FuelCard[]` / 201 `FuelCard` |
| PUT / DELETE | `/app/fuel/cards/{id}` | `FuelCard` / 204; input `{provider, cardNumber, vehicleId?, driverId?, validTo?, active?, notes?}`; DELETE leaves its transactions without a card |

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
| POST | `/app/calc/route` | `{points: [{lat, lng} \| {address}] (2-25), vehicleId?, profile?: {heightM, widthM, lengthM, weightT, axleLoadT, axles, hazmat}, avoidTolls?, avoidFerries?}` -> `Route` |
| GET | `/app/calc/toll-tariffs` | `TollTariff[]` by country, newest `validFrom` first |
| POST / PUT / DELETE | `/app/calc/toll-tariffs[/{id}]` | `{country, euroClass?, axlesMin?, weightMinKg?, ratePerKm, currency, validFrom?, notes?}` -> `TollTariff` / 204 |

Sample tariffs of 11 countries for 2026 (204 rows, with the official source of every class and how to import them
through the API) are in `docs/samples` (`toll-tariffs-2026.json`, see its README).

`TripInput = {start (instant), vehicleId?, driverIds?: [id, id?], crewSize?: 1 | 2 (default: number of drivers or
1), driverState?: TACHO | FRESH | MANUAL (default TACHO with drivers, else FRESH), manualState?: {drivingSinceBreakMin,
drivingInShiftMin, shiftStart, weekDrivingMin, fortnightDrivingMin, extensionsLeft, reducedRestsLeft,
lastWeeklyRestEnd} (for every driver), legs: [{name?, distanceKm, durationMin? (else distance / avgSpeedKmh),
countries?: [{country, km, tollKm?}] (must add up to the distance within 1 km; else all km in options.country or the
firma's country; tollKm, at most km, are the km on toll roads, else all km count), serviceMin? (loading or unloading at the end of the leg)}], options?: {avgSpeedKmh? (70), extendedDriving?
(true), reducedRests? (true), reducedWeeklyRest? (false), consumptionL100km?, fuelPrice?, fuelCurrency?, country?,
otherCosts?: [{label, amount, currency?}]}}`.

`Plan = {start, eta, totals: {distanceKm, drivingMin, breakMin, restMin, workMin, durationMin, dailyRests,
weeklyRests, days}, timeline: [{kind: DRIVE | BREAK | DAILY_REST | REDUCED_DAILY_REST | WEEKLY_REST |
REDUCED_WEEKLY_REST | SERVICE, start, end, minutes, kmFrom, kmTo, legIndex, driver: 1 | 2 | null}], driverStates:
[{driverId, source, dataUntil, drivingSinceBreakMin, drivingInShiftMin, shiftStart, weekDrivingMin,
fortnightDrivingMin, extensionsLeft, reducedRestsLeft, weeklyRestDueAt}], costs: {lines: [{kind: FUEL | DRIVER_KM |
DRIVER_PER_DIEM | DRIVER_BASE | TOLL | OTHER, label, country, quantity, unit, rate, amount, currency}], totals:
[{currency, amount}], missing: [code]}, payHidden, warnings: [code]}`.

`Route = {distanceKm, durationMin, points: [{lat, lng, label}], legs: [{from, to, distanceKm, durationMin, countries:
[{country, km, tollKm}], polyline}], polylinePrecision: 6, attribution, truck: {heightM, widthM, lengthM, weightT, axleLoadT,
axles, hazmat}}`. Truck routes come from a Valhalla server
(`RUTERA_ROUTING_URL`; MIT licence, OSM data under ODbL: show the `attribution` "© OpenStreetMap contributors" with the
map), addresses are geocoded with Nominatim (`RUTERA_GEOCODING_URL`, `rutera.geocoding.user-agent`; at most one
request per second, answers cached; the public instance only for development). The truck costing gets the
request's `profile`, else the vehicle card (height, gross weight, axles), else the EU limits of Directive 96/53/EC
for the kind: a tractor (and no vehicle at all) as a combination with a semi-trailer, 4.0 m, 2.55 m, 16.5 m, 40 t
whatever the card says (it holds the tractor alone), 11.5 t per axle, the axles and height of its default trailer
added when known, else 5 axles; a truck 4.0 m, 2.55 m, 12 m, 18 t, 11.5 t, 2 axles; a van 2.7 m, 2.1 m, 6 m, 3.5 t,
2 axles. Valhalla keeps to the OSM restrictions for them (maxweight, maxheight, maxwidth, maxlength, maxaxleload,
hgv, hazmat); `truck` in the answer is what was used. Km per country come from the edges of the route.
`breakdown: {countries: [{country, km, tollKm}], eta, days, costs: {lines, totals, missing}, payHidden}` is what the
route costs: the trip calculator on its legs (the body may add `start` (default now), `driverIds` (up to 2; their
tachograph state, and driver pay lines when the user has `driver.payment/view`) and `options` as in `/calc/trip`;
a point's `serviceMin` is the loading or unloading there), so FUEL per country, TOLL per country on its toll km and
the driver lines are the same as in a plan of the same legs. Toll km
of a country are its edges flagged as toll in OSM (concession motorways) plus the road classes of its truck toll
network (`core.calc.TollNetwork`, 2026): DE, HU, DK, BG motorway, trunk and primary roads; PL (e-TOLL A/S), AT, CZ,
SK, BE, NL, SI motorways and expressways; CH every road; elsewhere only the flagged roads. National roads that are
only partly in a network (PL GP/G, CZ and SK first class) are left out.
Errors: 503 `calc.routing.disabled` (no routing server configured), 502 `calc.routing.failed` /
`calc.geocode.failed` (server unreachable), 422 `points[i]: calc.geocode.not_found`, `route: calc.route.not_found`
(no truck route), `points: validation.required | validation.invalid_value` (fewer than 2 or more than 25 points).
`GET /app/calc/places?q=&limit=5` (`trip.trip/view`) suggests places while typing: `[{label, lat, lng, country}]`
from a Photon server (`RUTERA_GEOCODER_URL`; self-hosted, or https://photon.komoot.io for development under its
fair use; public Nominatim forbids autocomplete). Fewer than 3 characters answer `[]`; `limit` 1-10, default 5;
labels in the user's language where Photon has it (en, de, fr), else the local names. Answers are cached for a day,
calls to the server spread to `RUTERA_GEOCODER_MAX_PER_SECOND` (default 5; a caller waits up to a second, else 429
`api.rate_limited`); no server configured 503, server down 502, both `calc.geocode.failed`. The picked `lat`/`lng` go
to `/calc/route` as points, which skips geocoding for them. In
`TripInput`, `waypoints: [{lat, lng} | {address}, serviceMin?]` replace `legs`: the route's legs become the legs, a
waypoint's `serviceMin` the loading or unloading there, its countries the toll km. Development server: `docker
compose --profile routing up -d valhalla` downloads the OSM extract (`RUTERA_OSM_EXTRACT_URL`, Poland by default)
and builds the tiles at the first start.

Breaks and rests (`core.calc.plan`, pure Java): the state of each driver comes from the merged tachograph activity
of the last 35 days (gaps and the time up to the start count as rest), FRESH = just after a regular weekly rest, or
MANUAL. The planner drives while every limit allows and then inserts what the binding limit needs: a 45 min break
after 4.5 h, a daily rest after 9 h (10 h while extensions are left and allowed) or when the 24 h shift window would
close (11 h rest, 9 h while reductions are left and allowed; loading or unloading counts inside the shift and moves
behind the rest when it would not fit), a weekly rest when it is due (144 h after the last one) or the 56 h / 90 h
driving is used up (45 h, 24 h when allowed, longer until Monday when only the new week frees the driving). A crew
of two swaps at the wheel, co-driving counts as the passive driver's break and the crew rests 9 h within 30 h. Not
modelled: split daily rest, ferries and trains, compensation of reduced weekly rests, opening hours of loading places.

Costs: fuel = km x consumption (options, else the vehicle's `avgL100km`) / 100 x the net DIESEL price per litre
(without VAT: carriers deduct it), per country of the route with its km ("as driven"): the options' `fuelPrice` for
the whole trip (`MANUAL`); else the firma's own fuel card purchases in that country in the last 30 days (`OWN_TX`,
the currency with the most litres; a purchase's net is its net, else gross minus VAT, else gross without the
country's VAT rate), else the reference price of the country (`REFERENCE`, `fuel_price_ref`: the newest on or before
the start day, a national average before a cap), else the firma's average over all purchases in its base currency
(`OWN_TX_ALL`). One FUEL line without a country when every country got the same price, else one per country; a FUEL
line's `source` is that quality (`MISSING` without a price) and `priceDate` the day of the price. The reference
prices start with the EU Weekly Oil Bulletin of 2026-11-02 for diesel in the 27 EU countries (V18, CC BY 4.0, per
litre in each country's currency with gross, VAT rate and excise). `GET /app/calc/fuel-prices` (`trip.trip/view`)
lists them; only the operator writes them (shared by all firmas): `POST /app/calc/fuel-prices` `{country, product:
DIESEL | ADBLUE | PETROL | LNG | CNG, priceDate, net (or gross with vatRate), gross?, vatRate?, excisePer1000l?,
currency, kind?: AVERAGE | CAP | STATION_MEDIAN, source? (MANUAL)}` replaces the row of that country, product, source
and day, `DELETE /app/calc/fuel-prices/{id}`. Price sources plug in as `core.calc.FuelPriceProvider` beans (the
provider contract of the fuel research: name, countries, kind NATIONAL | STATION, `fetch(since)` -> `PriceQuote`);
`FuelPriceImporter` stores the quotes of the national ones daily at 04:40 UTC. Built in: `EU_WOB`
(`core.calc.EuOilBulletin`), the Commission's page (`RUTERA_FUEL_BULLETIN_URL`) links the current "prices with
taxes" and "duties and taxes" workbooks; diesel and Euro-super 95 of the 27 countries per litre in EUR, net = gross
without the VAT rate of the duties file, the excise for euro countries; it replaces the starting rows of the same
day (source `EU_WOB`), so their currency becomes EUR. Needs internet; `RUTERA_FUEL_BULLETIN_ENABLED=false` switches
it off. Not yet: exchange rates and a total in the base currency, AdBlue, the tax calendar; per driver
with the pay rate valid on the start day: `perKm` x km, per diem (abroad when any km is outside the firma's
country, else home) x started 24 h periods, `monthlyBase` / 30 x the same days; tolls = toll km per country x the
matching tariff (rows whose axles and weight conditions hold for the truck as routed, so a tractor with its
semi-trailer and the EU limits for an empty card, and whose Euro class is the card's; the most specific row, then
the latest `validFrom`); other
costs as given. A crew of two: DRIVER_KM counts the km of each driver's own DRIVE items; a crew slot without a
chosen driver gets no pay lines and no missing code. Units of the cost lines: FUEL `L`, DRIVER_KM and TOLL `KM`, DRIVER_PER_DIEM and DRIVER_BASE `DAY`;
`days` = started 24 h periods from the start to the arrival (at least 1). Loading before the first drive: a first
leg with `distanceKm: 0` and `serviceMin`. `timeline.driver` and `driverStates` follow the crew order (`driverIds[0]`
= 1, `driverIds[1]` = 2; a crew slot without an id is planned FRESH). No currency conversion: totals per currency.
`missing`: `calc.missing.consumption`, `calc.missing.fuel_price`, `calc.missing.pay_rate`,
`calc.missing.toll_tariff` (the TOLL line of that country has no rate), `calc.missing.tacho_data` (the driver is
planned as FRESH); `warnings`: `calc.warning.tacho_old` (newest record older than 24 h),
`calc.warning.weekly_rest_needed`. Routing is pluggable (`core.calc.RouteProvider`): `POST /app/calc/route` computes
the legs with Valhalla when `rutera.routing.url` is set, otherwise the legs come with the request. Nothing is saved
yet (trip_plan and `trip.cost_*` come later).

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Public API (`/api/v1`, paid AI access add-on)

The same resources as the web application, for scripts and integrations, available while the firma has the AI
access add-on (`firma.ai_access`). Personal tokens act as their user with the user's current privileges, inside the
user's firma; auth, profile, administration and the i18n dictionaries are not part of the public API. The MCP
server for AI assistants offers the same operations as tools, with the same tokens or OAuth (see below).
Actions are written to `audit_log` with the channel they came through: `API` for the public API and the OAuth
endpoints, `MCP` for tool calls of the MCP server (these are the actions "via AI"), `UI` for the web application and
`SYSTEM` for scheduled jobs.

Tokens (web application, session):

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/me/tokens` | `Token[]` of the signed-in user (`profile.self/manage`) |
| POST | `/app/me/tokens` | `{name, expiresAt?}` -> 201 `Token` with `token` (shown only here); 403 `api.ai_access_required` without the add-on; at most 10 active personal tokens per user (`name: api.token.limit`) |
| DELETE | `/app/me/tokens/{id}` | 204: revoked (for an OAuth connection: the client is disconnected) |
| GET | `/app/me/tokens/{id}/calls?page=&size=` | `{items: [{at, method, path, status, durationMs, ip}], total}`, newest first (own tokens, or any with `admin.api_token/manage`) |
| GET / DELETE | `/app/admin/tokens[/{id}]` | all tokens of the firma with `user: {id, name}` / revoke (`admin.api_token/manage`) |

`Token = {id, kind, name, prefix, user, createdAt, expiresAt, lastUsedAt, revokedAt, active, token}`. `kind`:
`PERSONAL` (created here) or `OAUTH` (the connection of an OAuth client such as claude.ai, named after the client,
`prefix` "OAuth", `expiresAt` = end of its refresh token). A personal token is `rtr_` plus 43 random base64url
characters; only its first 12 characters (`prefix`) and its SHA-256 hash are stored.

Calls: `Authorization: Bearer rtr_...` on every request; no session, no CSRF. Answers and errors are the ones of the
web API (`{code, message, details}`): 401 `auth.unauthenticated` (no, unknown, expired or revoked token, or a user or
firma that can no longer sign in), 403 `api.ai_access_required` (no add-on), 403 `auth.access_denied` (privilege),
429 `api.rate_limited` with `Retry-After` (fixed window per token, `RUTERA_API_RATE_PER_MINUTE`, default 120 per
minute). Every call with a known token is written to `api_call` (token, user, method, path, status, duration, IP).

Resources (paths, bodies and privileges as described in the sections above, `/app` replaced by `/api/v1`):
`/api/v1/vehicles` (the web path is `/app/fleet/vehicles`), `/drivers` with absences, days, pay rates and payments,
`/absences`, `/payments`, `/contractors`, `/orders`, `/trips` with their files, `/cmr`, `/invoices`, `/debts`,
`/reminders/schedule`, `/fuel/transactions`, `/fuel/cards`, `/fuel/imports`, `/fuel/mappings`, `/calc/trip`,
`/calc/route`, `/calc/toll-tariffs`, `/audit` (read-only, `admin.audit/view`),
`/tacho/files`, `/tacho/activity`, `/tacho/violations`. The OpenAPI 3 document of these paths with the bearer scheme
is public at `GET /api/v1/openapi.json` (springdoc-openapi 3.0.3).

## MCP server for AI assistants (`/mcp`)

RUTERA speaks the Model Context Protocol (Streamable HTTP transport, protocol versions 2025-06-18, 2025-03-26 and
2024-11-05) at `POST /mcp`, with the same personal tokens, AI access add-on, rate limit and call log as the public
API. Every JSON operation of `/api/v1` is a tool, generated from the OpenAPI document at startup of the first call:
new endpoints become tools without code. A tool call runs the same `/api/v1` request with the caller's token, so
validation, privileges and answers are identical; the result carries the JSON answer as text and as
`structuredContent` (a list as `{items}`), and `isError` for 4xx/5xx with the `{code, message, details}` body. Left
out: binary and multipart operations (CMR PDF, tachograph and trip file contents, tachograph, fuel and trip file
uploads). Internal endpoints (sign-in, profile, administration, tokens, firma settings, operator console) are not
part of `/api/v1`, so they are neither in the OpenAPI document nor tools; reading the audit journal is the
exception (`audit_list`).

Tool names: the resource path joined with `_` plus `list`, `get`, `create`, `update`, `patch` or `delete`
(`vehicles_list`, `drivers_absences_create`, `invoices_payments_delete`), actions by their name (`cmr_issue`,
`cmr_cancel`, `cmr_from_order`, `cmr_from_trip`, `orders_create_trip`, `calc_trip`, `calc_route`,
`drivers_absences_decide`, `tacho_violations_ack`, `tacho_violations_unack`, `fuel_transactions_assign`,
`reminders_schedule_get`, `tacho_activity_get`). The input is
`{<path parameters>, <query parameters>, body}`; descriptions name the REST operation and the allowed values of the
coded fields (statuses, VAT codes, products, providers, kinds). Annotations: `readOnlyHint` for GET,
`destructiveHint` for DELETE, `idempotentHint` for GET, PUT and DELETE.

Resources: the AI role guides of `docs/ai-roles` (packaged into the jar): `rutera://guide` is the guide of the
caller's role (owner, dispatcher, logistician, accountant, driver; other roles get the README), and
`rutera://guides/{readme|owner|dispatcher|logistician|accountant|driver}` every guide, all `text/markdown`. The
`initialize` instructions point the assistant at `rutera://guide`.

Protocol: JSON-RPC 2.0, one message or a batch per POST; `initialize`, `ping`, `tools/list`, `tools/call`,
`resources/list`, `resources/read` (unknown URI: -32002), `resources/templates/list` (empty);
notifications are answered with 202. No sessions (`Mcp-Session-Id`) and no server-initiated stream: `GET` and
`DELETE /mcp` answer 405. Errors: -32700 parse error, -32600 invalid request, -32601 unknown method, -32602 unknown
tool or missing path argument, -32603 internal error.

How to connect (a token from Settings, the firma needs the AI access add-on; the server must be reachable from the
assistant, in development `http://localhost:18082/mcp`):

```
claude mcp add --transport http rutera https://rutera.example.com/mcp --header "Authorization: Bearer rtr_..."
```

Other MCP clients with a JSON configuration:

```json
{"mcpServers": {"rutera": {"type": "http", "url": "https://rutera.example.com/mcp",
                           "headers": {"Authorization": "Bearer rtr_..."}}}}
```

The server is built without Spring AI: its MCP starter needs Spring Boot 4.1, RUTERA runs on 4.0.

### Connect Claude with OAuth (claude.ai connector)

RUTERA is an OAuth 2.1 authorization server for MCP clients, as the MCP authorization specification describes:
nobody copies a token. On claude.ai: Settings -> Connectors -> Add custom connector, URL `https://<host>/mcp`, then
Connect: the browser opens the RUTERA page, the user signs in (or an open RUTERA session is reused), sees the
application, the account and the return address, and allows it. The connection then appears in the profile under
API tokens (`kind: OAUTH`, named after the client) and is ended there. claude.ai calls RUTERA from Anthropic's
servers, so the host must be reachable from the internet over HTTPS; set `RUTERA_PUBLIC_URL` to that address (the
issuer and every published URL derive from it, otherwise from the request). On a LAN or in development Claude Code
does the same flow from the user's machine:

```
claude mcp add --transport http rutera http://localhost:18082/mcp      # then /mcp -> rutera -> Authenticate
```

| Method | Path | Purpose |
|---|---|---|
| GET | `/.well-known/oauth-protected-resource[/mcp \| /api/v1]` | Protected resource metadata (RFC 9728): `resource`, `authorization_servers` |
| GET | `/.well-known/oauth-authorization-server` | Authorization server metadata (RFC 8414) |
| POST | `/oauth/register` | Dynamic client registration (RFC 7591), JSON; at most 20 per address and hour |
| GET / POST | `/oauth/authorize` | Sign-in and consent page (session, CSRF), redirect with `code`, `state`, `iss` |
| POST | `/oauth/token` | Form: `authorization_code` (`code`, `redirect_uri`, `client_id`, `code_verifier`) or `refresh_token` |
| POST | `/oauth/revoke` | Form: `token` (access or refresh), `client_id` (RFC 7009) |

- A 401 from `/mcp` or `/api/**` carries `WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/
  oauth-protected-resource/mcp"` (`/api/v1` for the API; with `error="invalid_token"` when a token was sent), where
  MCP clients discover the authorization server.
- Registration: `redirect_uris` (1 to 10) must be `https` URIs or `http` on the loopback interface (localhost,
  127.0.0.1, [::1]), without a fragment; `token_endpoint_auth_method` `none` (default, public client with PKCE),
  `client_secret_post` or `client_secret_basic` (a secret `rts_...` is returned once); grant types
  `authorization_code` and `refresh_token`, response type `code`. Registrations never used within 7 days are removed.
- Authorization: `response_type=code`, PKCE with `code_challenge_method=S256` required, `redirect_uri` exactly as
  registered, `resource` (RFC 8707) must be on this server, `scope` is ignored: there is one scope, `rutera`, the
  user's own privileges. An unknown client or redirect URI is shown on the page and never redirected to; other faults
  return `error=invalid_request | unsupported_response_type | invalid_target`, Deny returns `error=access_denied`.
  Only users of a firma with the AI access add-on can allow; the page says why otherwise. The page has no scripts,
  is never framed (CSP `frame-ancestors 'none'`) and speaks the language of the user or the browser (`oauth.*` keys).
- Tokens: the code lives 60 seconds and works once; a second use revokes the connection made from it. Access token
  `rta_...` for one hour, refresh token `rtf_...` for 30 days after the last refresh; both rotate on every refresh, the
  old ones stop working. Errors: `{error, error_description}` with `invalid_request`, `invalid_client` (401),
  `invalid_grant`, `unsupported_grant_type`. A refresh fails once the user cannot sign in any more or the add-on is
  off. Authorizing the same client again ends the user's previous connection with it.
- Storage: `oauth_client` (registrations), `oauth_code` (codes, SHA-256), and the connection is an `api_token` row
  of kind `OAUTH` (`token_hash` = current access token, `refresh_hash`), so the add-on gate, the rate limit and the
  call log are those of personal tokens.

## Security

| Topic | Behaviour | Settings |
|---|---|---|
| Sign-in per account | after 5 failed passwords in a row the account is locked for 15 minutes (401 `auth.login.locked`); a success resets the counter | `RUTERA_LOCKOUT_ATTEMPTS`, `RUTERA_LOCKOUT_DURATION` |
| Sign-in per address | after 20 failed sign-ins (unknown login, wrong password, locked account) from one client address within 15 minutes, that address is blocked for 15 minutes from then: 429 `auth.login.throttled` with `Retry-After`, whatever login it tries; audited once as `login_throttled`; IPv6 counts per /64 network; nothing is locked permanently | `RUTERA_LOGIN_IP_MAX_FAILURES`, `RUTERA_LOGIN_IP_WINDOW` |
| Client address behind a proxy | the address, scheme and host come from `X-Forwarded-*` only with `RUTERA_FORWARD_HEADERS=native`; set it only when RUTERA is reachable through the proxy alone, otherwise clients could fake their address; without it all clients behind the proxy share one address in the throttle (a warning is logged once when requests carry `X-Forwarded-For`) | `RUTERA_FORWARD_HEADERS` (default none) |
| Session | cookie `RUTERA_SESSION`: HttpOnly, SameSite=Lax, Secure with `RUTERA_COOKIE_SECURE=true`; new session id and CSRF token at every sign-in; ends after 4 hours without requests and 12 hours after the sign-in at the latest (then 401 `auth.unauthenticated`); API tokens and OAuth connections have their own lifetimes and are not sessions | `RUTERA_SESSION_TIMEOUT`, `RUTERA_SESSION_MAX_AGE`, `RUTERA_COOKIE_SECURE` |
| CSRF | cookie `XSRF-TOKEN` mirrored in `X-XSRF-TOKEN` (forms: `_csrf`) on every state-changing request of the session | - |
| Headers of the web application | `Content-Security-Policy` (same origin; inline styles; images also from `tile.openstreetmap.org`; service worker and manifest from the origin; PDFs from blob URLs for the browser's viewer; `frame-ancestors 'none'`), `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` (camera and location for the origin only), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, HSTS on HTTPS; the OAuth page sends its own stricter policy | `RUTERA_CSP` (a whole policy, e.g. to allow another tile server) |
| Actuator | only `/actuator/health` is exposed and public; details for signed-in users | - |
| Uploads | every upload is checked by its content, not its name: tachograph files by the parser (rejected files are not stored), trip files by their signature (JPEG, PNG, WebP, HEIC, PDF), fuel exports as CSV or XLSX (zip signature, 200 MB uncompressed at most, binary XLS refused); sizes: 25 MB per file, 200 MB per request, 20 MB per tachograph file, 15 MB per trip file; stored files are served with their checked type and `nosniff` | - |
| API and MCP | bearer tokens and OAuth access tokens (SHA-256 in the database), 120 calls per token and minute, every call logged; actions in the audit journal with channel API or MCP | `RUTERA_API_RATE_PER_MINUTE` |

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
