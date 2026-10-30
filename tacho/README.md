# rutera-tacho

Parser of tachograph DDD files (also `.esm`, `.tgd`, `.c1b`, `.v1b`) written for RUTERA from the data
dictionary of Regulation (EU) 2016/799: Annex IB (first generation) and Annex IC (second generation, version 2
as amended by Regulation (EU) 2021/1228), Appendix 1 (data dictionary) and Appendix 7 (download protocols).
Plain Java, no dependencies, no Spring. `core` uses it for the import.

```java
TachoFile file = TachoParser.parse(bytes);            // recognises card or VU, throws TachoFormatException
TachoFileSummary s = file.summary();                  // card number / VIN + plate, period, downloadedAt
if (file instanceof DriverCardFile card) {
    card.identification();                            // card number, issuing state, validity
    card.holder();                                    // surname, first names, birth date
    card.activityDays();                              // both applications merged, day by day
    card.activityDays().getFirst().periods();         // [start, end) intervals in UTC per slot
    card.vehiclesUsed(); card.places(); card.events(); card.faults();
} else if (file instanceof VuFile vu) {
    vu.overview(); vu.activityDays(); vu.eventsAndFaults(); vu.technicalData(); vu.detailedSpeed();
}
```

## What is read

| File | Generation | Content |
|---|---|---|
| Driver card | GEN1, GEN2, GEN2V2 | ICC, application identification, card identification and holder, driving licence, last card download, events and faults, driver activity (cyclic buffer, per minute), vehicles used (with VIN on GEN2), places (with GNSS on GEN2), current usage, last control, specific conditions; GEN2: VU used, GNSS accumulated driving positions; GEN2V2: border crossings, load/unload operations, load type entries |
| Vehicle unit | GEN1 (TREP 01h..05h), GEN2 (21h..25h), GEN2V2 (31h..35h) | overview (VIN, plate, VU time, downloadable period, slots, previous download, company locks, controls), activities per day (card insertions, activity changes of both slots, places, GNSS positions, specific conditions, border crossings, loads), events and faults, over speeding, time adjustments, detailed speed, technical data (VU identification, sensors, calibrations) |

A second generation card holds two applications: `tachograph()` (GEN1 part, written by every VU) and
`tachographG2()` (written by second generation VUs only). The merged accessors of `DriverCardFile` combine both
and prefer the second generation record of the same moment.

All times are UTC (`Instant`); days are UTC calendar days. Text with a code page byte (names, addresses, plates)
is decoded with ISO 8859-1..16 or KOI8-R/U; blank values are `null`. Countries come as `Nation` with the
tachograph code and ISO 3166-1 alpha-2. Event and fault types have stable keys (`EventFaultTypes`) for storage and
translation in the client.

## Errors

`TachoFormatException.getReason()`: `NOT_A_TACHOGRAPH_FILE`, `TRUNCATED`, `UNSUPPORTED` (company, workshop and
control cards), `CORRUPT`. Damage inside one card EF or after the first VU transfer does not stop the parsing: the
rest is returned and `warnings()` says what was skipped.

## Not yet

- Signature and certificate verification (the signatures are skipped).
- Rules of Regulation (EC) 561/2006 (breaks, daily and weekly rests): a separate step on top of
  `ActivityPeriod`.
- Company, workshop and control card downloads.
- The second generation VU structures are checked with synthetic files only; the GEN1 VU and the GEN1/GEN2 card
  structures are also checked with real downloads.

## Tests with real files

Real downloads hold personal data and are never committed (`.gitignore` blocks `*.ddd` and friends). Put them in
`../../tacho-samples/` next to the clone (outside the working tree) or point the test elsewhere:

```
mvn -pl tacho test -Drutera.tacho.samples=C:/path/to/ddd-files
```

`RealSamplesTest` parses every file there and checks that there are no warnings, that activity days are sorted and
that the activity periods of each slot fit into the day. It prints only kinds, generations, dates and counts.
Without the directory the test is skipped.
