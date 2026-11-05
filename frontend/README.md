# RUTERA frontend

React + TypeScript + Vite + Mantine web client. Style direction A "Dispatch room" (REQUIREMENTS.md, section 15).

## Run

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, mock API on
npm run lint       # eslint . (typescript-eslint, react-hooks, react-refresh; eslint.config.js)
npm run typecheck  # tsc -b
npm run build      # tsc -b && vite build -> dist/ (PWA: manifest + service worker)
```

Node 22+ (npm 10+). Dependencies are pinned to exact versions; bump them deliberately. CI runs `lint`, `typecheck` and `build`.

## Backend connection

- `VITE_API_MOCK=true` (default in dev): an in-browser mock answers `/app/...` (`src/api/mock`). No backend needed.
- `VITE_API_MOCK=false`: the Vite dev server proxies `/app`, `/api`, `/mcp`, `/oauth` and `/.well-known` to
  `VITE_API_PROXY` (default `http://localhost:18082`; ports 8080-8089 belong to SMDP).
  Copy `.env.example` to `.env.local` to change it. Production builds never use the mock.

Mock test accounts (login = password): `owner`, `dispatcher`, `logistician`, `accountant` (locked), `driver`
(driver cabinet of Jan Kowalski), `operator` (SUPERUSER without a firma: operator console). Firma "TransPol Sp. z o.o.". Mock users live in memory per page load.

Internal endpoints used (all under `/app`, session cookie + CSRF header `X-XSRF-TOKEN` from cookie `XSRF-TOKEN`;
the backend clears the cookie on logout, the client fetches a new one with `GET /app/auth/me` and retries once):

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/auth/login` | `{ login, password }` -> `User`; 401 `{ code: "auth.login.invalid" }` |
| POST | `/app/auth/logout` | 204 |
| GET | `/app/auth/me` | `User`; 401 `{ code: "auth.unauthenticated" }` |
| PUT | `/app/me/settings` | `{ language?, startScreen? }` -> `User` |
| PUT | `/app/me/password` | `{ currentPassword, newPassword }` -> 204; 422 details per field |
| GET | `/app/i18n/{lang}` | flat JSON dictionary |
| GET/POST | `/app/admin/users` | `AdminUser[]` / create (`admin.user/view`, `admin.user/manage`) |
| GET/PUT/DELETE | `/app/admin/users/{id}` | card / full update / soft delete |
| PUT | `/app/admin/users/{id}/password`, POST `.../unlock` | 204 |
| GET/POST | `/app/fleet/vehicles` | `Vehicle[]` sorted by plate / create (`fleet.vehicle/view`, `fleet.vehicle/manage`) |
| GET/PUT/DELETE | `/app/fleet/vehicles/{id}` | card / full update (`VehicleInput`) / soft delete; 422 `plate: fleet.vehicle.plate_taken` |
| GET/POST | `/app/fleet/vehicles/{id}/documents` | `VehicleDocument[]` ordered by `validTo` / create (`fleet.document/manage`) |
| PUT/DELETE | `/app/fleet/vehicles/{id}/documents/{docId}` | full update / hard delete |
| GET/POST | `/app/drivers` | `Driver[]` sorted by last name / create (`driver.driver/view`, `driver.driver/manage`) |
| GET/PUT/DELETE | `/app/drivers/{id}` | card / full update (`DriverInput`, current tachograph card inline) / soft delete |
| GET/POST, PUT/DELETE | `/app/drivers/{id}/documents`, `.../{docId}` | driver documents with deadlines |
| GET/POST, PUT/DELETE | `/app/drivers/{id}/absences`, `.../{absenceId}` | `Absence[]` newest first / change (`driver.absence/manage`); 422 `toDate: driver.absence.overlap` |
| GET | `/app/absences?from&to&kind&driverId` | absences of the firma overlapping the range (always pass one) |
| GET, PUT/DELETE | `/app/drivers/{id}/days?month=YYYY-MM`, `.../days/{date}` | `{days, summary}` / manual day `{kind, note}` or back to computed (`driver.driver/manage`) |
| GET/POST, PUT/DELETE | `/app/drivers/{id}/pay-rates`, `.../{rateId}` | pay rates (`driver.payment/view`, `/manage`); 422 `validFrom: driver.pay_rate.date_taken` |
| GET/POST, PUT/DELETE | `/app/drivers/{id}/payments?from&to`, `.../{paymentId}` | payments of a driver (YYYY-MM months) |
| GET | `/app/payments?period&driverId&kind` | `{items, totals}` of the firma for a month; advances and deductions count negative |
| POST | `/app/tacho/files` | multipart `files` (1..50, 20 MB each) -> `TachoImportResult[]` (IMPORTED / DUPLICATE / REJECTED) |
| GET | `/app/tacho/files`, `/app/tacho/files/{id}` | journal `{items, total}` with filters / `TachoFileDetail` (`tacho.hos/view` or `tacho.file/upload`) |
| GET | `/app/tacho/files/{id}/content` | original bytes (download) |
| PUT, POST, POST, DELETE | `.../{id}/assignment`, `.../{id}/driver`, `.../{id}/vehicle`, `.../{id}` | assign by hand, create a driver / vehicle from the file, delete |
| GET | `/app/tacho/activity?driverId&from&to` | `{driver, timezone, days: HosDay[], intervals}` (`tacho.hos/view`, up to 31 days) |
| GET | `/app/tacho/violations`, `/app/tacho/violations/{id}` | `{items: Violation[], total}` with filters / one violation |
| POST/DELETE | `/app/tacho/violations/{id}/ack` | mark as reviewed `{note?}` / undo (`tacho.violation/ack`) |
| GET/POST, GET/PUT/DELETE | `/app/contractors`, `.../{id}` | `Contractor[]` (`?q&role=CUSTOMER\|CARRIER`) / card; read with `trip.order/view` or `trip.contractor/manage` |
| GET/POST, GET/PUT/DELETE | `/app/orders`, `.../{id}` | `{items: Order[], total}` (`?status&customerId&q&from&to&page&size`) / card (`trip.order/view`, `trip.order/manage`) |
| POST | `/app/orders/{id}/trip` | `{vehicleId, trailerId?, mainDriverId?, secondDriverId?, plannedStart?}` -> 201 `Trip` (both manage keys) |
| GET/POST, GET/PUT/DELETE | `/app/trips`, `.../{id}` | `{items: Trip[], total}` (`?status&vehicleId&driverId&q&from&to&page&size`) / card; PUT replaces crew, orders and stops |
| PATCH | `/app/trips/{id}/stops/{stopId}` | `{status, actualAt?}` -> `TripStop` (quick marks on the trip card) |
| GET/POST, GET/PUT/DELETE | `/app/invoices`, `.../{id}` | `{items, total, totals}` (`?direction&status&contractorId&q&from&to&overdue&page&size`) / `InvoiceDetail` with lines, payments, reminders |
| POST/DELETE | `/app/invoices/{id}/payments[/{paymentId}]` | `{paidAt, amount, method?, reference?, note?}` (`finance.payment/manage`); 422 `amount: finance.payment.too_much` |
| GET/POST | `/app/invoices/{id}/reminders` | reminders / one now (`finance.reminder/manage`); 422 `contractor: finance.reminder.no_email`, 409 when not an open sales invoice |
| GET | `/app/debts?asOf` | `DebtRow[]` per contractor and currency with aging buckets |
| GET/PUT | `/app/reminders/schedule` | `{sendHour, ccEmails, stopWhenPaid, steps: [{offsetDays, escalate}]}` |
| POST | `/app/fuel/imports/preview`, `/app/fuel/imports` | multipart `file` + fields `provider`, `mapping` and `options` (JSON strings), `saveMapping` -> `Preview` / 201 `FuelImport` |
| GET/DELETE | `/app/fuel/imports[/{id}]` | history / undo (its transactions go) |
| GET/POST, PUT/PATCH/DELETE | `/app/fuel/transactions[/{id}]` | `{items, total, totals, quantities}`; PUT only for MANUAL ones, PATCH `{vehicleId, driverId, tripId, notes}` for any |
| GET/POST, GET/PUT/DELETE | `/app/fuel/cards[/{id}]` | `FuelCard` with `cardNumberMasked`; `cardNumber` is write-only (null on PUT keeps it) |

Tachograph screens are tabs: `/tacho/files` (upload and journal), `/tacho/activity?driver=&week=` (a week of one
driver against 561/2006) and `/tacho/violations` (filters in the URL, review in the right panel). Day strips and
violation times are shown in the firma time zone (`timezone` of the activity, `User.firma.timezone`), the zone the
backend cuts days in.

Trips are tabs too: `/trips` (trips; card with stops and quick marks, status buttons Start / Finish / Cancel as a PUT
of the whole trip), `/trips/orders` (orders; card with "create trip") and `/trips/contractors` (customers and
carriers). Each tab needs its own privilege; users without `trip.trip/view` land on the first tab they may open.
Stop errors come as `stops[2].address` and land on the field `stops.2.address` of the trip form.
`/trips/calc` is the trip calculator (`POST /app/calc/trip`): legs with km, driving and loading time and countries, a
crew of one or two, the drivers' state from the tachograph, rested or typed in; the plan shows the ETA, the timeline of
driving, breaks and rests (561/2006) and the cost lines. The route panel (`RoutePanel`, Leaflet 1.9.4 loaded only with it, OSM
tiles with "© OpenStreetMap contributors") takes points as addresses or clicks on the map, asks `POST /app/calc/route`
(avoid tolls / ferries, the chosen vehicle) and fills the legs with km per country; 503 `calc.routing.disabled` shows
"enter the legs by hand". The mock routes with a small gazetteer; `sessionStorage['mock.routing'] = 'off'` imitates the
disabled server. `calc.missing.*` / `calc.warning.*` codes live only in the
frontend bundles. `/trips/tolls` keeps the toll rates per km (country, optional euro class, axles and weight).
`/trips/cmr` lists CMR consignment notes (`/app/cmr`, read with `trip.order/view` or `trip.cmr/manage`); the card shows
the boxes with their numbers of the international form, issues and cancels the note and opens or saves the PDF (drafts
with 1-4 copies and a DRAFT watermark). The editor (drafts only) is the right panel with all boxes; `?order=` /
`?trip=` narrow the list. Order and trip cards list their notes and "create CMR" (`from-order`, `from-trip`).

Finance is tabs too: `/finance` (invoices, sales or purchase; card with lines, payments and reminders; form with
lines `vatCode` RATE / ZW / NP / OO, or plain totals without lines), `/finance/debts` (aging per contractor) and
`/finance/reminders` (the reminder plan). Line errors come as `lines[2].qty` and land on `lines.2.qty`.

Fuel is tabs too: `/fuel` (transactions with sums per currency and quantities per product; card with the assignment
set by hand), `/fuel/imports` (wizard: file -> preview with the column of each field and read options, checked again
on the server -> result; history with undo) and `/fuel/cards`. Receipt times are shown and typed in the firma time zone
(`src/utils/zone.ts`).

Drivers are tabs as well: `/drivers` (list and cards), `/drivers/absences` (who is absent, drivers × days of a month)
and `/drivers/payments` (payroll of a month, `driver.payment/view` only). The driver card has the tabs overview,
documents, absences, work days (month calendar with manual corrections) and pay (rates and payments of a year);
`?tab=` in the URL opens a card on a tab.

Settings -> API tokens (`/settings/tokens`, `/app/me/tokens`) lists the own personal tokens of the public API and
the MCP server, creates one (the whole token is shown once, with a copy button; 403 `api.ai_access_required` without
the add-on), revokes it and shows its call log; with `admin.api_token/manage` the tab "All API tokens"
(`/settings/api-tokens`) lists and revokes every token of the firma. The page links `/api/v1/openapi.json` and shows
the `claude mcp add` command; the dev server proxies `/api` and `/mcp` to the backend as well.
Rows of kind `OAUTH` are apps connected over OAuth (MCP clients after the consent page of the backend): they are
shown as "Connected apps" with a disconnect button instead of revoke.

Audit log: see "Audit screens" below (backend 416af80, `GET /app/admin/audit`).

Operator console `/operator` (backend 8f6708c, `/app/operator/firmas`): for role SUPERUSER only, who has no firma
and therefore sees just this screen, profile and password. Lists every firma (search by name or short code) with
plan, vehicles against the limit, active logins, AI access, state (active / blocked / expired, test) and access
end; the card edits plan, vehicle limit, AI access, access on/off and "access until" (the end of that day in UTC,
empty = no end) with PUT. "New company" (`/operator/new`, backend 2d16fdc) creates a firma with its first owner;
an empty owner password makes the backend generate one, shown once with a copy button.

The driver cabinet `/driver` is a separate shell for the phone (tabs Today / Hours / Documents / Me at the bottom, no
side menu); users with role DRIVER start there. It reads the driver linked to the login (`User.driver` from
`/app/auth/me`, set by `userId` in the driver form) through the existing endpoints with that driver's id: trips
(`?driverId=`, stop marks), `/tacho/activity` and violations, the driver with documents, absences, work days and
payments; `driver_app.self/use` opens them for the own driver only (backend 4dfa4cb). Step 2 (backend
3828f0c): the driver asks for an absence (REQUESTED, withdrawable until decided; "Me" tab), the office approves or
rejects it in the absences screen (list of open requests) or the driver card (`PATCH .../absences/{id}`), only
APPROVED ones fill the calendars; trip files (`/app/trips/{id}/files`: camera or file from the cabinet's trip card, a
"Documents and photos" section in the office trip card); the pay block of the cabinet shows when
`firma.driversSeePay`. Settings -> Company (`/settings/firma`, `admin.firma/manage`) edits the firma (name, VAT id,
address, e-mail, document language, time zone, base currency, drivers see pay); plan, vehicle limit and AI access are
read-only. The mock implements all of it (`withPrivilegeOrSelf`).

### Offline and installation

The app shell (JS, CSS, fonts, icons, `index.html`) is precached by the service worker; server routes (`/app`,
`/api`, `/mcp`, `/oauth`, `/.well-known`, `/actuator`) are never answered from it.

Offline data (`src/app/offlineCache.ts`):

- **Who:** only users who use the driver cabinet (role DRIVER with `driver_app.self/use`). Office users keep nothing
  on the device.
- **What:** answers of the queries whose key starts with `auth` (the session), `trips`, `tacho`, `drivers` or `hr`,
  as long as they have data (also when their last refresh failed). Paused mutations are not stored. Everything is
  JSON from the API (no dates as objects, no files: downloads and PDFs are not queries).
- **Where and how long:** localStorage `rutera.offline.v1`, written about a second after each change, kept 7 days.
  Quota or private-mode errors are ignored (the app works online); a damaged or old saved state is dropped at start.
- **Restore:** before the first render. Restored answers are shown at once and always asked again (the session
  first) as soon as there is a connection; when `/app/auth/me` answers with another user (or none), everything
  restored is dropped.
- **Cleared:** on sign-out, on every sign-in, when the saved state is older than 7 days, and when the current user is
  not a cabinet user.
- **Known edge case:** with two tabs open, a tab that still holds a signed-out driver's data in memory can write it
  back until its own session check runs; on the next start that data is dropped because the session no longer
  matches.

Connection state comes from TanStack Query's `onlineManager` (set from `navigator.onLine` at start, then the
online/offline events). Offline, queries pause instead of failing; a bar says so in the cabinet, the office layout and
the login page; parts with nothing saved say "no connection" instead of loading forever; a failed refresh keeps the
data already on screen; everything stale refetches when the connection is back. Marking a stop done offline keeps
the time of the tap and is sent on reconnect while the app stays open (it is not stored across a reload). Sign-in
fails at once offline. The UI dictionary of each language used is kept in localStorage (`rutera.i18n.<lang>`) for
offline starts.

Offline outbox (`src/app/outbox.ts`, `public/sw-extra.js`): in the cabinet, a trip file (photo) or an absence request
made without a connection, or whose request got no answer, is kept in IndexedDB (`rutera-outbox`, files as Blobs)
and sent later: by the page on start, on reconnect, on return to the app and on "Send now"; by the service worker on
Background Sync (Chromium; it checks `/app/auth/me` and reads the CSRF cookie through `cookieStore`, otherwise it
leaves the work to the page). An item is claimed in one IndexedDB transaction before sending, so the page and the
service worker never send it twice at once; a claim older than two minutes counts as lost. Every try carries the
item id as `Idempotency-Key`. Network errors, 5xx, 408, 429 and a stale CSRF token keep the item waiting; 401 waits
for the next sign-in of the same user; other refusals (422, 403, 404, 409) mark it "not sent" with the reason, with
"Try again" and "Remove". The bar under the cabinet header lists everything waiting; the trip files and the absence
list show their waiting items too. Signing out with unsent items asks first and then deletes them from the phone.

Web Push (`src/app/push.ts`, card in the cabinet's "Me" tab, handlers in `public/sw-extra.js`; backend `/app/push`):
the card is shown only where push can work (a service worker is active, `GET /app/push/key` answers, on iPhone only
for the installed app; otherwise it says how to install). Turning it on asks for permission, subscribes with the
server's VAPID key (a subscription made with an older key is replaced) and posts it; off deletes it on the server and
in the browser; "Send a test notification" calls `POST /app/push/test`. On cabinet start an existing subscription is
posted again, so it follows the driver signed in now; signing out turns push off for the phone. The service worker
shows `{type, title, body, url, tag}` and opens or focuses RUTERA at `url` on a click. The dev server has no service
worker, so the card stays hidden there (the mock answers `push.disabled`). Deadline reminders (`deadline.document`,
`deadline.card`, `deadline.vehicle_document`) use the same payload, so nothing in the service worker depends on the
type. Owners and dispatchers get the reminders of vehicle documents, so the same switch is in Settings -> Profile
(`<PushCard office />`, any device); the office app also binds an existing subscription to the user at start and turns
push off on sign-out. A replay that hits the first try still
running (409 `request.in_progress`) waits and is tried again.

Installation: `src/app/install.ts` catches `beforeinstallprompt` at start (imported in `main.tsx`); the cabinet's
"Me" tab shows "Install RUTERA on this phone" with a button where the browser offers it, the Share -> Add to Home
Screen hint on iPhone/iPad, and nothing when the app already runs installed.

### Tooltips

Theme defaults in `src/app/theme.ts` (`events` hover, focus and touch; multiline, `maw` 300). Three shapes, all texts in
`hint.*` keys: `StatusChip` has a `hint` prop for status chips; `LabelHint` (`src/components/LabelHint.tsx`) puts an (i)
beside a field or switch label, and a tap on it does not reach the `<label>`, so a phone neither opens the keyboard nor
flips the switch; a plain `Tooltip` wraps an action button with a lasting effect. `DateField` has a `hint` prop for
date filters. An icon-only button needs `aria-label` as well as its `Tooltip`.

### Session end, sign-in throttle, CSP

- Any 401 means the session ended (12 h after sign-in or 4 h idle): the session is asked again once and the login
  page follows, instead of each part showing an error (`src/app/queryClient.ts`).
- 429 `auth.login.throttled` (20 failed sign-ins from one address in 15 min): the login page shows the message and,
  from `Retry-After`, about how many minutes are left (`ApiError.retryAfter`). The mock imitates it.
- The served app runs under the backend's CSP (`script-src 'self'`, `connect-src 'self'`, map tiles from
  `tile.openstreetmap.org` only): no inline scripts, fonts are bundled, zod runs with `jitless` so it never probes
  `new Function()`.

### Audit screens

- `/settings/audit`: tab "Audit log" for `admin.audit/view` (owners by default) in a firma; the entry panel is
  `/settings/audit/:id`.
- `/operator/audit`: the same list for the operator (SUPERUSER) across firmas, with a firma column and filter
  (`?firma=<id>`, then days are cut in that firma's zone, otherwise in UTC); each firma card links to its log; the
  entry panel is `/operator/audit/:id`.

Both use `AuditLog` (`src/pages/settings/audit/AuditPage.tsx`) on `GET /app/admin/audit`. The "Via AI" channel filter sends
`channel=API,MCP` (the backend accepts a comma list); API and MCP can also be chosen alone. Filters live in the URL;
API and MCP entries are marked "via AI" (chip and a violet mark on the row). The entry panel takes the entry from the
current page of the list (there is no single-entry endpoint). Summaries and action codes come from the backend
untranslated.

The vehicle–driver link lives on the vehicle (`defaultDriverId` in the vehicle form); `Driver.defaultVehicle` is
derived from it. Documents of vehicles and drivers share `components/DeadlineDocuments`.

`User = { id, login, name, role, firma: { id, name, timezone } | null, language, startScreen, privileges: string[] }`.
Menu items and screens are shown by `privileges` (`module.entity/action`, see `src/app/navigation.ts` and
`src/auth/privileges.ts`); the superuser has an empty list and bypasses checks. Error bodies carry `code` = i18n
key and, for 422, `details: { field: code }`, which forms map onto their fields (`src/forms/serverErrors.ts`).

## i18n

Same mechanism as the backend: flat keys `module.topic.name`, one dictionary per language, 26 languages
(24 EU + uk + ru). The client loads `/app/i18n/{lang}`; `src/i18n/locales/*.json` are the bundled copies used by
the mock and as the English fallback. UI keys introduced by the frontend must also be added to
`common/src/main/resources/i18n/messages_*.properties` so the backend serves them.

Dates are entered with `DateField` / `FormDateField` (`src/forms/DateField.tsx`, Mantine `DateInput`), not
`<input type="date">`: the field shows and accepts the UI language's short format (dayjs `L`: `05.11.2026` in
Polish, `11/05/2026` in English; `YYYY-MM-DD` is accepted too) and keeps form values as `YYYY-MM-DD`
(`YYYY-MM-DDTHH:mm` with `withTime`). The calendar language follows the UI (`src/i18n/DatesLocaleProvider.tsx`,
dayjs locales loaded on demand).

## Structure

```
src/app        theme (tokens of direction A), navigation (groups, roles, start screens), router, query client
src/api        fetch wrapper (/app, CSRF), auth, i18n, fleet; mock/ = in-browser backend for dev
src/auth       session hooks (TanStack Query), RequireAuth, ScreenGuard (role -> screen)
src/layout     AppLayout (AppShell), TopBar, Sidebar, LanguageMenu, UserMenu
src/components DetailDrawer (card in the right panel + "open as page"), PageHeader, StatusChip, Plate, ...
src/pages      Login, Overview, Settings (profile, password, users), Fleet, Drivers, Tachograph, Trips, Finance, Fuel, placeholders
src/forms      DateField, number fields, server validation errors -> form fields
src/i18n       i18next setup, language list, locales/, dayjs locale for date pickers
```

Detail cards: a list route has a child `:id` route that renders `<DetailDrawer>` into the right panel; the
sibling route `:id/page` renders the same component with `mode="page"`. Each card has its own URL.
