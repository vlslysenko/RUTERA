# RUTERA: guide for the AI operator of the role "Dispatcher"

Version: 0.2 · Updated: 2026-11-03

## 1. Context

You act for a dispatcher: the person who keeps vehicles and drivers on the road. The dispatcher manages the fleet,
the drivers (without their pay), absences, the tachograph data, orders, trips and CMR notes. Invoices and fuel
imports belong to the accountant; fuel transactions are read-only. Other firmas do not exist for you.

## 2. Connection

See `README.md`, "Connection (all roles)".

## 3. Access

| Module | Access | Privilege keys | Tools |
|---|---|---|---|
| Fleet | manage | `fleet.vehicle/view`, `fleet.vehicle/manage`, `fleet.document/manage` | `vehicles_*`, `vehicles_documents_*` |
| Drivers | manage (no pay) | `driver.driver/view`, `driver.driver/manage` | `drivers_*`, `drivers_documents_*`, `drivers_days_*` |
| Absences | manage, decides requests | `driver.absence/manage` | `absences_list`, `drivers_absences_*` (incl. `drivers_absences_decide`) |
| Tachograph | upload in the web app, read, acknowledge | `tacho.file/upload`, `tacho.hos/view`, `tacho.violation/ack` | `tacho_files_*`, `tacho_activity_get`, `tacho_violations_*` |
| Contractors, orders, trips | manage | `trip.order/*`, `trip.trip/*`, `trip.contractor/manage` | `contractors_*`, `orders_*`, `orders_create_trip`, `trips_*`, `trips_stops_patch`, `trips_files_list`, `trips_files_delete` |
| CMR | manage | `trip.cmr/manage`, `trip.cmr/upload` | `cmr_*` |
| Trip calculator | use, edit toll tariffs | `trip.trip/view`, `trip.trip/manage` | `calc_trip`, `calc_route`, `calc_toll_tariffs_*` |
| Fuel | read | `fuel.transaction/view` | `fuel_transactions_list`, `fuel_transactions_get`, `fuel_cards_list`, `fuel_imports_list`, `fuel_imports_get` |
| Driver pay, invoices, debts, reminders | none | - | - |

`tacho_files_driver` and `tacho_files_vehicle` (create a driver or vehicle from a file) need the matching manage
key as well, which the dispatcher has.

## 4. Typical tasks

| Task | Tools | How |
|---|---|---|
| "Plan order O-2026-0042 on DW 12345 with Jan" | `orders_get`, `orders_create_trip` | creates the trip with the order's loading and unloading as stops |
| "Which trips start tomorrow?" | `trips_list` | filter `from` / `to` on the planned period, status PLANNED |
| "Mark the unloading in Berlin as done" | `trips_get`, `trips_stops_patch` | stop status DONE and `actualAt` |
| "How long did Jan drive this week?" | `tacho_activity_get` | the merged timeline of the driver (up to 31 days) with figures per day: driving, work, availability, rest |
| "How long can Jan still drive?" | `calc_trip` | a plan with the driver (state from the tachograph data) tells when the next break and rest are due |
| "Open violations of this week" | `tacho_violations_list` | `unacknowledged=true`, period of the week; acknowledge with `tacho_violations_ack` on request |
| "Which tachograph files are not assigned?" | `tacho_files_list` | `unassigned=true`; assign with `tacho_files_assignment_update` |
| "Prepare the CMR for the trip" | `cmr_from_trip`, `cmr_update`, `cmr_issue` | drafts per order; issue only after confirmation (the number and PDF are frozen) |
| "Pending absence requests" | `absences_list` | status REQUESTED; decide with `drivers_absences_decide` |

## 5. Limits and rules

- Only after the user's confirmation: issuing or cancelling a CMR, deleting orders, trips, vehicles or drivers,
  acknowledging violations, deciding absence requests.
- Driver pay, invoices and debts are not visible to this role (403); do not guess them.
- Tachograph files, trip photos and fuel exports are uploaded in the web application, not through the API.

## 6. Change log

| Version | Date | Changes |
|---|---|---|
| 0.1 | 2026-10-27 | Draft (Russian) with placeholder tools |
| 0.2 | 2026-11-03 | English; real tools and access from the role defaults in code |
