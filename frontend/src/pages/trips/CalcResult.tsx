import { Alert, Group, Paper, Stack, Table, Text } from '@mantine/core';
import { IconAlertTriangle, IconInfoCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { CalcPlan, CostLine, CountryKm, TimelineItem } from '@/api/calc';
import { useUser } from '@/auth/useAuth';
import { formatMinutes } from '@/pages/tacho/hosFormat';
import { countryName } from '@/utils/countries';
import { cx } from '@/utils/cx';
import { formatDate } from '@/utils/deadline';
import { formatInstantShort, formatMoney } from './tripFormat';
import classes from './Calc.module.css';

/** "2 d 5 h 30 min" for long durations, "5 h 30 min" below a day. */
function formatDuration(t: (k: string, o?: Record<string, unknown>) => string, min: number): string {
  const d = Math.floor(min / 1440);
  return d > 0 ? t('calc.days_and', { d, rest: formatMinutes(t as never, min % 1440) }) : formatMinutes(t as never, min);
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className={classes.stat}>
      <span className={classes.statLabel}>{label}</span>
      <span className={classes.statValue}>{value}</span>
    </div>
  );
}

/** Days of the timeline in the firma zone, for the day headers. */
function dayOf(iso: string, lang: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(lang, { weekday: 'short', day: '2-digit', month: '2-digit', timeZone }).format(new Date(iso));
}

/** Where a fuel price came from and its day, under the fuel line; a missing price is marked in red. */
function PriceSource({ line }: { line: CostLine }) {
  const { t, i18n } = useTranslation();
  if ((line.kind !== 'FUEL' && line.kind !== 'ADBLUE') || !line.source) return null;
  const day = line.priceDate ? new Intl.DateTimeFormat(i18n.resolvedLanguage ?? 'en', { dateStyle: 'short', timeZone: 'UTC' }).format(new Date(`${line.priceDate}T00:00:00Z`)) : null;
  return (
    <Text size="xs" c={line.source === 'MISSING' ? 'red' : 'dimmed'}>
      {t(`calc.source.${line.source}`)}
      {day ? ` · ${t('calc.price_date', { date: day })}` : ''}
    </Text>
  );
}

/**
 * The plan: arrival, totals, warnings, the timeline of driving / breaks / rests, km and toll km per country, the
 * drivers' state and the costs (fuel per country with where its price came from, tolls, driver pay, other).
 */
export function CalcResult({ plan, legNames, countries = [] }: { plan: CalcPlan; legNames: string[]; countries?: CountryKm[] }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const time = (iso: string) => new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit', timeZone: tz }).format(new Date(iso));
  const km = (v: number) => new Intl.NumberFormat(lang, { maximumFractionDigits: 0 }).format(v);
  const tot = plan.totals;
  const base = plan.costs.baseCurrency ?? null;

  // Group timeline items by the day they start on.
  const days: { day: string; items: TimelineItem[] }[] = [];
  for (const item of plan.timeline) {
    const day = dayOf(item.start, lang, tz);
    if (days.at(-1)?.day === day) days.at(-1)!.items.push(item);
    else days.push({ day, items: [item] });
  }

  return (
    <Stack gap="md">
      <Paper withBorder p="md">
        <Group justify="space-between" align="flex-end" wrap="wrap">
          <div>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {t('calc.eta')}
            </Text>
            <Text fz={26} fw={700} className={classes.mono}>
              {formatInstantShort(plan.eta, lang, tz)}
            </Text>
            <Text size="sm" c="dimmed">
              {t('calc.from', { start: formatInstantShort(plan.start, lang, tz) })} · {formatDuration(t, tot.durationMin)} · {km(tot.distanceKm)} km
            </Text>
          </div>
        </Group>
        <div className={classes.stats}>
          <Stat label={t('hos.activity.DRIVING')} value={formatMinutes(t, tot.drivingMin)} />
          <Stat label={t('calc.breaks')} value={formatMinutes(t, tot.breakMin)} />
          <Stat label={t('calc.rests')} value={formatMinutes(t, tot.restMin)} />
          <Stat label={t('calc.service')} value={formatMinutes(t, tot.workMin)} />
          <Stat label={t('calc.daily_rests')} value={String(tot.dailyRests)} />
          <Stat label={t('calc.weekly_rests')} value={String(tot.weeklyRests)} />
          <Stat label={t('calc.days')} value={String(tot.days)} />
        </div>
      </Paper>

      {(plan.warnings.length > 0 || plan.costs.missing.length > 0 || plan.payHidden) && (
        <Stack gap={6}>
          {plan.warnings.map((w) => (
            <Alert key={w} color="orange" variant="light" icon={<IconAlertTriangle size={16} />} p="xs">
              {t(w)}
            </Alert>
          ))}
          {plan.costs.missing.map((m) => (
            <Alert key={m} color="gray" variant="light" icon={<IconInfoCircle size={16} />} p="xs">
              {t(m)}
            </Alert>
          ))}
          {plan.payHidden && (
            <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} p="xs">
              {t('calc.pay_hidden')}
            </Alert>
          )}
        </Stack>
      )}

      <Paper withBorder p="md">
        <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={8}>
          {t('calc.timeline')}
        </Text>
        <Stack gap="sm">
          {days.map((d) => (
            <div key={d.day}>
              <Text size="sm" fw={600} tt="capitalize" mb={4}>
                {d.day}
              </Text>
              <ol className={classes.timeline}>
                {d.items.map((item, i) => (
                  <li key={i} className={cx(classes.item, classes[item.kind])}>
                    <span className={classes.when}>
                      {time(item.start)}–{time(item.end)}
                    </span>
                    <span className={classes.what}>
                      {t(`calc.kind.${item.kind}`)}
                      {item.driver ? ` · ${t('calc.driver_n', { n: item.driver })}` : ''}
                    </span>
                    <span className={classes.dur}>{formatMinutes(t, item.minutes)}</span>
                    <span className={classes.where}>
                      {item.kind === 'DRIVE' ? `${km(item.kmFrom)}–${km(item.kmTo)} km` : `${km(item.kmTo)} km`}
                      {legNames[item.legIndex] ? ` · ${legNames[item.legIndex]}` : ''}
                    </span>
                  </li>
                ))}
              </ol>
            </div>
          ))}
        </Stack>
      </Paper>

      {countries.length > 0 && (
        <Paper withBorder p="md">
          <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={8}>
            {t('calc.countries')}
          </Text>
          <Table fz="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('trips.country')}</Table.Th>
                <Table.Th className={classes.num}>km</Table.Th>
                <Table.Th className={classes.num}>{t('calc.toll_km')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {countries.map((c) => (
                <Table.Tr key={c.country}>
                  <Table.Td>{countryName(c.country, lang)}</Table.Td>
                  <Table.Td className={classes.num}>{km(c.km)}</Table.Td>
                  <Table.Td className={classes.num}>{c.tollKm != null ? km(c.tollKm) : '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      )}

      <Paper withBorder p="md">
        <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={8}>
          {t('calc.costs')}
        </Text>
        {plan.costs.lines.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('calc.no_costs')}
          </Text>
        ) : (
          <Table fz="sm">
            <Table.Tbody>
              {plan.costs.lines.map((l, i) => (
                <Table.Tr key={i}>
                  <Table.Td>
                    {t(`calc.cost.${l.kind}`)}
                    {l.country ? ` · ${countryName(l.country, lang)}` : ''}
                    {l.label ? ` · ${l.label}` : ''}
                    <PriceSource line={l} />
                  </Table.Td>
                  <Table.Td className={classes.num}>
                    {l.quantity != null && l.unit ? `${new Intl.NumberFormat(lang, { maximumFractionDigits: 2 }).format(l.quantity)} ${t(`calc.unit.${l.unit}`)}` : ''}
                  </Table.Td>
                  <Table.Td className={classes.num}>{l.rate != null && l.currency ? `× ${formatMoney(l.rate, l.currency, lang)}` : ''}</Table.Td>
                  <Table.Td className={classes.num}>
                    {l.amount != null && l.currency ? (
                      <>
                        <b>{formatMoney(l.amount, l.currency, lang)}</b>
                        {/* Another currency than the base one: its share of the base total, too. */}
                        {base && l.currency !== base && l.amountBase != null && (
                          <Text size="xs" c="dimmed">
                            ≈ {formatMoney(l.amountBase, base, lang)}
                          </Text>
                        )}
                      </>
                    ) : (
                      // Not priced: not part of the total, so it must not look like a zero.
                      <Group gap={4} justify="flex-end" wrap="nowrap" c="red">
                        <IconAlertTriangle size={14} />
                        <Text span size="sm" c="red" fw={600}>
                          {t('calc.no_rate')}
                        </Text>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
        <Group justify="flex-end" gap="lg" mt="sm">
          <Text size="sm" c="dimmed">
            {t('calc.total')}
          </Text>
          {plan.costs.totals.map((s) => (
            <Text key={s.currency} fw={700} className={classes.num}>
              {formatMoney(s.amount, s.currency, lang)}
            </Text>
          ))}
        </Group>
        {base && plan.costs.totalBase != null && (
          <Group justify="flex-end" gap="lg" mt={4}>
            <Text size="sm" c="dimmed">
              {t('calc.total_base', { currency: base })}
            </Text>
            <Text fz="lg" fw={700} className={classes.num}>
              {formatMoney(plan.costs.totalBase, base, lang)}
            </Text>
          </Group>
        )}
        {base && (plan.costs.fxRates ?? []).length > 0 && (
          <Text size="xs" c="dimmed" ta="right" mt={4}>
            {(plan.costs.fxRates ?? [])
              .map((r) =>
                t('calc.fx_rate', {
                  currency: r.currency,
                  rate: new Intl.NumberFormat(lang, { maximumFractionDigits: 4 }).format(r.rate),
                  base,
                  source: r.source ?? '—',
                  date: r.rateDate ? formatDate(r.rateDate, lang) : '—',
                }),
              )
              .join(' · ')}
          </Text>
        )}
      </Paper>

      {plan.driverStates.length > 0 && (
        <Paper withBorder p="md">
          <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={8}>
            {t('calc.driver_states')}
          </Text>
          {/* Seven columns do not fit a phone: the table scrolls sideways inside its card. */}
          <Table.ScrollContainer minWidth={720}>
            <Table fz="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th />
                  <Table.Th>{t('calc.state_source')}</Table.Th>
                  <Table.Th className={classes.num}>{t('hos.summary.weekly')}</Table.Th>
                  <Table.Th className={classes.num}>{t('hos.summary.two_weeks')}</Table.Th>
                  <Table.Th className={classes.num}>{t('calc.extensions_left')}</Table.Th>
                  <Table.Th className={classes.num}>{t('calc.reduced_left')}</Table.Th>
                  <Table.Th>{t('hos.summary.weekly_rest_by')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {plan.driverStates.map((s, i) => (
                  <Table.Tr key={i}>
                    <Table.Td>{t('calc.driver_n', { n: i + 1 })}</Table.Td>
                    <Table.Td>
                      {t(`calc.state.${s.source}`)}
                      {s.dataUntil ? ` · ${t('calc.data_until', { at: formatInstantShort(s.dataUntil, lang, tz) })}` : ''}
                    </Table.Td>
                    <Table.Td className={classes.num}>{formatMinutes(t, s.weekDrivingMin)}</Table.Td>
                    <Table.Td className={classes.num}>{formatMinutes(t, s.fortnightDrivingMin)}</Table.Td>
                    <Table.Td className={classes.num}>{s.extensionsLeft}</Table.Td>
                    <Table.Td className={classes.num}>{s.reducedRestsLeft}</Table.Td>
                    <Table.Td>{s.weeklyRestDueAt ? formatInstantShort(s.weeklyRestDueAt, lang, tz) : '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Paper>
      )}
    </Stack>
  );
}
