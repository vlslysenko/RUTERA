import { useState } from 'react';
import { ActionIcon, Alert, Button, Checkbox, Group, NumberInput, Paper, SegmentedControl, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { IconCalculator, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { calcApi, type CalcPlan, type CalcRequest, type CalcRoute, type DriverStateSource } from '@/api/calc';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { decimalSeparator } from '@/forms/number';
import { countryOptions } from '@/utils/countries';
import { fromZonedInput, toZonedInput } from '@/utils/zone';
import { DateField } from '@/forms/DateField';
import { CalcResult } from './CalcResult';
import { RoutePanel } from './RoutePanel';
import { idOrNull, useCrewOptions } from './crew';
import { CURRENCIES } from './tripFormat';
import classes from './Calc.module.css';

interface LegRow {
  key: number;
  name: string;
  distanceKm: number | string;
  durationH: number | string;
  country: string | null;
  /** Km per country of a leg from the route; typing the distance by hand drops them. */
  countries: { country: string; km: number }[] | null;
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

let seq = 0;
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
 * Trip calculator: when will the truck arrive under 561/2006 (breaks, daily and weekly rests, crew of one or two)
 * and what will it cost (fuel, driver pay, tolls, other). Legs are typed in by hand (km, time, country, service) or come
 * from a truck route built on the map (km per country).
 */
export function CalculatorPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const [start, setStart] = useState(nextHour(tz));
  const [vehicleId, setVehicleId] = useState<string | null>(null);
  const [driver1, setDriver1] = useState<string | null>(null);
  const [driver2, setDriver2] = useState<string | null>(null);
  const [crewSize, setCrewSize] = useState<'1' | '2'>('1');
  const [driverState, setDriverState] = useState<DriverStateSource>('FRESH');
  const [manual, setManual] = useState<ManualRow>({ sinceBreakH: 0, inShiftH: 0, shiftStart: '', weekH: 0, fortnightH: 0, extensionsLeft: 2, reducedLeft: 3, lastWeeklyRestEnd: '' });
  const [legs, setLegs] = useState<LegRow[]>([
    { key: ++seq, name: '', distanceKm: 0, durationH: '', country: 'PL', countries: null, serviceH: 1 },
    { key: ++seq, name: '', distanceKm: '', durationH: '', country: 'PL', countries: null, serviceH: 1 },
  ]);
  const [avgSpeed, setAvgSpeed] = useState<number | string>(70);
  const [extended, setExtended] = useState(true);
  const [reducedRests, setReducedRests] = useState(true);
  const [reducedWeekly, setReducedWeekly] = useState(false);
  const [consumption, setConsumption] = useState<number | string>('');
  const [fuelPrice, setFuelPrice] = useState<number | string>('');
  const [fuelCurrency, setFuelCurrency] = useState<string | null>('EUR');
  const [others, setOthers] = useState<OtherRow[]>([]);
  const [plan, setPlan] = useState<CalcPlan | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const crew = useCrewOptions([vehicleId, driver1, driver2]);
  const dec = decimalSeparator(lang);
  const err = (key: string) => (errors[key] ? t(errors[key]) : undefined);

  const setLeg = (key: number, patch: Partial<LegRow>) => setLegs((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  const driverCount = [driver1, driver2].filter(Boolean).length;

  /** A built route replaces the legs: loading at the first point (0 km), then one leg per stretch with its countries. */
  const fromRoute = (r: CalcRoute) => {
    setLegs((old) => [
      { key: ++seq, name: r.points[0]?.label ?? '', distanceKm: 0, durationH: '', country: r.legs[0]?.countries[0]?.country ?? 'PL', countries: null, serviceH: old[0]?.serviceH ?? 1 },
      ...r.legs.map((l, i) => ({
        key: ++seq,
        name: `${l.from} – ${l.to}`,
        distanceKm: l.distanceKm,
        durationH: Math.round((l.durationMin / 60) * 100) / 100,
        country: l.countries[0]?.country ?? null,
        countries: l.countries,
        serviceH: old[i + 1]?.serviceH ?? 1,
      })),
    ]);
    setPlan(null);
  };
  const effectiveCrew = driverCount === 2 ? '2' : crewSize;

  const calculate = async () => {
    const local: Record<string, string> = {};
    if (!start) local.start = 'validation.required';
    legs.forEach((l, i) => {
      if (num(l.distanceKm) == null) local[`legs[${i}].distanceKm`] = 'validation.required';
    });
    setErrors(local);
    if (Object.keys(local).length) return;
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
      legs: legs.map((l) => ({
        name: l.name.trim() || null,
        distanceKm: num(l.distanceKm) ?? 0,
        durationMin: minutes(l.durationH),
        countries: l.countries ?? (l.country && (num(l.distanceKm) ?? 0) > 0 ? [{ country: l.country, km: num(l.distanceKm) ?? 0 }] : null),
        serviceMin: minutes(l.serviceH),
      })),
      options: {
        avgSpeedKmh: num(avgSpeed),
        extendedDriving: extended,
        reducedRests,
        reducedWeeklyRest: reducedWeekly,
        consumptionL100km: num(consumption),
        fuelPrice: num(fuelPrice),
        fuelCurrency: num(fuelPrice) != null ? fuelCurrency : null,
        otherCosts: others
          .filter((o) => o.label.trim() && num(o.amount) != null && o.currency)
          .map((o) => ({ label: o.label.trim(), amount: num(o.amount) ?? 0, currency: o.currency ?? 'EUR' })),
      },
    };
    setBusy(true);
    setFailure(null);
    try {
      setPlan(await calcApi.plan(body));
    } catch (e) {
      if (e instanceof ApiError && e.details) setErrors(e.details);
      setFailure(errorCode(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={classes.layout}>
      <Paper withBorder p="md">
        <Stack gap="sm">
          <DateField withTime label={t('calc.start')} value={start} onChange={setStart} error={err('start')} />
          <Select label={t('trips.vehicle')} description={t('calc.vehicle_hint')} data={crew.vehicles} value={vehicleId} onChange={setVehicleId} clearable searchable error={err('vehicleId')} />
          <Group grow align="flex-start">
            <Select
              label={t('calc.driver_n', { n: 1 })}
              data={crew.drivers}
              value={driver1}
              onChange={(v) => {
                setDriver1(v);
                // Tachograph data only exist for a chosen driver.
                if (v && driverState === 'FRESH') setDriverState('TACHO');
                if (!v) {
                  setDriver2(null);
                  if (driverState === 'TACHO') setDriverState('FRESH');
                }
              }}
              clearable
              searchable
              error={err('driverIds')}
            />
            <Select label={t('calc.driver_n', { n: 2 })} data={crew.drivers} value={driver2} onChange={setDriver2} clearable searchable disabled={!driver1} />
          </Group>
          <Group gap="md" wrap="wrap">
            <div>
              <Text size="sm" fw={500} mb={4}>
                {t('calc.crew')}
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
                {t('calc.driver_state')}
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
                  <NumberInput size="xs" label={t('calc.m.since_break')} value={manual.sinceBreakH} onChange={(v) => setManual((m) => ({ ...m, sinceBreakH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                  <NumberInput size="xs" label={t('calc.m.in_shift')} value={manual.inShiftH} onChange={(v) => setManual((m) => ({ ...m, inShiftH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                </Group>
                <Group grow align="flex-start">
                  <NumberInput size="xs" label={t('calc.m.week')} value={manual.weekH} onChange={(v) => setManual((m) => ({ ...m, weekH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                  <NumberInput size="xs" label={t('calc.m.fortnight')} value={manual.fortnightH} onChange={(v) => setManual((m) => ({ ...m, fortnightH: v }))} decimalScale={2} decimalSeparator={dec} min={0} hideControls />
                </Group>
                <Group grow align="flex-start">
                  <NumberInput size="xs" label={t('calc.extensions_left')} value={manual.extensionsLeft} onChange={(v) => setManual((m) => ({ ...m, extensionsLeft: v }))} min={0} max={2} allowDecimal={false} />
                  <NumberInput size="xs" label={t('calc.reduced_left')} value={manual.reducedLeft} onChange={(v) => setManual((m) => ({ ...m, reducedLeft: v }))} min={0} max={3} allowDecimal={false} />
                </Group>
                <Group grow align="flex-start">
                  <DateField withTime size="xs" label={t('calc.m.shift_start')} value={manual.shiftStart} onChange={(v) => setManual((m) => ({ ...m, shiftStart: v }))} />
                  <DateField withTime size="xs" label={t('calc.m.last_weekly_rest')} value={manual.lastWeeklyRestEnd} onChange={(v) => setManual((m) => ({ ...m, lastWeeklyRestEnd: v }))} />
                </Group>
                {err('manualState') && (
                  <Text size="xs" c="red">
                    {err('manualState')}
                  </Text>
                )}
              </Stack>
            </Paper>
          )}

          <Group justify="space-between" mt={4}>
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
                  <TextInput size="xs" label={`${i + 1}. ${t('calc.leg_name')}`} placeholder={t(i === 0 ? 'calc.leg_name_first' : 'calc.leg_name_hint')} value={l.name} onChange={(e) => setLeg(l.key, { name: e.currentTarget.value })} style={{ flex: 1 }} />
                  <Tooltip label={t('common.delete')}>
                    <ActionIcon variant="subtle" color="red" onClick={() => setLegs((ls) => ls.filter((x) => x.key !== l.key))} disabled={legs.length === 1} aria-label={t('common.delete')} mb={2}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
                <Group grow gap={6} align="flex-start">
                  <NumberInput size="xs" label={t('calc.distance_km')} value={l.distanceKm} onChange={(v) => setLeg(l.key, { distanceKm: v, countries: null })} min={0} max={20000} allowNegative={false} hideControls error={err(`legs[${i}].distanceKm`)} />
                  <NumberInput size="xs" label={t('calc.duration_h')} placeholder={t('calc.auto')} value={l.durationH} onChange={(v) => setLeg(l.key, { durationH: v })} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls error={err(`legs[${i}].durationMin`)} />
                  <NumberInput size="xs" label={t('calc.service_h')} value={l.serviceH} onChange={(v) => setLeg(l.key, { serviceH: v })} decimalScale={2} decimalSeparator={dec} allowNegative={false} hideControls error={err(`legs[${i}].serviceMin`)} />
                </Group>
                {l.countries ? (
                  <Group gap={6} wrap="nowrap">
                    <Text size="xs" c="dimmed" style={{ flex: 1 }}>
                      {l.countries.map((c) => `${c.country} ${new Intl.NumberFormat(lang, { maximumFractionDigits: 1 }).format(c.km)} km`).join(' · ')}
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

          <Text size="xs" fw={600} tt="uppercase" c="dimmed" mt={4}>
            {t('calc.options')}
          </Text>
          <Group grow align="flex-start">
            <NumberInput size="xs" label={t('calc.avg_speed')} value={avgSpeed} onChange={setAvgSpeed} min={20} max={120} allowDecimal={false} error={err('options.avgSpeedKmh')} />
            <NumberInput size="xs" label={t('calc.consumption')} placeholder={t('calc.from_vehicle')} value={consumption} onChange={setConsumption} decimalScale={1} decimalSeparator={dec} allowNegative={false} hideControls />
          </Group>
          <Group grow align="flex-start">
            <NumberInput size="xs" label={t('calc.fuel_price')} placeholder={t('calc.from_fuel')} value={fuelPrice} onChange={setFuelPrice} decimalScale={3} decimalSeparator={dec} allowNegative={false} hideControls />
            <Select size="xs" label={t('orders.currency')} data={CURRENCIES} value={fuelCurrency} onChange={setFuelCurrency} searchable error={err('options.fuelCurrency')} />
          </Group>
          <Stack gap={4}>
            <Checkbox size="xs" label={t('calc.opt_extended')} checked={extended} onChange={(e) => setExtended(e.currentTarget.checked)} />
            <Checkbox size="xs" label={t('calc.opt_reduced_rests')} checked={reducedRests} onChange={(e) => setReducedRests(e.currentTarget.checked)} />
            <Checkbox size="xs" label={t('calc.opt_reduced_weekly')} checked={reducedWeekly} onChange={(e) => setReducedWeekly(e.currentTarget.checked)} />
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
          <Button leftSection={<IconCalculator size={16} />} onClick={() => void calculate()} loading={busy} mt="xs">
            {t('calc.calculate')}
          </Button>
          {failure && (
            <Alert color="red" variant="light" p="xs">
              {t(failure)}
            </Alert>
          )}
        </Stack>
      </Paper>
      <Stack gap="md">
        <RoutePanel vehicleId={idOrNull(vehicleId)} onRoute={fromRoute} />
        {plan ? <CalcResult plan={plan} legNames={legs.map((l, i) => l.name.trim() || t('calc.leg_n', { n: i + 1 }))} /> : <EmptyState text={t('calc.empty')} />}
      </Stack>
    </div>
  );
}
