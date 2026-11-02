import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ActionIcon, Anchor, Button, Group, Paper, Select, Stack, Switch, Text, Tooltip } from '@mantine/core';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { ABSENCE_KINDS, HR_KEY, hrApi, type Absence, type AbsenceKind } from '@/api/hr';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { cx } from '@/utils/cx';
import { AbsenceRequests } from './AbsenceRequests';
import { formatMonth, formatRange, monthOf, monthRange, shiftMonth, weekdayIndex } from './hrFormat';
import classes from './Hr.module.css';

interface Row {
  id: number;
  name: string;
  absences: Absence[];
}

/**
 * Who is absent when: drivers × days of a month, a coloured bar per approved absence; a name opens the driver's absences.
 * Above it, the open requests from the driver cabinet for those who decide them.
 */
export function AbsenceCalendarPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const [month, setMonth] = useState(monthOf());
  const [kind, setKind] = useState<AbsenceKind | null>(null);
  const [onlyAbsent, setOnlyAbsent] = useState(false);
  const canDecide = hasPrivilege(useUser(), PRIV.DRIVER_ABSENCE_MANAGE);
  const [from, to] = monthRange(month);
  const days = useMemo(() => {
    const n = Number(to.slice(8));
    return Array.from({ length: n }, (_, i) => `${month}-${String(i + 1).padStart(2, '0')}`);
  }, [month, to]);

  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list });
  const absences = useQuery({
    queryKey: [...HR_KEY, 'absences', month, kind],
    queryFn: () => hrApi.firmAbsences(from, to, kind ?? undefined, undefined, 'APPROVED'),
  });

  const rows: Row[] = useMemo(() => {
    const byDriver = new Map<number, Absence[]>();
    for (const a of absences.data ?? []) byDriver.set(a.driver.id, [...(byDriver.get(a.driver.id) ?? []), a]);
    const list: Row[] = (drivers.data ?? [])
      .filter((d) => d.status === 'ACTIVE' || byDriver.has(d.id))
      .map((d) => ({ id: d.id, name: d.name, absences: byDriver.get(d.id) ?? [] }));
    // Absences of drivers the list does not return (deleted) still show.
    for (const [id, items] of byDriver) if (!list.some((r) => r.id === id)) list.push({ id, name: items[0].driver.name, absences: items });
    return list.filter((r) => !onlyAbsent || r.absences.length > 0).sort((a, b) => a.name.localeCompare(b.name));
  }, [drivers.data, absences.data, onlyAbsent]);

  const at = (r: Row, day: string) => r.absences.find((a) => a.fromDate <= day && day <= a.toDate);
  /** Working days (Mon–Fri) of this month the driver is away. */
  const workingDaysAway = (r: Row) => days.filter((d) => weekdayIndex(d) < 5 && at(r, d)).length;

  const error = drivers.error ?? absences.error;

  return (
    <Stack gap="sm">
      {canDecide && <AbsenceRequests />}
      <Group justify="space-between" wrap="wrap">
        <Group gap={4} wrap="nowrap">
          <Tooltip label={t('days.prev_month')}>
            <ActionIcon variant="default" size="lg" onClick={() => setMonth((m) => shiftMonth(m, -1))} aria-label={t('days.prev_month')}>
              <IconChevronLeft size={16} />
            </ActionIcon>
          </Tooltip>
          <Text fw={600} px={6} className={classes.monthNav}>
            {formatMonth(month, lang)}
          </Text>
          <Tooltip label={t('days.next_month')}>
            <ActionIcon variant="default" size="lg" onClick={() => setMonth((m) => shiftMonth(m, 1))} aria-label={t('days.next_month')}>
              <IconChevronRight size={16} />
            </ActionIcon>
          </Tooltip>
          <Button variant="subtle" onClick={() => setMonth(monthOf())} disabled={month === monthOf()}>
            {t('days.this_month')}
          </Button>
        </Group>
        <Group gap="sm" wrap="wrap">
          <Select
            placeholder={t('absences.kind')}
            aria-label={t('absences.kind')}
            data={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`absence.kind.${k}`) }))}
            value={kind}
            onChange={(v) => setKind(v as AbsenceKind | null)}
            clearable
            w={180}
          />
          <Switch label={t('absences.only_absent')} checked={onlyAbsent} onChange={(e) => setOnlyAbsent(e.currentTarget.checked)} />
        </Group>
      </Group>

      {drivers.isPending || absences.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : error ? (
        <ErrorState
          message={t(errorCode(error))}
          onRetry={() => {
            void drivers.refetch();
            void absences.refetch();
          }}
        />
      ) : rows.length === 0 ? (
        <EmptyState text={t(onlyAbsent ? 'absences.nobody' : 'drivers.empty')} />
      ) : (
        <Paper p="xs" className={classes.absWrap}>
          <div className={classes.absGrid} style={{ gridTemplateColumns: `minmax(160px, max-content) repeat(${days.length}, 26px) 60px` }}>
            <span className={classes.absHead} style={{ textAlign: 'left' }}>
              {t('trips.driver')}
            </span>
            {days.map((d) => (
              <span key={d} className={cx(classes.absHead, weekdayIndex(d) >= 5 && classes.weekend)}>
                {Number(d.slice(8))}
              </span>
            ))}
            <span className={classes.absHead} title={t('absences.working_days_hint')}>
              {t('absences.working_days')}
            </span>
            {rows.map((r) => (
              <div key={r.id} style={{ display: 'contents' }}>
                <span className={classes.absName}>
                  <Anchor component={Link} to={`/drivers/${r.id}?tab=absences`} size="sm">
                    {r.name}
                  </Anchor>
                </span>
                {days.map((d) => {
                  const a = at(r, d);
                  return (
                    <span key={d} className={cx(classes.absCell, weekdayIndex(d) >= 5 && classes.weekend)}>
                      {a && (
                        <Tooltip
                          label={`${t(`absence.kind.${a.kind}`)} · ${formatRange(a.fromDate, a.toDate, lang)}${a.note ? ` · ${a.note}` : ''}`}
                          withinPortal
                        >
                          <span className={cx(classes.bar, classes[a.kind])} />
                        </Tooltip>
                      )}
                    </span>
                  );
                })}
                <span className={classes.absTotal}>{workingDaysAway(r) || '—'}</span>
              </div>
            ))}
          </div>
        </Paper>
      )}
      <div className={classes.legend}>
        {ABSENCE_KINDS.map((k) => (
          <span key={k}>
            <i className={cx(classes.swatch, classes[k])} />
            {t(`absence.kind.${k}`)}
          </span>
        ))}
      </div>
    </Stack>
  );
}
