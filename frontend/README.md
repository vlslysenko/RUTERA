# RUTERA frontend

React + TypeScript + Vite + Mantine web client. Style direction A "Dispatch room" (REQUIREMENTS.md, section 15).

## Run

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, mock API on
npm run typecheck  # tsc -b
npm run build      # tsc -b && vite build -> dist/ (PWA: manifest + service worker)
```

Node 22+ (npm 10+). Dependencies are pinned to exact versions; bump them deliberately.

## Backend connection

- `VITE_API_MOCK=true` (default in dev): an in-browser mock answers `/app/...` (`src/api/mock`). No backend needed.
- `VITE_API_MOCK=false`: the Vite dev server proxies `/app` to `VITE_API_PROXY` (default `http://localhost:8080`).
  Copy `.env.example` to `.env.local` to change it. Production builds never use the mock.

Mock test accounts (login = password): `owner`, `dispatcher`, `logistician`, `accountant` (locked), `driver`
(inactive). Firma "TransPol Sp. z o.o.". Mock users live in memory per page load.

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

The vehicle–driver link lives on the vehicle (`defaultDriverId` in the vehicle form); `Driver.defaultVehicle` is
derived from it. Documents of vehicles and drivers share `components/DeadlineDocuments`.

`User = { id, login, name, role, firma: { id, name } | null, language, startScreen, privileges: string[] }`.
Menu items and screens are shown by `privileges` (`module.entity/action`, see `src/app/navigation.ts` and
`src/auth/privileges.ts`); the superuser has an empty list and bypasses checks. Error bodies carry `code` = i18n
key and, for 422, `details: { field: code }`, which forms map onto their fields (`src/forms/serverErrors.ts`).

## i18n

Same mechanism as the backend: flat keys `module.topic.name`, one dictionary per language, 26 languages
(24 EU + uk + ru). The client loads `/app/i18n/{lang}`; `src/i18n/locales/*.json` are the bundled copies used by
the mock and as the English fallback. UI keys introduced by the frontend must also be added to
`common/src/main/resources/i18n/messages_*.properties` so the backend serves them.

## Structure

```
src/app        theme (tokens of direction A), navigation (groups, roles, start screens), router, query client
src/api        fetch wrapper (/app, CSRF), auth, i18n, fleet; mock/ = in-browser backend for dev
src/auth       session hooks (TanStack Query), RequireAuth, ScreenGuard (role -> screen)
src/layout     AppLayout (AppShell), TopBar, Sidebar, LanguageMenu, UserMenu
src/components DetailDrawer (card in the right panel + "open as page"), PageHeader, StatusChip, Plate, ...
src/pages      Login, Overview, Settings (profile, password, users), Fleet, Drivers (list, card, form), placeholders
src/i18n       i18next setup, language list, locales/
```

Detail cards: a list route has a child `:id` route that renders `<DetailDrawer>` into the right panel; the
sibling route `:id/page` renders the same component with `mode="page"`. Each card has its own URL.
