import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Anchor, Badge, Button, Group, Paper, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { driverHrKey, HR_KEY, hrApi, type Absence } from '@/api/hr';
import { AbsenceKindChip } from './HrChips';
import { formatRange } from './hrFormat';
import classes from './DriversPage.module.css';

const REQUESTS_KEY = [...HR_KEY, 'absences', 'requests'] as const;

/** Approve or reject a driver's request; rejecting asks for an optional note the driver sees in the cabinet. */
export function DecisionButtons({ absence }: { absence: Absence }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<'APPROVED' | 'REJECTED' | null>(null);

  const decide = async (status: 'APPROVED' | 'REJECTED') => {
    setBusy(status);
    try {
      await hrApi.decideAbsence(absence.driver.id, absence.id, { status, note: status === 'REJECTED' ? note.trim() || null : null });
      void qc.invalidateQueries({ queryKey: HR_KEY });
      void qc.invalidateQueries({ queryKey: driverHrKey(absence.driver.id, 'absences') });
      void qc.invalidateQueries({ queryKey: driverHrKey(absence.driver.id, 'days') });
      notifications.show({ color: 'green', message: t(status === 'APPROVED' ? 'absences.request_approved' : 'absences.request_rejected', { name: absence.driver.name }) });
      setRejecting(false);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  return rejecting ? (
    <Group gap="xs" wrap="wrap" align="flex-end">
      <TextInput size="xs" placeholder={t('absences.reject_note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} maxLength={500} style={{ flex: '1 1 200px' }} />
      <Button size="xs" color="red" onClick={() => void decide('REJECTED')} loading={busy === 'REJECTED'}>
        {t('absences.reject')}
      </Button>
      <Button size="xs" variant="default" onClick={() => setRejecting(false)} disabled={busy !== null}>
        {t('common.cancel')}
      </Button>
    </Group>
  ) : (
    <Group gap="xs">
      <Button size="xs" color="green" leftSection={<IconCheck size={14} />} onClick={() => void decide('APPROVED')} loading={busy === 'APPROVED'}>
        {t('absences.approve')}
      </Button>
      <Button size="xs" variant="default" color="red" leftSection={<IconX size={14} />} onClick={() => setRejecting(true)} disabled={busy !== null}>
        {t('absences.reject')}
      </Button>
    </Group>
  );
}

/** Open requests of all drivers (absences screen of the office): who asks for what, approve or reject in place. */
export function AbsenceRequests() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const q = useQuery({ queryKey: REQUESTS_KEY, queryFn: hrApi.requests });
  const items = q.data ?? [];
  if (q.isError) {
    return (
      <Text size="sm" c="red">
        {t('absences.requests')}: {t(errorCode(q.error))}
      </Text>
    );
  }
  if (items.length === 0) return null;

  return (
    <Paper withBorder p="sm">
      <Stack gap="xs">
        <Group gap="xs">
          <Text size="sm" fw={600}>
            {t('absences.requests')}
          </Text>
          <Badge color="orange" variant="filled" size="sm">
            {items.length}
          </Badge>
        </Group>
        {items.map((a) => (
          <Group key={a.id} justify="space-between" wrap="wrap" gap="xs">
            <Stack gap={2}>
              <Group gap={6} wrap="wrap">
                <Anchor component={Link} to={`/drivers/${a.driver.id}?tab=absences`} size="sm" fw={500}>
                  {a.driver.name}
                </Anchor>
                <AbsenceKindChip kind={a.kind} />
                <Text size="sm" className={classes.mono}>
                  {formatRange(a.fromDate, a.toDate, lang)}
                </Text>
                <Text size="sm" c="dimmed">
                  {t('absences.days', { days: a.days, working: a.workingDays })}
                </Text>
              </Group>
              {a.note && (
                <Text size="xs" c="dimmed">
                  {a.note}
                </Text>
              )}
            </Stack>
            <DecisionButtons absence={a} />
          </Group>
        ))}
      </Stack>
    </Paper>
  );
}
