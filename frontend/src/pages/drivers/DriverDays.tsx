import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Button, Group, Paper, SegmentedControl, Stack, Text, Textarea, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronLeft, IconChevronRight, IconPencil } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { DAY_KINDS, driverHrKey, hrApi, type DayKind, type DriverDay } from '@/api/hr';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { formatMinutes } from '@/pages/tacho/hosFormat';
import { formatMonth, hmm, monthOf, shiftMonth, weekdayIndex, weekdayNames } from './hrFormat';
import classes from './Hr.module.css';

/** Editor of one day: a manual kind and note, or back to what the tachograph and absences say. */
function DayEditor({ driverId, day, month, onDone }: { driverId: string; day: DriverDay; month: string; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const [kind, setKind] = useState<DayKind>(day.kind);
  const [note, setNote] = useState(day.note ?? '');
  const [busy, setBusy] = useState<'save' | 'reset' | null>(null);

  const run = async (what: 'save' | 'reset') => {
    setBusy(what);
    try {
      if (what === 'save') await hrApi.setDay(driverId, day.day, { kind, note: note.trim() || null });
      else await hrApi.resetDay(driverId, day.day);
      await qc.invalidateQueries({ queryKey: [...driverHrKey(driverId, 'days'), month] });
      notifications.show({ color: 'green', message: t(what === 'save' ? 'days.saved' : 'days.reset_done') });
      onDone();
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Paper withBorder p="sm">
      <Stack gap="sm">
        <Text fw={600}>
          {formatDate(day.day, lang)} · {t(`days.source.${day.source}`)}
        </Text>
        <SegmentedControl
          value={kind}
          onChange={(v) => setKind(v as DayKind)}
          data={DAY_KINDS.map((k) => ({ value: k, label: t(`days.kind.${k}`) }))}
          fullWidth
          size="xs"
        />
        <Textarea label={t('drivers.notes')} autosize minRows={1} maxRows={3} maxLength={500} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
        <Group justify="space-between" gap="xs">
          {day.source === 'MANUAL' ? (
            <Tooltip label={t('hint.action.day_reset')}>
              <Button variant="subtle" size="xs" onClick={() => void run('reset')} loading={busy === 'reset'} disabled={busy === 'save'}>
                {t('days.reset')}
              </Button>
            </Tooltip>
          ) : (
            <span />
          )}
          <Group gap="xs">
            <Button variant="default" size="xs" onClick={onDone} disabled={busy !== null}>
              {t('common.cancel')}
            </Button>
            <Button size="xs" onClick={() => void run('save')} loading={busy === 'save'} disabled={busy === 'reset'}>
              {t('common.save')}
            </Button>
          </Group>
        </Group>
      </Stack>
    </Paper>
  );
}

/**
 * Work-day calendar of a driver for one month (driver card tab): what each day was (work from the tachograph, rest,
 * absence, holiday), driving and work time, and manual corrections with driver.driver/manage.
 */
export function DriverDays({ driverId, canManage }: { driverId: string; canManage: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const [month, setMonth] = useState(monthOf());
  const [editing, setEditing] = useState<string | null>(null);
  const q = useQuery({ queryKey: [...driverHrKey(driverId, 'days'), month], queryFn: () => hrApi.month(driverId, month) });
  const go = (n: number) => {
    setMonth((m) => shiftMonth(m, n));
    setEditing(null);
  };
  const editingDay = q.data?.days.find((d) => d.day === editing);
  const today = new Date().toISOString().slice(0, 10);

  return (
    <Stack gap="sm">
      <Group justify="space-between" wrap="nowrap">
        <Group gap={4} wrap="nowrap">
          <Tooltip label={t('days.prev_month')}>
            <ActionIcon variant="default" onClick={() => go(-1)} aria-label={t('days.prev_month')}>
              <IconChevronLeft size={16} />
            </ActionIcon>
          </Tooltip>
          <Text fw={600} px={6} className={classes.monthNav}>
            {formatMonth(month, lang)}
          </Text>
          <Tooltip label={t('days.next_month')}>
            <ActionIcon variant="default" onClick={() => go(1)} aria-label={t('days.next_month')}>
              <IconChevronRight size={16} />
            </ActionIcon>
          </Tooltip>
        </Group>
        <Button variant="subtle" size="xs" onClick={() => setMonth(monthOf())} disabled={month === monthOf()}>
          {t('days.this_month')}
        </Button>
      </Group>

      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <div className={classes.summary}>
            <span>
              {t('days.kind.WORK')}: <b>{q.data.summary.workDays}</b>
            </span>
            <span>
              {t('days.kind.REST')}: <b>{q.data.summary.restDays}</b>
            </span>
            <span>
              {t('days.kind.ABSENCE')}: <b>{q.data.summary.absenceDays}</b>
            </span>
            <span>
              {t('days.kind.HOLIDAY')}: <b>{q.data.summary.holidayDays}</b>
            </span>
            <span>
              {t('hos.activity.DRIVING')}: <b>{formatMinutes(t, q.data.summary.drivingMin)}</b>
            </span>
            <span>
              {t('hos.activity.WORK')}: <b>{formatMinutes(t, q.data.summary.workMin)}</b>
            </span>
          </div>
          <div className={classes.calendar} role="grid" aria-label={formatMonth(month, lang)}>
            {weekdayNames(lang).map((w) => (
              <span key={w} className={classes.weekday} role="columnheader">
                {w}
              </span>
            ))}
            {q.data.days.length > 0 &&
              Array.from({ length: weekdayIndex(q.data.days[0].day) }, (_, i) => <span key={`pad${i}`} />)}
            {q.data.days.map((d) => {
              const label = d.kind === 'ABSENCE' && d.absenceKind ? t(`absence.kind.${d.absenceKind}`) : t(`days.kind.${d.kind}`);
              const title = [
                `${formatDate(d.day, lang)}: ${label}`,
                t(`days.source.${d.source}`),
                d.drivingMin ? `${t('hos.activity.DRIVING')} ${formatMinutes(t, d.drivingMin)}` : null,
                d.workMin ? `${t('hos.activity.WORK')} ${formatMinutes(t, d.workMin)}` : null,
                d.note,
              ]
                .filter(Boolean)
                .join('\n');
              const content = (
                <>
                  <span className={classes.dayNum}>
                    {Number(d.day.slice(8))}
                    {d.source === 'MANUAL' && <IconPencil size={10} aria-hidden />}
                  </span>
                  <span className={classes.dayKind}>{label}</span>
                  {d.drivingMin > 0 && <span className={classes.dayTime}>{hmm(d.drivingMin)}</span>}
                </>
              );
              const cls = cx(
                classes.day,
                classes[d.kind],
                d.source === 'NONE' && classes.noData,
                d.day === today && classes.today,
                d.day === editing && classes.editing,
              );
              return canManage ? (
                <button key={d.day} type="button" className={cls} title={title} onClick={() => setEditing(d.day === editing ? null : d.day)}>
                  {content}
                </button>
              ) : (
                <div key={d.day} className={cls} title={title} role="gridcell">
                  {content}
                </div>
              );
            })}
          </div>
          <Text size="xs" c="dimmed">
            {t(canManage ? 'days.hint_manage' : 'days.hint')}
          </Text>
          {editingDay && <DayEditor key={editingDay.day} driverId={driverId} day={editingDay} month={month} onDone={() => setEditing(null)} />}
        </>
      )}
    </Stack>
  );
}
