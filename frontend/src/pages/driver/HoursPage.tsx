import { useQuery } from '@tanstack/react-query';
import { Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { HOS, HOS_KEY, hosApi, VIOLATIONS_KEY } from '@/api/hos';
import { useUser } from '@/auth/useAuth';
import { formatMinutes, shiftDays, ymdIn } from '@/pages/tacho/hosFormat';
import { SeverityChip } from '@/pages/tacho/ViolationChips';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { cx } from '@/utils/cx';
import { Card, PartError, Stat, Waiting } from './DriverParts';
import classes from './DriverApp.module.css';

/** "Hours": what is left under 561/2006, driving of the last seven days and own violations of four weeks. */
export function HoursPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const driverId = user.driver!.id;
  const tz = user.firma?.timezone ?? 'UTC';
  const today = ymdIn(new Date().toISOString(), tz);
  const from = shiftDays(today, -6);

  const hos = useQuery({ queryKey: [...HOS_KEY, driverId, from, today], queryFn: () => hosApi.activity(driverId, from, today), retry: false });
  const filter = { driverId, from: shiftDays(today, -27), to: today, size: 20 };
  const violations = useQuery({ queryKey: [...VIOLATIONS_KEY, 'list', filter], queryFn: () => hosApi.violations(filter), retry: false });

  const days = hos.data?.days ?? [];
  const day = days.at(-1);
  const hours = new Intl.NumberFormat(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const weekday = (ymd: string) => new Intl.DateTimeFormat(lang, { weekday: 'short', timeZone: 'UTC' }).format(new Date(`${ymd}T12:00:00Z`));

  return (
    <Stack gap="sm">
      <Card title={t('driver_app.hours_now')}>
        {hos.isPending ? (
          <Waiting query={hos} />
        ) : (hos.isError && hos.data === undefined) || !day ? (
          <PartError error={hos.error} />
        ) : (
          <Stack gap="sm">
            <div className={classes.stats}>
              <div className={classes.stat}>
                <span className={classes.statLabel}>{t('hos.summary.left_today')}</span>
                <span className={classes.big}>{formatMinutes(t, day.remainingDailyMin)}</span>
              </div>
              <div className={classes.stat}>
                <span className={classes.statLabel}>{t('hos.summary.left_week')}</span>
                <span className={classes.big}>{formatMinutes(t, day.remainingWeeklyMin)}</span>
              </div>
            </div>
            <div className={classes.stats}>
              <Stat label={t('hos.summary.weekly')} value={formatMinutes(t, day.weeklyDrivingMin)} />
              <Stat label={t('hos.summary.two_weeks')} value={`${formatMinutes(t, day.biweeklyDrivingMin)} / 90 h`} />
              <Stat label={t('hos.summary.extended')} value={`${day.extDrivingInWeek} / ${HOS.EXTENSIONS_PER_WEEK}`} />
              <Stat label={t('hos.summary.reduced')} value={`${day.reducedRestInWeek} / ${HOS.REDUCED_RESTS_BETWEEN_WEEKLY}`} />
              <Stat label={t('hos.summary.weekly_rest_by')} value={day.nextWeeklyRestBy ? formatInstantShort(day.nextWeeklyRestBy, lang, tz) : '—'} />
            </div>
          </Stack>
        )}
      </Card>

      {days.length > 0 && (
        <Card title={t('driver_app.last_days')}>
          <div className={classes.bars}>
            {days.map((d) => (
              <div key={d.day} className={classes.bar} title={formatMinutes(t, d.drivingMin)}>
                <span className={classes.mono}>{hours.format(d.drivingMin / 60)}</span>
                <div
                  className={cx(classes.barFill, d.drivingMin > HOS.DAILY_DRIVING && classes.barOver)}
                  style={{ height: `${Math.min(100, (d.drivingMin / HOS.DAILY_DRIVING_EXTENDED) * 100)}%` }}
                />
                <span>{weekday(d.day)}</span>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Card title={t('driver_app.violations')}>
        {violations.isPending ? (
          <Waiting query={violations} />
        ) : violations.isError && violations.data === undefined ? (
          <PartError error={violations.error} />
        ) : violations.data.items.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('driver_app.no_violations')}
          </Text>
        ) : (
          violations.data.items.map((v) => (
            <div key={v.id} className={classes.row}>
              <div style={{ minWidth: 0 }}>
                <Text size="sm" fw={600}>
                  {t(`hos.rule.${v.rule}`)}
                </Text>
                <Text size="xs" c="dimmed" className={classes.mono}>
                  {formatInstantShort(v.startAt, lang, tz)}
                </Text>
              </div>
              <SeverityChip severity={v.severity} />
            </div>
          ))
        )}
      </Card>
    </Stack>
  );
}
