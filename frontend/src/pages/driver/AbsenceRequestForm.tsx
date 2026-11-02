import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Group, Select, Stack, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { ABSENCE_KINDS, driverHrKey, hrApi, type AbsenceKind } from '@/api/hr';
import { DateField } from '@/forms/DateField';

/** The driver asks for an absence from the cabinet; it waits as REQUESTED until the office decides. */
export function AbsenceRequestForm({ driverId, onDone }: { driverId: number; onDone: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [kind, setKind] = useState<AbsenceKind>('VACATION');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const send = async () => {
    const local: Record<string, string> = {};
    if (!from) local.fromDate = 'validation.required';
    if (!to) local.toDate = 'validation.required';
    else if (from && to < from) local.toDate = 'validation.invalid_value';
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      await hrApi.createAbsence(driverId, { kind, fromDate: from, toDate: to, paid: null, note: note.trim() || null });
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'absences') });
      notifications.show({ color: 'green', message: t('driver_app.request_sent') });
      onDone();
    } catch (e) {
      if (e instanceof ApiError && e.details) setErrors(e.details);
      else notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };
  const err = (k: string) => (errors[k] ? t(errors[k]) : undefined);

  return (
    <Stack gap="sm">
      <Select
        size="md"
        label={t('absences.kind')}
        data={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`absence.kind.${k}`) }))}
        value={kind}
        onChange={(v) => v && setKind(v as AbsenceKind)}
        allowDeselect={false}
        error={err('kind')}
      />
      <Group grow align="flex-start">
        <DateField size="md" label={t('absences.from')} value={from} onChange={setFrom} error={err('fromDate')} />
        <DateField size="md" label={t('absences.to')} value={to} min={from || undefined} onChange={setTo} error={err('toDate')} />
      </Group>
      <Textarea size="md" label={t('drivers.notes')} autosize minRows={1} maxRows={4} maxLength={500} value={note} onChange={(e) => setNote(e.currentTarget.value)} error={err('note')} />
      <Group grow>
        <Button size="md" variant="default" onClick={onDone} disabled={busy}>
          {t('common.cancel')}
        </Button>
        <Button size="md" onClick={() => void send()} loading={busy}>
          {t('driver_app.request_send')}
        </Button>
      </Group>
    </Stack>
  );
}
