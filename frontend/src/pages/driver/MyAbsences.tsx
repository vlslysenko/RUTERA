import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Group, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { driverHrKey, hrApi, type Absence } from '@/api/hr';
import { useOutbox, type AbsenceItem } from '@/app/outbox';
import { useUser } from '@/auth/useAuth';
import { AbsenceKindChip, AbsenceStatusChip } from '@/pages/drivers/HrChips';
import { formatRange } from '@/pages/drivers/hrFormat';
import { AbsenceRequestForm } from './AbsenceRequestForm';
import { Card, PartError, Waiting } from './DriverParts';
import { OutboxActions, OutboxStateChip } from './OutboxBar';
import classes from './DriverApp.module.css';

/** Own absences in the cabinet: approved vacation days of the year, coming absences and requests, a new request. */
export function MyAbsences({ driverId, today }: { driverId: number; today: string }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const year = today.slice(0, 4);
  const until = `${Number(year) + 1}-12-31`;
  const q = useQuery({
    queryKey: [...driverHrKey(driverId, 'absences'), `${year}-01-01`, until],
    queryFn: () => hrApi.absences(driverId, `${year}-01-01`, until),
    retry: false,
  });
  // Requests made without a connection, waiting on the phone.
  const waiting = useOutbox(useUser().id).filter((i): i is AbsenceItem => i.type === 'absence' && i.driverId === driverId);
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);

  const withdraw = async (a: Absence) => {
    setBusy(a.id);
    try {
      await hrApi.removeAbsence(driverId, a.id);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'absences') });
      notifications.show({ color: 'green', message: t('driver_app.request_withdrawn') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  const all = q.data ?? [];
  const vacationDays = all.filter((a) => a.kind === 'VACATION' && a.status === 'APPROVED' && a.fromDate.startsWith(year)).reduce((n, a) => n + a.workingDays, 0);
  const coming = all.filter((a) => a.toDate >= today).sort((a, b) => a.fromDate.localeCompare(b.fromDate));

  return (
    <Card
      title={t('driver_app.absences')}
      right={
        !asking && (
          <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setAsking(true)}>
            {t('driver_app.request_absence')}
          </Button>
        )
      }
    >
      {asking ? (
        <AbsenceRequestForm driverId={driverId} onDone={() => setAsking(false)} />
      ) : q.isPending ? (
        <Waiting query={q} />
      ) : q.isError && q.data === undefined ? (
        <PartError error={q.error} />
      ) : (
        <>
          <Text size="sm" mb={4}>
            {t('driver_app.vacation_used', { n: vacationDays, year })}
          </Text>
          {waiting.map((item) => (
            <div key={item.id} className={classes.row} style={{ flexWrap: 'wrap', paddingBlock: 6 }}>
              <Stack gap={2}>
                <Text size="sm">{formatRange(item.body.fromDate, item.body.toDate, lang)}</Text>
                {item.state === 'failed' && item.error && (
                  <Text size="xs" c="red">
                    {t(item.error)}
                  </Text>
                )}
              </Stack>
              <Group gap={6}>
                <AbsenceKindChip kind={item.body.kind} />
                <OutboxStateChip item={item} />
                <OutboxActions item={item} />
              </Group>
            </div>
          ))}
          {coming.length === 0 && waiting.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('driver_app.no_absences')}
            </Text>
          ) : (
            coming.map((a) => (
              <div key={a.id} className={classes.row} style={{ flexWrap: 'wrap', paddingBlock: 6 }}>
                <Stack gap={2}>
                  <Text size="sm">{formatRange(a.fromDate, a.toDate, lang)}</Text>
                  {a.decisionNote && (
                    <Text size="xs" c="dimmed">
                      {t('absences.decision_note', { note: a.decisionNote })}
                    </Text>
                  )}
                </Stack>
                <Group gap={6}>
                  <AbsenceKindChip kind={a.kind} />
                  {a.status !== 'APPROVED' && <AbsenceStatusChip status={a.status} />}
                  {a.status === 'REQUESTED' && (
                    <Tooltip label={t('hint.action.absence_withdraw')}>
                      <Button size="xs" variant="subtle" color="red" onClick={() => void withdraw(a)} loading={busy === a.id}>
                        {t('driver_app.request_withdraw')}
                      </Button>
                    </Tooltip>
                  )}
                </Group>
              </div>
            ))
          )}
        </>
      )}
    </Card>
  );
}
