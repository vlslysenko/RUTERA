import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Alert, Autocomplete, Button, Checkbox, Collapse, Group, NumberInput, Paper, SegmentedControl, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconCalculator, IconChevronDown, IconChevronUp, IconPlus, IconTrash, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { calcApi, type CalcLeg, type CalcPlan, type CountryKm, type CalcRequest, type CalcRoute, type DriverStateSource, type PlaceSuggestion, type RoutePoint } from '@/api/calc';
import { TRIPS_KEY, tripsApi, type Trip, type TripStop } from '@/api/trips';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { LabelHint } from '@/components/LabelHint';
import { DateField } from '@/forms/DateField';
import { decimalSeparator } from '@/forms/number';
import { countryName, countryOptions } from '@/utils/countries';
import { fromZonedInput, toZonedInput } from '@/utils/zone';
import { CalcResult } from './CalcResult';
import { RouteMap } from './RouteMap';
import { idOrNull, useCrewOptions } from './crew';
import { CURRENCIES } from './tripFormat';
import classes from './Calc.module.css';

interface LegRow {
  key: number;
  name: string;
  distanceKm: number | string;
  durationH: number | string;
  country: string | null;
  /** Km (and toll km) per country of a leg from the route; typing the distance by hand drops them. */
  countries: CountryKm[] | null;
  serviceH: number | string;
}

interface OtherRow {
  key: number;
  label: string;
  amount: number | string;
  currency: string | null;
}

interface ManualRow {
  sinceBreakH: number | string;
  inShiftH: number | string;
  shiftStart: string;
  weekH: number | string;
  fortnightH: number | string;
  extensionsLeft: number | string;
  reducedLeft: number | string;
  lastWeeklyRestEnd: string;
}

/** A place of the trip: an address to geocode, or a point picked on the map (then the text shows its coordinates). */
interface Place {
  key: number;
  text: string;
  lat: number | null;
  lng: number | null;
}

let seq = 0;
// Learnt from the first route call of the session: the server has no routing engine, so the distance is typed in.
let routingKnownOff = false;
// Learnt the same way: the server has no place search (503) or it is down (502), so the fields stay plain text.
let placesKnownOff = false;
const MAX_VIAS = 23;
const place = (text = ''): Place => ({ key: ++seq, text, lat: null, lng: null });
const filled = (p: Place) => p.lat != null || p.text.trim() !== '';
const coords = (lat: number, lng: number) => `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
// LegInput.name is at most 200 characters on the backend; a place gets at most 90 of them.
const LEG_NAME_MAX = 200;
const PLACE_NAME_MAX = 90;
const clip = (s: string, max: number) => (s.length > max ? `${s.slice(0, max - 1).trimEnd()}…` : s);
/** A place as the user knows it: what was typed, or the routed label of a point picked on the map. */
const placeName = (p: Place | undefined, routed: string | undefined) => {
  const typed = p?.text.trim() ?? '';
  const picked = p?.lat != null && p.lng != null && typed === coords(p.lat, p.lng);
  return clip(!typed || picked ? (routed ?? typed) : typed, PLACE_NAME_MAX);
};
const num = (v: number | string | null | undefined) => (v === '' || v == null ? null : Number(v));
const minutes = (h: number | string) => {
  const n = num(h);
  return n == null ? null : Math.round(n * 60);
};

/** Next full hour as the default start. */
function nextHour(tz?: string): string {
  const d = new Date(Math.ceil(Date.now() / 3_600_000) * 3_600_000);
  return toZonedInput(d.toISOString(), tz);
}

/**
 * Where the vehicle will be: the last stop of its running trip, or else of its latest trip (planned or done), with
 * the time it gets there.
 */
function lastStop(trips: Trip[]): { trip: Trip; stop: TripStop; at: string | null } | null {
  const usable = trips.filter((tr) => tr.status !== 'CANCELLED' && tr.stops.length > 0);
  const lastOf = (tr: Trip) => [...tr.stops].sort((a, b) => a.seq - b.seq)[tr.stops.length - 1];
  const end = (tr: Trip) => tr.actualEnd ?? tr.plannedEnd ?? lastOf(tr).actualAt ?? lastOf(tr).plannedAt ?? tr.plannedStart ?? tr.createdAt;
  const running = usable.filter((tr) => tr.status === 'RUNNING');
  const best = [...(running.length ? running : usable)].sort((a, b) => (end(b) ?? '').localeCompare(end(a) ?? ''))[0];
  if (!best) return null;
  return { trip: best, stop: lastOf(best), at: end(best) };
}

/** Km and toll km per country over all legs, in the order the trip enters them; empty when no leg names a country. */
function countriesOf(legs: CalcLeg[]): CountryKm[] {
  const by = new Map<string, CountryKm>();
  for (const l of legs) {
    for (const c of l.countries ?? []) {
      const sum = by.get(c.country) ?? { country: c.country, km: 0, tollKm: null };
      sum.km += c.km;
      if (c.tollKm != null) sum.tollKm = (sum.tollKm ?? 0) + c.tollKm;
      by.set(c.country, sum);
    }
  }
  return [...by.values()];
}

const legRow = (l: CalcLeg): LegRow => ({
  key: ++seq,
  name: l.name ?? '',
  distanceKm: l.distanceKm,
  durationH: l.durationMin == null ? '' : Math.round((l.durationMin / 60) * 100) / 100,
  country: l.countries?.[0]?.country ?? null,
  // One country without toll km is the country select; otherwise the route's split stays.
  countries: l.countries && (l.countries.length > 1 || l.countries.some((c) => c.tollKm != null)) ? l.countries : null,
  serviceH: l.serviceMin == null ? '' : Math.round((l.serviceMin / 60) * 100) / 100,
});

/**
 * Where from, a stop on the way or where to: an address with suggestions while typing (GET /app/calc/places), or the
 * coordinates of a point picked on the map. A picked suggestion brings its coordinates, so the route needs no second
 * lookup. Without a place search on the server the field is plain text and the route looks the address up.
 */
function PlaceInput({
  value,
  label,
  hint,
  description,
  error,
  onChange,
  onPick,
}: {
  value: Place;
  label: string;
  hint?: string;
  description?: string | null;
  error?: string;
  onChange: (text: string) => void;
  onPick: (place: PlaceSuggestion) => void;
}) {
  const { t, i18n } = useTranslation();
  const [q] = useDebouncedValue(value.text.trim(), 400);
  const [off, setOff] = useState(placesKnownOff);
  // Autocomplete reports the picked label as a change right after the pick: that change must not drop the coordinates.
  const picked = useRef<string | null>(null);
  const suggestions = useQuery({
    queryKey: ['calc', 'places', i18n.resolvedLanguage, q],
    queryFn: async () => {
      try {
        return await calcApi.places(q);
      } catch (e) {
        if (e instanceof ApiError && (e.status === 502 || e.status === 503)) {
          placesKnownOff = true;
          setOff(true);
        }
        // Throttled (429) or offline: no suggestions this time.
        return [];
      }
    },
    enabled: !off && value.lat == null && q.length >= 3,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const items = off ? [] : (suggestions.data ?? []);
  return (
    <Autocomplete
      label={hint ? <LabelHint label={label} hint={hint} /> : label}
      placeholder={t('calc.place_hint')}
      description={description ?? undefined}
      value={value.text}
      onChange={(text) => {
        if (picked.current === text) {
          picked.current = null;
          return;
        }
        picked.current = null;
        onChange(text);
      }}
      data={[...new Set(items.map((p) => p.label))]}
      // The server already matched the text; Mantine must not filter its answer again.
      filter={({ options }) => options}
      onOptionSubmit={(chosen) => {
        const place = items.find((p) => p.label === chosen);
        if (!place) return;
        picked.current = chosen;
        onPick(place);
      }}
      // Coordinates of a point picked on the map read better in the monospace font.
      classNames={value.lat != null && value.lng != null && value.text === coords(value.lat, value.lng) ? { input: classes.mono } : undefined}
      error={error}
    />
  );
}

/**
 * Trip calculator: from where to where, with which vehicle (and driver), when it arrives under 561/2006 and what it
 * costs (fuel, driver pay, tolls, other). The start place comes from the end of the vehicle's current or last trip.
 * The truck route (POST /app/calc/route) gives the km per country; a server without routing asks for the distance.
 * Everything else (second driver, the drivers' state, rest options, prices, legs typed in by hand) is under
 * "More options".
 */
export function CalculatorPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const [start, setStart] = useState(nextHour(tz));
  const [startTouched, setStartTouched] = useState(false);
  const [vehicleId, setVehicleId] = useState<string | null>(null);
  const [driver1, setDriver1] = useState<string | null>(null);
  const [driver2, setDriver2] = useState<string | null>(null);
  const [from, setFrom] = useState<Place>(place());
  const [to, setTo] = useState<Place>(place());
  const [vias, setVias] = useState<Place[]>([]);
  // The start place may still be replaced from the chosen vehicle (not typed in, not picked on the map).
  const [fromAuto, setFromAuto] = useState(true);
  const [fromNote, setFromNote] = useState<string | null>(null);
  const [routingOff, setRoutingOff] = useState(routingKnownOff);
  const [distanceKm, setDistanceKm] = useState<number | string>('');
  const [route, setRoute] = useState<CalcRoute | null>(null);
  const [placeErrors, setPlaceErrors] = useState<Record<number, string>>({});
  const [more, setMore] = useState(false);
  const [crewSize, setCrewSize] = useState<'1' | '2'>('1');
  const [driverState, setDriverState] = useState<DriverStateSource>('FRESH');
  const [manual, setManual] = useState<ManualRow>({ sinceBreakH: 0, inShiftH: 0, shiftStart: '', weekH: 0, fortnightH: 0, extensionsLeft: 2, reducedLeft: 3, lastWeeklyRestEnd: '' });
  const [loadH, setLoadH] = useState<number | string>(1);
  const [unloadH, setUnloadH] = useState<number | string>(1);
  const [avoidTolls, setAvoidTolls] = useState(false);
  const [avoidFerries, setAvoidFerries] = useState(false);
  const [manualLegs, setManualLegs] = useState(false);
  const [legs, setLegs] = useState<LegRow[]>([]);
  const [lastLegs, setLastLegs] = useState<CalcLeg[]>([]);
  const [avgSpeed, setAvgSpeed] = useState<number | string>(70);
  const [extended, setExtended] = useState(true);
  const [reducedRests, setReducedRests] = useState(true);
  const [reducedWeekly, setReducedWeekly] = useState(false);
  const [consumption, setConsumption] = useState<number | string>('');
  const [fuelPrice, setFuelPrice] = useState<number | string>('');
  const [fuelCurrency, setFuelCurrency] = useState<string | null>('EUR');
  const [adBluePct, setAdBluePct] = useState<number | string>('');
  const [adBluePrice, setAdBluePrice] = useState<number | string>('');
  const [adBlueCurrency, setAdBlueCurrency] = useState<string | null>('EUR');
  const [others, setOthers] = useState<OtherRow[]>([]);
  const [plan, setPlan] = useState<CalcPlan | null>(null);
  const [planLegNames, setPlanLegNames] = useState<string[]>([]);
  const [planCountries, setPlanCountries] = useState<CountryKm[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  // The leg (1-based) a failure of a route-built leg belongs to.
  const [failureLeg, setFailureLeg] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const crew = useCrewOptions([vehicleId, driver1, driver2]);
  const dec = decimalSeparator(lang);
  const err = (key: string) => (errors[key] ? t(errors[key]) : undefined);
  const driverCount = [driver1, driver2].filter(Boolean).length;
  const effectiveCrew = driverCount === 2 ? '2' : crewSize;

  const qc = useQueryClient();
  // Bumped by every vehicle change and by typing or picking the start place, so a late answer cannot overwrite them.
  const fillToken = useRef(0);

  /** Where the vehicle starts: the last stop of its running or latest trip, and the time it gets free. */
  const fillFrom = async (id: string, token: number) => {
    try {
      const page = await qc.fetchQuery({
        queryKey: [...TRIPS_KEY, 'calc-start', id],
        queryFn: () => tripsApi.list({ vehicleId: Number(id), size: 50 }),
        staleTime: 30_000,
      });
      if (fillToken.current !== token) return;
      const last = lastStop(page.items);
      if (!last) return;
      setFrom(place([last.stop.address, countryName(last.stop.country, lang)].filter(Boolean).join(', ')));
      setFromNote(t('calc.from_trip_end', { number: last.trip.number }));
      setRoute(null);
      if (!startTouched && last.at && last.at > new Date().toISOString()) setStart(toZonedInput(last.at, tz));
    } catch {
      // No trips to start from: the place is typed in.
    }
  };

  const chooseVehicle = (v: string | null) => {
    setVehicleId(v);
    setRoute(null);
    const token = ++fillToken.current;
    if (fromAuto) {
      setFrom(place());
      setFromNote(null);
      if (v) void fillFrom(v, token);
    }
    // The vehicle's usual driver, when none is chosen yet; his tachograph data give the driving time left.
    const usual = crew.vehicle(v)?.defaultDriver;
    if (usual && !driver1) {
      setDriver1(String(usual.id));
      if (driverState === 'FRESH') setDriverState('TACHO');
    }
  };

  const chooseDriver1 = (v: string | null) => {
    setDriver1(v);
    // Tachograph data only exist for a chosen driver.
    if (v && driverState === 'FRESH') setDriverState('TACHO');
    if (!v) {
      setDriver2(null);
      if (driverState === 'TACHO') setDriverState('FRESH');
    }
  };

  const editFrom = (text: string) => {
    fillToken.current++;
    setFrom((p) => ({ ...p, text, lat: null, lng: null }));
    setFromAuto(false);
    setFromNote(null);
    setRoute(null);
  };
  const editTo = (text: string) => {
    setTo((p) => ({ ...p, text, lat: null, lng: null }));
    setRoute(null);
  };
  const editVia = (key: number, text: string) => {
    setVias((vs) => vs.map((v) => (v.key === key ? { ...v, text, lat: null, lng: null } : v)));
    setRoute(null);
  };

  const pickFrom = (p: PlaceSuggestion) => {
    fillToken.current++;
    setFrom((f) => ({ ...f, text: p.label, lat: p.lat, lng: p.lng }));
    setFromAuto(false);
    setFromNote(null);
    setRoute(null);
  };
  const pickTo = (p: PlaceSuggestion) => {
    setTo((x) => ({ ...x, text: p.label, lat: p.lat, lng: p.lng }));
    setRoute(null);
  };
  const pickVia = (key: number, p: PlaceSuggestion) => {
    setVias((vs) => vs.map((v) => (v.key === key ? { ...v, text: p.label, lat: p.lat, lng: p.lng } : v)));
    setRoute(null);
  };

  /** A click on the map fills where from, then where to, and later replaces where to. */
  const pickOnMap = (lat: number, lng: number) => {
    const picked = { text: coords(lat, lng), lat, lng };
    setRoute(null);
    if (!filled(from)) {
      fillToken.current++;
      setFrom((p) => ({ ...p, ...picked }));
      setFromAuto(false);
      setFromNote(null);
    } else setTo((p) => ({ ...p, ...picked }));
  };

  const toggleManualLegs = (on: boolean) => {
    setManualLegs(on);
    // The legs of the last calculation are a starting point for editing them by hand.
    if (on && legs.length === 0) {
      setLegs(
        lastLegs.length
          ? lastLegs.map(legRow)
          : [
              { key: ++seq, name: '', distanceKm: 0, durationH: '', country: 'PL', countries: null, serviceH: 1 },
              { key: ++seq, name: '', distanceKm: '', durationH: '', country: 'PL', countries: null, serviceH: 1 },
            ],
      );
    }
  };
  const setLeg = (key: number, patch: Partial<LegRow>) => setLegs((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  /** The legs from the route: loading at the start (0 km), then one leg per stretch with its km per country. */
  /**
   * The legs from the route: loading at the start (0 km), then one leg per stretch with its km per country. Names use
   * what was typed (the geocoder's full labels run past the 200 characters of a leg name).
   */
  const legsOfRoute = (r: CalcRoute, places: Place[]): CalcLeg[] => {
    const names = r.points.map((pt, i) => placeName(places[i], pt.label));
    return [
      { name: names[0] ?? null, distanceKm: 0, durationMin: null, countries: null, serviceMin: minutes(loadH) },
      ...r.legs.map((l, i) => ({
        name: clip(`${names[i] ?? clip(l.from.label, PLACE_NAME_MAX)} – ${names[i + 1] ?? clip(l.to.label, PLACE_NAME_MAX)}`, LEG_NAME_MAX),
        distanceKm: l.distanceKm,
        durationMin: l.durationMin,
        countries: l.countries,
        serviceMin: minutes(unloadH),
      })),
    ];
  };

  /** Without a routing engine: one leg of the typed-in distance (km in the firma's country). */
  const legsOfDistance = (): CalcLeg[] => [
    { name: placeName(from, undefined), distanceKm: 0, durationMin: null, countries: null, serviceMin: minutes(loadH) },
    { name: clip(`${placeName(from, undefined)} – ${placeName(to, undefined)}`, LEG_NAME_MAX), distanceKm: num(distanceKm) ?? 0, durationMin: null, countries: null, serviceMin: minutes(unloadH) },
  ];

  const legsOfRows = (): CalcLeg[] =>
    legs.map((l) => ({
      name: l.name.trim() || null,
      distanceKm: num(l.distanceKm) ?? 0,
      durationMin: minutes(l.durationH),
      countries: l.countries ?? (l.country && (num(l.distanceKm) ?? 0) > 0 ? [{ country: l.country, km: num(l.distanceKm) ?? 0 }] : null),
      serviceMin: minutes(l.serviceH),
    }));

  /** The route through the places; null when it failed (the reason is shown) or the server cannot route. */
  const routeOf = async (places: Place[]): Promise<CalcRoute | null> => {
    const points: RoutePoint[] = places.map((p) => (p.lat != null && p.lng != null ? { lat: p.lat, lng: p.lng } : { address: p.text.trim() }));
    try {
      const r = await calcApi.route({ points, vehicleId: idOrNull(vehicleId), avoidTolls, avoidFerries });
      setRoute(r);
      return r;
    } catch (e) {
      if (e instanceof ApiError && e.code === 'calc.routing.disabled') {
        routingKnownOff = true;
        setRoutingOff(true);
        setFailure('calc.routing_off');
      } else if (e instanceof ApiError && e.details) {
        // points[i] in driving order: where from, the stops on the way, where to.
        const byPlace: Record<number, string> = {};
        for (const [field, code] of Object.entries(e.details)) {
          const m = /^points\[(\d+)\]$/.exec(field);
          if (m && places[Number(m[1])]) byPlace[places[Number(m[1])].key] = code;
          else setFailure(code);
        }
        setPlaceErrors(byPlace);
      } else setFailure(errorCode(e));
      return null;
    }
  };

  const calculate = async () => {
    const local: Record<string, string> = {};
    const places = [from, ...vias.filter(filled), to];
    if (!start) local.start = 'validation.required';
    if (manualLegs) {
      legs.forEach((l, i) => {
        if (num(l.distanceKm) == null) local[`legs[${i}].distanceKm`] = 'validation.required';
      });
    } else {
      if (!filled(from)) local.from = 'validation.required';
      if (!filled(to)) local.to = 'validation.required';
      if (routingOff && num(distanceKm) == null) local.distance = 'validation.required';
    }
    setErrors(local);
    setPlaceErrors({});
    setFailure(null);
    setFailureLeg(null);
    if (Object.keys(local).length) return;

    setBusy(true);
    try {
      let used: CalcLeg[];
      if (manualLegs) used = legsOfRows();
      else if (routingOff) used = legsOfDistance();
      else {
        const r = route ?? (await routeOf(places));
        if (!r) return;
        used = legsOfRoute(r, places);
      }
      const body: CalcRequest = {
        start: fromZonedInput(start, tz) ?? '',
        vehicleId: idOrNull(vehicleId),
        driverIds: [driver1, driver2].filter((x): x is string => !!x).map(Number),
        crewSize: effectiveCrew === '2' ? 2 : 1,
        driverState,
        manualState:
          driverState === 'MANUAL'
            ? {
                drivingSinceBreakMin: minutes(manual.sinceBreakH) ?? 0,
                drivingInShiftMin: minutes(manual.inShiftH) ?? 0,
                shiftStart: fromZonedInput(manual.shiftStart, tz),
                weekDrivingMin: minutes(manual.weekH) ?? 0,
                fortnightDrivingMin: minutes(manual.fortnightH) ?? 0,
                extensionsLeft: num(manual.extensionsLeft) ?? 0,
                reducedRestsLeft: num(manual.reducedLeft) ?? 0,
                lastWeeklyRestEnd: fromZonedInput(manual.lastWeeklyRestEnd, tz),
              }
            : null,
        legs: used,
        options: {
          avgSpeedKmh: num(avgSpeed),
          extendedDriving: extended,
          reducedRests,
          reducedWeeklyRest: reducedWeekly,
          consumptionL100km: num(consumption),
          fuelPrice: num(fuelPrice),
          fuelCurrency: num(fuelPrice) != null ? fuelCurrency : null,
          adBluePct: num(adBluePct),
          adBluePrice: num(adBluePrice),
          adBlueCurrency: num(adBluePrice) != null ? adBlueCurrency : null,
          otherCosts: others
            .filter((o) => o.label.trim() && num(o.amount) != null && o.currency)
            .map((o) => ({ label: o.label.trim(), amount: num(o.amount) ?? 0, currency: o.currency ?? 'EUR' })),
        },
      };
      try {
        setPlan(await calcApi.plan(body));
        setLastLegs(used);
        setPlanLegNames(used.map((l, i) => l.name?.trim() || t('calc.leg_n', { n: i + 1 })));
        setPlanCountries(countriesOf(used));
      } catch (e) {
        if (e instanceof ApiError && e.details) {
          setErrors(e.details);
          // Legs built from the route have no fields on screen: name the first problem and its leg instead.
          const [field, code] = Object.entries(e.details)[0] ?? [];
          const leg = field ? /^legs\[(\d+)\]/.exec(field) : null;
          setFailure(manualLegs || !code ? errorCode(e) : code);
          setFailureLeg(!manualLegs && leg ? Number(leg[1]) + 1 : null);
        } else setFailure(errorCode(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const placeError = (p: Place) => (placeErrors[p.key] ? t(placeErrors[p.key]) : undefined);
  const picks = [from, ...vias, to].filter((p) => p.lat != null && p.lng != null).map((p) => ({ lat: p.lat!, lng: p.lng! }));
  return (
    <div className={classes.layout}>
      <Paper withBorder p="md">
        <Stack gap="sm">
          <Select
            label={<LabelHint label={t('trips.vehicle')} hint={t('calc.vehicle_hint')} />}
            data={crew.vehicles}
            value={vehicleId}
            onChange={chooseVehicle}
            clearable
            searchable
            error={err('vehicleId')}
          />
          <Select
            label={<LabelHint label={t('calc.driver')} hint={t('hint.calc.driver')} />}
            data={crew.drivers}
            value={driver1}
            onChange={chooseDriver1}
            clearable
            searchable
            error={err('driverIds')}
          />
          {!manualLegs && (
            <>
              <PlaceInput value={from} label={t('calc.from_place')} hint={t('hint.calc.from')} description={fromNote} error={err('from') ?? placeError(from)} onChange={editFrom} onPick={pickFrom} />
              {vias.map((v, i) => (
                <Group key={v.key} gap={6} wrap="nowrap" align="flex-end">
                  <div style={{ flex: 1 }}>
                    <PlaceInput value={v} label={t('calc.via_n', { n: i + 1 })} error={placeError(v)} onChange={(text) => editVia(v.key, text)} onPick={(p) => pickVia(v.key, p)} />
                  </div>
                  <Tooltip label={t('common.delete')}>
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      mb={4}
                      onClick={() => {
                        setVias((vs) => vs.filter((x) => x.key !== v.key));
                        setRoute(null);
                      }}
                      aria-label={t('common.delete')}
                    >
                      <IconX size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              ))}
              <PlaceInput value={to} label={t('calc.to_place')} error={err('to') ?? placeError(to)} onChange={editTo} onPick={pickTo} />
              <Button
                size="xs"
                variant="subtle"
                leftSection={<IconPlus size={14} />}
                onClick={() => setVias((vs) => [...vs, place()])}
                disabled={vias.length >= MAX_VIAS}
                w="fit-content"
              >
                {t('calc.add_via')}
              </Button>
              {routingOff && (
                <NumberInput
                  label={t('calc.distance_km')}
                  value={distanceKm}
                  onChange={setDistanceKm}
                  min={0}
                  max={20000}
                  allowNegative={false}
                  allowDecimal={false}
                  hideControls
                  error={err('distance')}
                />
              )}
            </>
          )}
          <DateField
            withTime
            label={t('calc.start')}
            value={start}
            onChange={(v) => {
              setStart(v);
              setStartTouched(true);
            }}
            error={err('start')}
          />
          <Button leftSection={<IconCalculator size={16} />} onClick={() => void calculate()} loading={busy} mt={4}>
            {t('calc.calculate')}
          </Button>
          {failure && (
            <Alert color={failure === 'calc.routing_off' ? 'gray' : 'red'} variant="light" p="xs">
              {failureLeg != null && `${t('calc.leg_n', { n: failureLeg })}: `}
              {t(failure)}
            </Alert>
          )}

          <Button
            variant="subtle"
            size="xs"
            color="gray"
            onClick={() => setMore((m) => !m)}
            rightSection={more ? <IconChevronUp size={14} /> : <IconChevronDown size={14} />}
            aria-expanded={more}
            w="fit-content"
          >
            {t('calc.more')}
          </Button>
          <Collapse expanded={more}>
            <Stack gap="sm">
              <Select label={t('calc.driver_n', { n: 2 })} data={crew.drivers} value={driver2} onChange={setDriver2} clearable searchable disabled={!driver1} />
              <Group gap="md" wrap="wrap">
                <div>
                  <Text size="sm" fw={500} mb={4}>
                    <LabelHint label={t('calc.crew')} hint={t('hint.calc.crew')} />
                  </Text>
                  <SegmentedControl
                    value={effectiveCrew}
                    onChange={(v) => setCrewSize(v as '1' | '2')}
                    data={[
                      { value: '1', label: t('calc.crew_1') },
                      { value: '2', label: t('calc.crew_2') },
                    ]}
                    disabled={driverCount === 2}
                  />
                </div>
                <div>
                  <Text size="sm" fw={500} mb={4}>
                    <LabelHint label={t('calc.driver_state')} hint={t('hint.calc.driver_state')} />
                  </Text>
                  <SegmentedControl
                    value={driverState}
                    onChange={(v) => setDriverState(v as DriverStateSource)}
                    data={[
                      { value: 'TACHO', label: t('calc.state.TACHO'), disabled: driverCount === 0 },
                      { value: 'FRESH', label: t('calc.state.FRESH') },
                      { value: 'MANUAL', label: t('calc.state.MANUAL') },
                    ]}
                  />
                </div>
              </Group>
              {driverState === 'MANUAL' && (
                <Paper withBorder p="xs">
                  <Stack gap={6}>
                    <Group grow align="flex-start">
                      <NumberInput size="xs" label={<LabelHint label={t('calc.m.since_break')} hint={t('hos.rule_hint.BREAK_45_AFTER_4H30')} />} value={manual.sinceBreakH} onChange={(v) => setManual((m) => ({ ...m, sinceBreakH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                      <NumberInput size="xs" label={t('calc.m.in_shift')} value={manual.inShiftH} onChange={(v) => setManual((m) => ({ ...m, inShiftH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                    </Group>
                    <Group grow align="flex-start">
                      <NumberInput size="xs" label={<LabelHint label={t('calc.m.week')} hint={t('hos.rule_hint.WEEKLY_DRIVING_56H')} />} value={manual.weekH} onChange={(v) => setManual((m) => ({ ...m, weekH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                      <NumberInput size="xs" label={<LabelHint label={t('calc.m.fortnight')} hint={t('hos.rule_hint.TWO_WEEK_DRIVING_90H')} />} value={manual.fortnightH} onChange={(v) => setManual((m) => ({ ...m, fortnightH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                    </Group>
                    <Group grow align="flex-start">
                      <NumberInput size="xs" label={<LabelHint label={t('calc.extensions_left')} hint={t('hos.rule_hint.DAILY_DRIVING_9H')} />} value={manual.extensionsLeft} onChange={(v) => setManual((m) => ({ ...m, extensionsLeft: v }))} min={0} max={2} allowDecimal={false} />
                      <NumberInput size="xs" label={<LabelHint label={t('calc.reduced_left')} hint={t('hos.rule_hint.DAILY_REST_9H')} />} value={manual.reducedLeft} onChange={(v) => setManual((m) => ({ ...m, reducedLeft: v }))} min={0} max={3} allowDecimal={false} />
                    </Group>
                    <Group grow align="flex-start">
                      <DateField withTime size="xs" label={t('calc.m.shift_start')} value={manual.shiftStart} onChange={(v) => setManual((m) => ({ ...m, shiftStart: v }))} />
                      <DateField withTime size="xs" label={<LabelHint label={t('calc.m.last_weekly_rest')} hint={t('hos.rule_hint.WEEKLY_REST_45H')} />} value={manual.lastWeeklyRestEnd} onChange={(v) => setManual((m) => ({ ...m, lastWeeklyRestEnd: v }))} />
                    </Group>
                    {err('manualState') && (
                      <Text size="xs" c="red">
                        {err('manualState')}
                      </Text>
                    )}
                  </Stack>
                </Paper>
              )}

              {!manualLegs && (
                <Group grow align="flex-start">
                  <NumberInput size="xs" label={t('calc.load_h')} value={loadH} onChange={setLoadH} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls />
                  <NumberInput size="xs" label={<LabelHint label={t('calc.unload_h')} hint={t('hint.field.service_h')} />} value={unloadH} onChange={setUnloadH} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls />
                </Group>
              )}
              {!manualLegs && !routingOff && (
                <Group gap="md">
                  <Checkbox size="xs" label={t('calc.route.avoid_tolls')} checked={avoidTolls} onChange={(e) => { setAvoidTolls(e.currentTarget.checked); setRoute(null); }} />
                  <Checkbox size="xs" label={t('calc.route.avoid_ferries')} checked={avoidFerries} onChange={(e) => { setAvoidFerries(e.currentTarget.checked); setRoute(null); }} />
                </Group>
              )}
              <Group grow align="flex-start">
                <NumberInput size="xs" label={<LabelHint label={t('calc.avg_speed')} hint={t('hint.field.avg_speed')} />} value={avgSpeed} onChange={setAvgSpeed} min={20} max={120} allowDecimal={false} error={err('options.avgSpeedKmh')} />
                <NumberInput size="xs" label={t('calc.consumption')} placeholder={t('calc.from_vehicle')} value={consumption} onChange={setConsumption} decimalScale={1} decimalSeparator={dec} allowNegative={false} hideControls />
              </Group>
              <Group grow align="flex-start">
                <NumberInput size="xs" label={t('calc.fuel_price')} placeholder={t('calc.from_fuel')} value={fuelPrice} onChange={setFuelPrice} decimalScale={3} decimalSeparator={dec} allowNegative={false} hideControls />
                <Select size="xs" label={t('orders.currency')} data={CURRENCIES} value={fuelCurrency} onChange={setFuelCurrency} searchable error={err('options.fuelCurrency')} />
              </Group>
              <Group grow align="flex-start">
                <NumberInput size="xs" label={<LabelHint label={t('calc.adblue_pct')} hint={t('hint.calc.adblue_pct')} />} placeholder={t('calc.adblue_pct_auto')} value={adBluePct} onChange={setAdBluePct} decimalScale={2} decimalSeparator={dec} min={0} max={20} allowNegative={false} hideControls error={err('options.adBluePct')} />
                <NumberInput size="xs" label={t('calc.adblue_price')} placeholder={t('calc.from_fuel')} value={adBluePrice} onChange={setAdBluePrice} decimalScale={3} decimalSeparator={dec} allowNegative={false} hideControls error={err('options.adBluePrice')} />
                <Select size="xs" label={t('orders.currency')} data={CURRENCIES} value={adBlueCurrency} onChange={setAdBlueCurrency} searchable error={err('options.adBlueCurrency')} />
              </Group>
              <Stack gap={4}>
                <Checkbox size="xs" label={<LabelHint label={t('calc.opt_extended')} hint={t('hos.rule_hint.DAILY_DRIVING_9H')} />} checked={extended} onChange={(e) => setExtended(e.currentTarget.checked)} />
                <Checkbox size="xs" label={<LabelHint label={t('calc.opt_reduced_rests')} hint={t('hos.rule_hint.DAILY_REST_9H')} />} checked={reducedRests} onChange={(e) => setReducedRests(e.currentTarget.checked)} />
                <Checkbox size="xs" label={<LabelHint label={t('calc.opt_reduced_weekly')} hint={t('hos.rule_hint.WEEKLY_REST_24H')} />} checked={reducedWeekly} onChange={(e) => setReducedWeekly(e.currentTarget.checked)} />
              </Stack>
              <Group justify="space-between">
                <Text size="sm" fw={500}>
                  {t('calc.other_costs')}
                </Text>
                <Button size="xs" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => setOthers((os) => [...os, { key: ++seq, label: '', amount: '', currency: 'EUR' }])}>
                  {t('calc.add_cost')}
                </Button>
              </Group>
              {others.map((o, i) => (
                <Group key={o.key} gap={6} wrap="nowrap" align="flex-start">
                  <TextInput size="xs" placeholder={t('calc.cost_label')} value={o.label} onChange={(e) => setOthers((os) => os.map((x) => (x.key === o.key ? { ...x, label: e.currentTarget.value } : x)))} style={{ flex: 2 }} />
                  <NumberInput size="xs" value={o.amount} onChange={(v) => setOthers((os) => os.map((x) => (x.key === o.key ? { ...x, amount: v } : x)))} decimalScale={2} decimalSeparator={dec} hideControls style={{ flex: 1 }} error={err(`options.otherCosts[${i}].amount`)} />
                  <Select size="xs" data={CURRENCIES} value={o.currency} onChange={(v) => setOthers((os) => os.map((x) => (x.key === o.key ? { ...x, currency: v } : x)))} w={90} />
                  <Tooltip label={t('common.delete')}>
                    <ActionIcon variant="subtle" color="red" onClick={() => setOthers((os) => os.filter((x) => x.key !== o.key))} aria-label={t('common.delete')}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              ))}

              <Checkbox
                label={<LabelHint label={t('calc.manual_legs')} hint={t('hint.calc.manual_legs')} />}
                checked={manualLegs}
                onChange={(e) => toggleManualLegs(e.currentTarget.checked)}
              />
              {manualLegs && (
                <>
                  <Group justify="space-between">
                    <Text size="xs" fw={600} tt="uppercase" c="dimmed">
                      {t('calc.legs')}
                    </Text>
                    <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setLegs((ls) => [...ls, { key: ++seq, name: '', distanceKm: '', durationH: '', country: ls.at(-1)?.country ?? 'PL', countries: null, serviceH: '' }])}>
                      {t('calc.add_leg')}
                    </Button>
                  </Group>
                  {err('legs') && (
                    <Text size="xs" c="red">
                      {err('legs')}
                    </Text>
                  )}
                  {legs.map((l, i) => (
                    <div key={l.key} className={classes.leg}>
                      <Stack gap={6}>
                        <Group gap={6} wrap="nowrap" align="flex-end">
                          <TextInput size="xs" maxLength={200} label={`${i + 1}. ${t('calc.leg_name')}`} placeholder={t(i === 0 ? 'calc.leg_name_first' : 'calc.leg_name_hint')} value={l.name} onChange={(e) => setLeg(l.key, { name: e.currentTarget.value })} style={{ flex: 1 }} />
                          <Tooltip label={t('common.delete')}>
                            <ActionIcon variant="subtle" color="red" onClick={() => setLegs((ls) => ls.filter((x) => x.key !== l.key))} disabled={legs.length === 1} aria-label={t('common.delete')} mb={2}>
                              <IconTrash size={16} />
                            </ActionIcon>
                          </Tooltip>
                        </Group>
                        <Group grow gap={6} align="flex-start">
                          <NumberInput size="xs" label={t('calc.distance_km')} value={l.distanceKm} onChange={(v) => setLeg(l.key, { distanceKm: v, countries: null })} min={0} max={20000} allowNegative={false} hideControls error={err(`legs[${i}].distanceKm`)} />
                          <NumberInput size="xs" label={t('calc.duration_h')} placeholder={t('calc.auto')} value={l.durationH} onChange={(v) => setLeg(l.key, { durationH: v })} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls error={err(`legs[${i}].durationMin`)} />
                          <NumberInput size="xs" label={<LabelHint label={t('calc.service_h')} hint={t('hint.field.service_h')} />} value={l.serviceH} onChange={(v) => setLeg(l.key, { serviceH: v })} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls error={err(`legs[${i}].serviceMin`)} />
                        </Group>
                        {l.countries ? (
                          <Group gap={6} wrap="nowrap">
                            <Text size="xs" c="dimmed" style={{ flex: 1 }}>
                              {l.countries
                                .map((c) => {
                                  const n = (v: number) => new Intl.NumberFormat(lang, { maximumFractionDigits: 1 }).format(v);
                                  return `${c.country} ${n(c.km)} km${c.tollKm != null ? ` (${t('calc.toll_km_short', { km: n(c.tollKm) })})` : ''}`;
                                })
                                .join(' · ')}
                            </Text>
                            <Tooltip label={t('calc.route.clear_countries')}>
                              <ActionIcon size="sm" variant="subtle" color="gray" onClick={() => setLeg(l.key, { countries: null })} aria-label={t('calc.route.clear_countries')}>
                                <IconTrash size={14} />
                              </ActionIcon>
                            </Tooltip>
                          </Group>
                        ) : (
                          <Select size="xs" label={t('trips.country')} data={countryOptions(lang, l.country)} value={l.country} onChange={(v) => setLeg(l.key, { country: v })} searchable error={err(`legs[${i}].countries`)} />
                        )}
                      </Stack>
                    </div>
                  ))}
                  <Text size="xs" c="dimmed">
                    {t('calc.legs_hint')}
                  </Text>
                </>
              )}
            </Stack>
          </Collapse>
        </Stack>
      </Paper>
      <Stack gap="md">
        <RouteMap route={route} picks={picks} onPick={pickOnMap} />
        {plan ? <CalcResult plan={plan} legNames={planLegNames} countries={planCountries} /> : <EmptyState text={t('calc.empty_simple')} />}
      </Stack>
    </div>
  );
}
