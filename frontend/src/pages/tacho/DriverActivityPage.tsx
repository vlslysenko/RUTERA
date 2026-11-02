import { useEffect, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ActionIcon, Anchor, Button, Group, Paper, Progress, Select, Stack, Text, Tooltip } from '@mantine/core';
import { IconChevronLeft, IconChevronRight, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { ACTIVITIES, HOS, HOS_KEY, hosApi, type ActivityInterval, type HosDay } from '@/api/hos';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { StatusChip } from '@/components/StatusChip';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { dayStart, formatMinutes, mondayOf, shiftDays, toYmd } from './hosFormat';
import classes from './Hos.module.css';

const DAY_MS = 86_400_000;

function hhmm(ms: number, lang: string): string {
  return new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
}

/** 24-hour strip of one day: coloured segments per activity, the exact times in the tooltip. */
function DayBar({ day, intervals }: { day: string; intervals: ActivityInterval[] }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const start = dayStart(day);
  const end = start + DAY_MS;
  const segs = intervals
    .map((iv) => ({ iv, a: Math.max(start, Date.parse(iv.startAt)), b: Math.min(end, Date.parse(iv.endAt)) }))
    .filter((s) => s.b > s.a);
  return (
    <div className={classes.bar} role="img" aria-label={formatDate(day, lang)}>
      {segs.map(({ iv, a, b }, i) => (
        <Tooltip
          key={i}
          label={`${t(`hos.activity.${iv.activity}`)} ${hhmm(a, lang)}–${b === end ? '24:00' : hhmm(b, lang)}${iv.vehicle ? ` · ${iv.vehicle.plate}` : ''}`}
          withinPortal
        >
          <div
            className={cx(classes.seg, classes[iv.activity])}
            style={{ left: `${((a - start) / DAY_MS) * 100}%`, width: `${((b - a) / DAY_MS) * 100}%` }}
          />
        </Tooltip>
      ))}
    </div>
  );
}

function Stat({ label, value, ratio }: { label: string; value: string; ratio?: number }) {
  const color = ratio == null ? undefined : ratio >= 1 ? 'red' : ratio >= 0.9 ? 'yellow' : 'blue';
  return (
    <div className={classes.stat}>
      <span className={classes.statLabel}>{label}</span>
      <span className={classes.statValue}>{value}</span>
      {ratio != null && <Progress value={Math.min(100, ratio * 100)} color={color} size="sm" aria-hidden />}
    </div>
  );
}

/** Where the driver stands against 561/2006 at the end of the shown week (or today, for the current week). */
function Summary({ days }: { days: HosDay[] }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const today = toYmd(new Date());
  const past = days.filter((d) => d.day <= today);
  const ref = past[past.length - 1];
  if (!ref) return null;
  const isCurrentWeek = days.some((d) => d.day === today);
  const of = (v: number, limit: number) => t('hos.of', { value: formatMinutes(t, v), limit: formatMinutes(t, limit) });
  return (
    <div className={classes.summary}>
      <Stat label={t('hos.summary.weekly')} value={of(ref.weeklyDrivingMin, HOS.WEEKLY_DRIVING)} ratio={ref.weeklyDrivingMin / HOS.WEEKLY_DRIVING} />
      <Stat
        label={t('hos.summary.two_weeks')}
        value={of(ref.biweeklyDrivingMin, HOS.TWO_WEEK_DRIVING)}
        ratio={ref.biweeklyDrivingMin / HOS.TWO_WEEK_DRIVING}
      />
      <Stat
        label={t('hos.summary.extended')}
        value={t('hos.of', { value: ref.extDrivingInWeek, limit: HOS.EXTENSIONS_PER_WEEK })}
        ratio={ref.extDrivingInWeek / HOS.EXTENSIONS_PER_WEEK}
      />
      <Stat
        label={t('hos.summary.reduced')}
        value={t('hos.of', { value: ref.reducedRestInWeek, limit: HOS.REDUCED_RESTS_BETWEEN_WEEKLY })}
        ratio={ref.reducedRestInWeek / HOS.REDUCED_RESTS_BETWEEN_WEEKLY}
      />
      {isCurrentWeek && <Stat label={t('hos.summary.left_today')} value={formatMinutes(t, ref.remainingDailyMin)} />}
      {isCurrentWeek && <Stat label={t('hos.summary.left_week')} value={formatMinutes(t, ref.remainingWeeklyMin)} />}
      {ref.nextWeeklyRestBy && (
        <Stat
          label={t('hos.summary.weekly_rest_by')}
          value={new Intl.DateTimeFormat(lang, { weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }).format(
            new Date(ref.nextWeeklyRestBy),
          )}
        />
      )}
    </div>
  );
}

/**
 * Driver activity (Tachograph -> Activity): one week of a driver, a 24-hour strip per day with driving, other work,
 * availability and rest, the day totals, and where the week stands against 561/2006. URL: ?driver=&week=YYYY-MM-DD.
 */
export function DriverActivityPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const [params, setParams] = useSearchParams();
  const canListDrivers = hasPrivilege(me, PRIV.DRIVER_VIEW);
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list, enabled: canListDrivers });
  const active = useMemo(() => (drivers.data ?? []).filter((d) => d.status === 'ACTIVE'), [drivers.data]);

  const driverId = params.get('driver');
  const week = params.get('week') ?? mondayOf(new Date());
  const to = shiftDays(week, 6);
  const today = toYmd(new Date());

  // Open the first driver when none is chosen yet.
  useEffect(() => {
    if (!driverId && active.length > 0) {
      setParams((p) => {
        p.set('driver', String(active[0].id));
        return p;
      }, { replace: true });
    }
  }, [driverId, active, setParams]);

  const q = useQuery({
    queryKey: [...HOS_KEY, driverId, week],
    queryFn: () => hosApi.activity(Number(driverId), week, to),
    enabled: !!driverId,
  });

  const setParam = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      return p;
    });

  const notReady = q.isError && q.error instanceof ApiError && q.error.status === 404 && !!driverId;
  const days: string[] = Array.from({ length: 7 }, (_, i) => shiftDays(week, i));
  const byDay = new Map((q.data?.days ?? []).map((d) => [d.day, d]));
  const weekday = (d: string) => new Intl.DateTimeFormat(lang, { weekday: 'short' }).format(new Date(dayStart(d)));

  if (!canListDrivers) return <EmptyState text={t('auth.access_denied')} />;

  return (
    <Stack gap="md">
      <div className={classes.toolbar}>
        <Select
          label={t('fleet.driver')}
          data={(drivers.data ?? [])
            .filter((d) => d.status === 'ACTIVE' || String(d.id) === driverId)
            .map((d) => ({ value: String(d.id), label: d.name }))}
          value={driverId}
          onChange={(v) => setParam('driver', v)}
          searchable
          w={240}
          placeholder={t('hos.choose_driver')}
        />
        <Group gap={4} wrap="nowrap">
          <Tooltip label={t('hos.week.prev')}>
            <ActionIcon variant="default" size="lg" aria-label={t('hos.week.prev')} onClick={() => setParam('week', shiftDays(week, -7))}>
              <IconChevronLeft size={16} />
            </ActionIcon>
          </Tooltip>
          <Text className={classes.weekLabel} px={6}>
            {formatDate(week, lang)} – {formatDate(to, lang)}
          </Text>
          <Tooltip label={t('hos.week.next')}>
            <ActionIcon
              variant="default"
              size="lg"
              aria-label={t('hos.week.next')}
              onClick={() => setParam('week', shiftDays(week, 7))}
              disabled={to >= today}
            >
              <IconChevronRight size={16} />
            </ActionIcon>
          </Tooltip>
          <Button variant="subtle" onClick={() => setParam('week', null)} disabled={week === mondayOf(new Date())}>
            {t('hos.week.this')}
          </Button>
        </Group>
      </div>

      {!driverId ? (
        <EmptyState text={drivers.isPending ? t('common.loading') : t(active.length ? 'hos.choose_driver' : 'drivers.empty')} />
      ) : q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : notReady ? (
        <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.intervals.length === 0 ? (
        <EmptyState text={t('hos.no_data')} />
      ) : (
        <>
          <Summary days={q.data.days} />
          <Paper p="sm" className={classes.days}>
            <div className={classes.grid}>
              <span className={classes.head}>{t('hos.col.day')}</span>
              <div className={classes.ticks}>
                {[0, 6, 12, 18, 24].map((h) => (
                  <span key={h} className={classes.tick} style={{ left: `${(h / 24) * 100}%` }}>
                    {String(h).padStart(2, '0')}
                  </span>
                ))}
              </div>
              <span className={cx(classes.head, classes.num)}>{t('hos.activity.DRIVING')}</span>
              <span className={cx(classes.head, classes.num)}>{t('hos.activity.WORK')}</span>
              <span className={cx(classes.head, classes.num)}>{t('hos.activity.AVAILABILITY')}</span>
              <span className={cx(classes.head, classes.num)}>{t('hos.col.daily_rest')}</span>
              <span className={classes.head}>{t('tacho.tab.violations')}</span>
              {days.map((day) => {
                const d = byDay.get(day);
                return (
                  <div key={day} className={classes.row}>
                    <span className={cx(classes.dayCell, day === today && classes.today)}>
                      {weekday(day)} {formatDate(day, lang).slice(0, 5)}
                    </span>
                    <div>
                      <DayBar day={day} intervals={q.data.intervals} />
                    </div>
                    <span className={classes.num}>{d && d.drivingMin > 0 ? formatMinutes(t, d.drivingMin) : '—'}</span>
                    <span className={classes.num}>{d && d.workMin > 0 ? formatMinutes(t, d.workMin) : '—'}</span>
                    <span className={classes.num}>{d && d.availabilityMin > 0 ? formatMinutes(t, d.availabilityMin) : '—'}</span>
                    <span className={classes.num}>{d?.dailyRestMin != null ? formatMinutes(t, d.dailyRestMin) : '—'}</span>
                    <span>
                      {d && d.violations > 0 ? (
                        <Anchor component={Link} to={`/tacho/violations?driver=${driverId}&from=${day}&to=${day}`} underline="never">
                          <StatusChip tone="crit">{d.violations}</StatusChip>
                        </Anchor>
                      ) : (
                        <Text span c="dimmed">
                          —
                        </Text>
                      )}
                    </span>
                  </div>
                );
              })}
            </div>
          </Paper>
          <div className={classes.legend}>
            {ACTIVITIES.map((a) => (
              <span key={a}>
                <span className={cx(classes.swatch, classes[a])} />
                {t(`hos.activity.${a}`)}
              </span>
            ))}
          </div>
        </>
      )}
    </Stack>
  );
}
