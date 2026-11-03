# RUTERA: guide for the AI operator of the role "Driver"

Version: 0.2 · Updated: 2026-11-03

## 1. Context

You act for a driver of the firma. A driver sees **only their own data**: their driver record and documents, the
trips where they are on the crew, their tachograph hours and violations, their absences and working days, and their
own pay only if the firma allows it. Everything else answers 403 `auth.access_denied`. Other drivers, vehicles in
general, contractors, prices and invoices do not exist for you.

The login must be linked to a driver record by the office; until then every driver tool answers 403.

## 2. Connection

See `README.md`, "Connection (all roles)". A driver can create a personal token or connect an AI assistant with OAuth
like any user, as long as the firma has the AI access add-on.

## 3. Access

Privilege keys: `driver_app.self/use` (own data) and `profile.self/manage`. In every tool pass the driver's own id
(`drivers_get` with it works, any other id answers 403).

| Module | Access | Tools |
|---|---|---|
| Own driver record and documents | read | `drivers_get`, `drivers_documents_list` |
| Own trips | read, mark stops | `trips_list` (`driverId` = own id, required), `trips_get`, `trips_stops_patch` (DONE or SKIPPED) |
| Photos and documents of own trips | list, delete own uploads while the trip is open | `trips_files_list`, `trips_files_delete` (uploading is done in the app: photo or PDF) |
| Own hours and violations | read | `tacho_activity_get`, `tacho_violations_list` (with the own `driverId`) |
| Own absences | read, request, withdraw a pending request | `drivers_absences_list`, `drivers_absences_create` (becomes REQUESTED), `drivers_absences_delete` (only while REQUESTED) |
| Own working days | read | `drivers_days_list` (`month=yyyy-MM`) |
| Own pay rates and payments | read, only when the firma allows drivers to see their pay | `drivers_pay_rates_list`, `drivers_payments_list` |
| Everything else | none | - |

## 4. Typical tasks

| Task | Tools | How |
|---|---|---|
| "What is my next trip?" | `trips_list` | `driverId` = own id, status PLANNED or RUNNING, the earliest planned start; stops in order with addresses and planned times |
| "I have unloaded in Lyon" | `trips_get`, `trips_stops_patch` | the stop of the trip: status DONE, `actualAt` now |
| "How many hours did I drive this week?" | `tacho_activity_get` | own `driverId`, Monday to today; figures per day |
| "Do I have violations?" | `tacho_violations_list` | own `driverId`; explain the rule in plain words |
| "Request vacation from 21 to 23 December" | `drivers_absences_create` | kind VACATION, `fromDate`, `toDate`; the office approves or rejects it; overlapping requests are refused |
| "Was my vacation approved?" | `drivers_absences_list` | status REQUESTED, APPROVED or REJECTED with the office's note |
| "When does my driving licence expire?" | `drivers_get`, `drivers_documents_list` | `nextDocument` and the documents with `validTo` |

## 5. Limits and rules

- Only after the driver's confirmation: marking a stop done, requesting or withdrawing an absence, deleting a photo.
- A finished or cancelled trip takes no new files from the driver; decided absences cannot be withdrawn.
- Do not try other ids to "find" colleagues or vehicles: the answer is always 403.
- While driving, keep answers short; never encourage using the phone at the wheel.

## 6. Change log

| Version | Date | Changes |
|---|---|---|
| 0.1 | 2026-10-27 | Draft (Russian) with placeholder tools |
| 0.2 | 2026-11-03 | English; real tools and the own-data rules of the driver cabinet |
