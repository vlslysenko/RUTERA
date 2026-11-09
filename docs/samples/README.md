# Sample data

## Truck toll tariffs 2026 (`toll-tariffs-2026.json`)

204 rows for the trip calculator's toll lines, in the body format of `POST /app/calc/toll-tariffs`
(`{country, euroClass, axlesMin, weightMinKg, ratePerKm, currency, validFrom, notes}`), researched on 2026-11-09.
Countries: AT, BE, CZ, DE, DK, FR, HU, IT, NL, PL, SK. Rates are per km of motorway, net of VAT. Each row's `notes`
names the toll system and the legal act or tariff table; rows without a Euro class, axles or weight are the
fallback for a vehicle that lacks those values. Confidence by country:

- `official`: AT, BE, CZ, DE, DK, NL, PL, SK, taken from the laws and the operators' published tables;
- `estimate`: HU, IT, FR, averaged per km from section prices (Italy and France charge per motorway section, Hungary
  partly by road class).

`toll-tariffs-2026-sources.csv` is the full table behind it (561 rows): every class with its weight and axle range,
Euro and CO2 class, VAT treatment, confidence and the `source_url` of the official page. Spain cannot be reduced to a
rate per km and is left out. No country publishes its tariffs through an open API, so the table needs a yearly check
(most rates change on 1 January, Poland on 1 February 2026, some on 1 July).

### Import

Create the rows in file order: when two rows match a vehicle equally well, the calculator takes the later
`validFrom` and then the newest row. With an API token whose user has `trip.trip/manage`:

```bash
jq -c '.[]' docs/samples/toll-tariffs-2026.json | while read -r row; do
  curl -sf -X POST "$RUTERA_URL/api/v1/calc/toll-tariffs" -H "Authorization: Bearer $RUTERA_TOKEN" \
    -H 'Content-Type: application/json' -d "$row" > /dev/null || echo "failed: $row"
done
```

The rows are added to the firma of the token; existing tariffs stay, so import into a firma without tariffs or
delete the old ones first.

### Limits

- Tolls are charged by the axles and the weight of the whole combination (a tractor with a semi-trailer: 5 axles,
  40 t). For the right rate set the tractor's axles to 5 and its gross weight to 40000 kg.
- RUTERA has no CO2 class yet: the rows assume CO2 class 1 (the usual class of a diesel truck), where countries
  differentiate by CO2 (DE, AT, CZ, SK, DK, NL, BE).
- The calculator applies the rate to all km in a country, not only to the toll roads, so the toll line comes out
  somewhat high for routes off the motorways. Concession motorways in Poland (A1 Gdańsk-Toruń, A2 Świecko-Konin, A4
  Katowice-Kraków) are charged separately per section and are not in the Polish rate.
