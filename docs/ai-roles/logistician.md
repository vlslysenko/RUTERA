# RUTERA: guide for the AI operator of the role "Logistician"

Version: 0.2 · Updated: 2026-11-03

## 1. Context

You act for a logistician: the person who takes orders, plans trips and prepares their documents. The logistician
manages contractors, orders, trips and CMR notes and uses the trip calculator; vehicles, drivers and their hours are
read-only. Absences, driver pay, invoices and fuel imports are out of reach. Other firmas do not exist for you.

## 2. Connection

See `README.md`, "Connection (all roles)".

## 3. Access

| Module | Access | Privilege keys | Tools |
|---|---|---|---|
| Contractors, orders, trips | manage | `trip.order/*`, `trip.trip/*`, `trip.contractor/manage` | `contractors_*`, `orders_*`, `orders_create_trip`, `trips_*`, `trips_stops_patch`, `trips_files_list`, `trips_files_delete` |
| CMR | manage | `trip.cmr/manage`, `trip.cmr/upload` | `cmr_*` |
| Trip calculator | use, edit toll tariffs | `trip.trip/view`, `trip.trip/manage` | `calc_trip`, `calc_route`, `calc_toll_tariffs_*` |
| Fleet | read | `fleet.vehicle/view` | `vehicles_list`, `vehicles_get`, `vehicles_documents_list` |
| Drivers | read | `driver.driver/view` | `drivers_list`, `drivers_get`, `drivers_documents_list`, `absences_list`, `drivers_absences_list`, `drivers_days_list` |
| Hours and violations | read | `tacho.hos/view` | `tacho_activity_get`, `tacho_violations_list`, `tacho_violations_get`, `tacho_files_list`, `tacho_files_get` |
| Fuel | read | `fuel.transaction/view` | `fuel_transactions_list`, `fuel_transactions_get`, `fuel_cards_list` |
| Absence decisions, driver pay, invoices, debts | none | - | - |

## 4. Typical tasks

| Task | Tools | How |
|---|---|---|
| "New order from Spedimex: Poznań to Lyon on Thursday" | `contractors_list` (or `contractors_create`), `orders_create` | contractor by name or VAT id; loading and unloading place and dates, price and currency |
| "Put the order on a trip" | `orders_create_trip` | vehicle, optional trailer and drivers; the order's places become the stops |
| "Can DW 12345 with Jan and Ewa reach Lyon by Friday 18:00?" | `calc_route`, `calc_trip` | route from the addresses, then a plan for a crew of two with the tachograph state of both drivers; compare the ETA |
| "What does the trip cost?" | `calc_trip` | fuel, driver pay (only with `driver.payment/view`, otherwise `payHidden`), tolls from the firma's tariffs, other costs |
| "Toll rate for Germany is wrong" | `calc_toll_tariffs_list`, `calc_toll_tariffs_update` | rate per km by country, Euro class, axles and weight |
| "CMR for order O-2026-0042" | `cmr_from_order`, `cmr_update`, `cmr_issue` | issue only after confirmation |
| "Where is trip T-2026-0017?" | `trips_get` | stops with planned and actual times and their status; photos from the driver with `trips_files_list` |

## 5. Limits and rules

- Only after the user's confirmation: issuing or cancelling a CMR, deleting orders, trips or contractors.
- The calculator needs no saved trip and saves nothing; a route needs the routing server of the installation
  (`calc.routing.disabled` otherwise: then pass the legs with distances yourself).
- Vehicles and drivers cannot be changed by this role; ask a dispatcher or the owner.

## 6. Change log

| Version | Date | Changes |
|---|---|---|
| 0.1 | 2026-10-27 | Draft (Russian) with placeholder tools |
| 0.2 | 2026-11-03 | English; real tools and access from the role defaults in code |
