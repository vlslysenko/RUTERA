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

Settings -> Audit log (`/settings/audit`, `admin.audit/view`, backend 416af80 `GET /app/admin/audit`): who did what
in the firma, newest first, filters in the URL (period in the firma zone, user, module, channel, text), the channel
as a chip; API and MCP are shown as "via AI" with a violet mark on the row. A row opens the entry (details JSON,
IP, request id) in the right panel from the page of the list. Summaries come from the backend in English.

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

Offline (driver cabinet first): the app shell is precached by the service worker; the last answers of the cabinet
(session, trips, tachograph, driver, HR queries) are kept in localStorage (`src/app/offlineCache.ts`, drivers only,
7 days, cleared on sign-out and sign-in) and restored before the first render, so the phone shows the trip and hours
without a connection. A bar says when the device is offline; parts with nothing saved say so instead of loading
forever; queries refetch when the connection is back. Marking a stop done offline is queued with the time of the tap
and sent on reconnect (while the app stays open). The UI dictionaries are kept per language for offline starts. The
"Me" tab offers installation (`beforeinstallprompt`; on iPhone the Share -> Add to Home Screen hint).

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
