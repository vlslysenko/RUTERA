import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Alert, Button, Checkbox, Group, Paper, Select, Stack, Text, Textarea, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { ABSENCE_KINDS, driverHrKey, HR_KEY, hrApi, type Absence, type AbsenceInput } from '@/api/hr';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { applyServerErrors } from '@/forms/serverErrors';
import { FormDateField } from '@/forms/DateField';
import { DecisionButtons } from './AbsenceRequests';
import { AbsenceKindChip, AbsenceStatusChip } from './HrChips';
import { formatRange } from './hrFormat';
import classes from './DriversPage.module.css';

/* Same rules as DriverHrService: both dates, to not before from, at most 366 days, note up to 500. */
const schema = z
  .object({
    kind: z.enum(ABSENCE_KINDS),
    fromDate: z.string().min(1, 'validation.required'),
    toDate: z.string().min(1, 'validation.required'),
    paid: z.boolean(),
    note: z.string().max(500, 'validation.too_long'),
  })
  .superRefine((v, ctx) => {
    if (v.fromDate && v.toDate && v.toDate < v.fromDate) ctx.addIssue({ code: 'custom', path: ['toDate'], message: 'validation.invalid_value' });
  });

type FormValues = z.infer<typeof schema>;
const FIELDS: readonly FieldPath<FormValues>[] = ['kind', 'fromDate', 'toDate', 'paid', 'note'];

function AbsenceForm({ driverId, absence, onDone }: { driverId: string; absence: Absence | null; onDone: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const {
    control,
    register,
    handleSubmit,
    setError,
    setValue,
    formState: { errors, isSubmitting, dirtyFields },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: absence
      ? { kind: absence.kind, fromDate: absence.fromDate, toDate: absence.toDate, paid: absence.paid, note: absence.note ?? '' }
      : { kind: 'VACATION', fromDate: '', toDate: '', paid: true, note: '' },
  });
  const err = (name: keyof FormValues) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const body: AbsenceInput = { kind: f.kind, fromDate: f.fromDate, toDate: f.toDate, paid: f.paid, note: f.note.trim() || null };
    try {
      if (absence) await hrApi.updateAbsence(driverId, absence.id, body);
      else await hrApi.createAbsence(driverId, body);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'absences') });
      // The work-day calendar and the firm-wide calendar show absences too.
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'days') });
      void qc.invalidateQueries({ queryKey: HR_KEY });
      notifications.show({ color: 'green', message: t(absence ? 'absences.updated' : 'absences.created') });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          <Controller
            name="kind"
            control={control}
            render={({ field }) => (
              <Select
                label={t('absences.kind')}
                data={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`absence.kind.${k}`) }))}
                value={field.value}
                onChange={(v) => {
                  if (!v) return;
                  field.onChange(v);
                  // Unpaid leave is unpaid unless the user decided otherwise.
                  if (!dirtyFields.paid) setValue('paid', v !== 'UNPAID');
                }}
                allowDeselect={false}
                error={err('kind')}
              />
            )}
          />
          <Group grow align="flex-start">
            <FormDateField label={t('absences.from')} error={err('fromDate')} control={control} name="fromDate" />
            <FormDateField label={t('absences.to')} error={err('toDate')} control={control} name="toDate" />
          </Group>
          <Controller
            name="paid"
            control={control}
            render={({ field }) => (
              <Checkbox label={t('absences.paid')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
            )}
          />
          <Textarea label={t('drivers.notes')} autosize minRows={1} maxRows={4} error={err('note')} {...register('note')} />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onDone} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {absence ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}

/** Absences of one driver (driver card tab): newest first, added and changed with driver.absence/manage; requests from
 * the driver cabinet are approved or rejected here too. */
export function DriverAbsences({ driverId, canManage }: { driverId: string; canManage: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const q = useQuery({ queryKey: driverHrKey(driverId, 'absences'), queryFn: () => hrApi.absences(driverId) });
  const [editing, setEditing] = useState<Absence | 'new' | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (a: Absence) => {
    setBusy(true);
    try {
      await hrApi.removeAbsence(driverId, a.id);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'absences') });
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'days') });
      void qc.invalidateQueries({ queryKey: HR_KEY });
      notifications.show({ color: 'green', message: t('absences.deleted') });
      setDeleting(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  if (q.isPending) return <FullScreenLoader fullScreen={false} />;
  if (q.isError) return <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />;

  return (
    <Stack gap="sm">
      {canManage && editing === null && (
        <Group justify="flex-end">
          <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            {t('absences.add')}
          </Button>
        </Group>
      )}
      {editing === 'new' && <AbsenceForm driverId={driverId} absence={null} onDone={() => setEditing(null)} />}
      {q.data.length === 0 && editing === null ? (
        <EmptyState text={t('absences.empty')} />
      ) : (
        <Stack gap={6}>
          {q.data.map((a) =>
            editing !== null && editing !== 'new' && editing.id === a.id ? (
              <AbsenceForm key={a.id} driverId={driverId} absence={a} onDone={() => setEditing(null)} />
            ) : (
              <Paper key={a.id} withBorder p="xs">
                <Group justify="space-between" wrap="nowrap" align="flex-start">
                  <Stack gap={4}>
                    <Group gap={6} wrap="wrap">
                      <AbsenceKindChip kind={a.kind} />
                      {a.status !== 'APPROVED' && <AbsenceStatusChip status={a.status} />}
                      <Text size="sm" fw={500} className={classes.mono}>
                        {formatRange(a.fromDate, a.toDate, lang)}
                      </Text>
                    </Group>
                    <Text size="sm" c="dimmed">
                      {t('absences.days', { days: a.days, working: a.workingDays })}
                      {' · '}
                      {t(a.paid ? 'absences.paid' : 'absences.unpaid')}
                      {a.status === 'APPROVED' && a.approvedBy ? ` · ${t('absences.approved_by', { name: a.approvedBy.name })}` : ''}
                    </Text>
                    {a.note && (
                      <Text size="sm" className={classes.notes}>
                        {a.note}
                      </Text>
                    )}
                    {a.decisionNote && (
                      <Text size="sm" c="dimmed" className={classes.notes}>
                        {t('absences.decision_note', { note: a.decisionNote })}
                      </Text>
                    )}
                    {canManage && a.status === 'REQUESTED' && <DecisionButtons absence={a} />}
                    {deleting === a.id && (
                      <Alert color="red" variant="light" p="xs">
                        <Group gap="xs">
                          <Text size="sm">{t('absences.delete_confirm')}</Text>
                          <Button size="xs" color="red" onClick={() => void remove(a)} loading={busy}>
                            {t('common.confirm')}
                          </Button>
                          <Button size="xs" variant="default" onClick={() => setDeleting(null)} disabled={busy}>
                            {t('common.cancel')}
                          </Button>
                        </Group>
                      </Alert>
                    )}
                  </Stack>
                  {canManage && (
                    <Group gap={2} wrap="nowrap">
                      <Tooltip label={t('common.edit')}>
                        <ActionIcon variant="subtle" color="gray" onClick={() => setEditing(a)} aria-label={t('common.edit')}>
                          <IconPencil size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <Tooltip label={t('common.delete')}>
                        <ActionIcon variant="subtle" color="red" onClick={() => setDeleting(a.id)} aria-label={t('common.delete')}>
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Group>
                  )}
                </Group>
              </Paper>
            ),
          )}
        </Stack>
      )}
    </Stack>
  );
}
