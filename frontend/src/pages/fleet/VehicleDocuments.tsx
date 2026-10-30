import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Alert, Button, Group, NumberInput, Paper, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import {
  fleetApi,
  VEHICLE_DOCUMENT_KINDS,
  VEHICLES_KEY,
  type VehicleDocument,
  type VehicleDocumentInput,
} from '@/api/fleet';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { DEFAULT_REMIND_DAYS, formatDate } from '@/utils/deadline';
import { DeadlineChip } from './VehicleStatusChip';
import classes from './VehicleDetail.module.css';

const DASH = '—';

/* Same rules as VehicleService: validTo not before validFrom, currency (3 letters) required together with cost. */
const schema = z
  .object({
    kind: z.enum(VEHICLE_DOCUMENT_KINDS),
    number: z.string().trim().max(64, 'validation.too_long'),
    validFrom: z.string(),
    validTo: z.string(),
    cost: numberField(0, 999_999_999_999.99),
    currency: z.string().trim(),
    remindDays: numberField(0, 365, { integer: true }),
  })
  .superRefine((v, ctx) => {
    if (v.validFrom && v.validTo && v.validTo < v.validFrom) {
      ctx.addIssue({ code: 'custom', path: ['validTo'], message: 'validation.invalid_value' });
    }
    if (v.currency && !/^[A-Za-z]{3}$/.test(v.currency)) {
      ctx.addIssue({ code: 'custom', path: ['currency'], message: 'validation.invalid_format' });
    } else if (v.cost != null && !v.currency) {
      ctx.addIssue({ code: 'custom', path: ['currency'], message: 'validation.required' });
    }
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;

const FIELDS: readonly FieldPath<FormInput>[] = ['kind', 'number', 'validFrom', 'validTo', 'cost', 'currency', 'remindDays'];

function toFormValues(d: VehicleDocument | null): FormInput {
  return {
    kind: d?.kind ?? 'INSPECTION',
    number: d?.number ?? '',
    validFrom: d?.validFrom ?? '',
    validTo: d?.validTo ?? '',
    cost: d?.cost ?? null,
    currency: d?.currency ?? '',
    remindDays: d?.remindDays ?? DEFAULT_REMIND_DAYS,
  };
}

function toInput(f: FormValues): VehicleDocumentInput {
  return {
    kind: f.kind,
    number: f.number.trim() || null,
    validFrom: f.validFrom || null,
    validTo: f.validTo || null,
    cost: f.cost,
    currency: f.cost == null ? null : f.currency.trim().toUpperCase(),
    remindDays: f.remindDays,
  };
}

/** Add or edit one document; the vehicle list, card and document list are refreshed after saving. */
function DocumentForm({ vehicleId, doc, onDone }: { vehicleId: string; doc: VehicleDocument | null; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const separator = decimalSeparator(i18n.resolvedLanguage ?? 'en');
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormValues>({ resolver: zodResolver(schema), defaultValues: toFormValues(doc) });

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      if (doc) await fleetApi.updateDocument(vehicleId, doc.id, body);
      else await fleetApi.createDocument(vehicleId, body);
      await qc.invalidateQueries({ queryKey: VEHICLES_KEY });
      notifications.show({ color: 'green', message: t('fleet.documents.saved') });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          <Text fw={600}>{doc ? t(`vehicle.document.${doc.kind}`) : t('fleet.documents.new')}</Text>
          <Group grow align="flex-start">
            <Controller
              name="kind"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('fleet.documents.kind')}
                  data={VEHICLE_DOCUMENT_KINDS.map((k) => ({ value: k, label: t(`vehicle.document.${k}`) }))}
                  value={field.value}
                  onChange={(v) => v && field.onChange(v)}
                  allowDeselect={false}
                />
              )}
            />
            <TextInput label={t('fleet.documents.number')} autoComplete="off" error={err('number')} {...register('number')} />
          </Group>
          <Group grow align="flex-start">
            <TextInput type="date" label={t('fleet.documents.valid_from')} error={err('validFrom')} {...register('validFrom')} />
            <TextInput type="date" label={t('fleet.documents.valid_to')} error={err('validTo')} {...register('validTo')} />
          </Group>
          <Group grow align="flex-start">
            <Controller
              name="cost"
              control={control}
              render={({ field }) => (
                <NumberInput
                  label={t('fleet.documents.cost')}
                  value={field.value ?? ''}
                  onChange={(v) => field.onChange(toNumberInputValue(v))}
                  allowNegative={false}
                  decimalScale={2}
                  decimalSeparator={separator}
                  hideControls
                  error={err('cost')}
                />
              )}
            />
            <TextInput
              label={t('fleet.documents.currency')}
              placeholder="PLN"
              maxLength={3}
              autoComplete="off"
              classNames={{ input: classes.mono }}
              error={err('currency')}
              {...register('currency')}
            />
            <Controller
              name="remindDays"
              control={control}
              render={({ field }) => (
                <NumberInput
                  label={t('fleet.documents.remind_days')}
                  value={field.value ?? ''}
                  onChange={(v) => field.onChange(toNumberInputValue(v))}
                  allowNegative={false}
                  allowDecimal={false}
                  hideControls
                  error={err('remindDays')}
                />
              )}
            />
          </Group>
          <Group justify="flex-end" gap="xs">
            <Button size="xs" variant="default" onClick={onDone} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button size="xs" type="submit" loading={isSubmitting}>
              {doc ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}

/** "Documents and deadlines" tab of the vehicle card: list for fleet.vehicle/view, changes for fleet.document/manage. */
export function VehicleDocuments({ vehicleId }: { vehicleId: string }) {
  const { t, i18n } = useTranslation();
  const me = useUser();
  const qc = useQueryClient();
  const lang = i18n.resolvedLanguage ?? 'en';
  const canManage = hasPrivilege(me, PRIV.FLEET_DOCUMENT_MANAGE);
  const q = useQuery({ queryKey: [...VEHICLES_KEY, vehicleId, 'documents'], queryFn: () => fleetApi.documents(vehicleId) });
  const [editing, setEditing] = useState<VehicleDocument | 'new' | null>(null);
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (doc: VehicleDocument) => {
    setBusy(true);
    try {
      await fleetApi.removeDocument(vehicleId, doc.id);
      await qc.invalidateQueries({ queryKey: VEHICLES_KEY });
      notifications.show({ color: 'green', message: t('fleet.documents.deleted') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setConfirmId(null);
      setBusy(false);
    }
  };

  if (q.isPending) return <Text c="dimmed">{t('common.loading')}</Text>;
  if (q.isError && q.error instanceof ApiError && q.error.status === 404) {
    return <EmptyState text={t('common.in_development')} />;
  }
  if (q.isError) return <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />;

  return (
    <Stack gap="sm">
      {canManage && editing === null && (
        <Group justify="flex-end">
          <Button size="xs" variant="default" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            {t('fleet.documents.add')}
          </Button>
        </Group>
      )}
      {editing === 'new' && <DocumentForm vehicleId={vehicleId} doc={null} onDone={() => setEditing(null)} />}
      {q.data.length === 0 && editing === null && <EmptyState text={t('fleet.documents.empty')} />}
      <ul className={classes.docs}>
        {q.data.map((d) =>
          editing !== null && editing !== 'new' && editing.id === d.id ? (
            <li key={d.id}>
              <DocumentForm vehicleId={vehicleId} doc={d} onDone={() => setEditing(null)} />
            </li>
          ) : (
            <li key={d.id} className={classes.doc}>
              <div className={classes.docText}>
                <div className={classes.docTitle}>{t(`vehicle.document.${d.kind}`)}</div>
                <div className={classes.docSub}>
                  {[d.number, d.validTo && t('fleet.deadline.until', { date: formatDate(d.validTo, lang) })]
                    .filter(Boolean)
                    .join(' · ') || DASH}
                </div>
                {confirmId === d.id && (
                  <Alert color="red" variant="light" p="xs" mt={6}>
                    <Group gap="xs" justify="space-between">
                      <Text size="sm">{t('fleet.documents.delete_confirm')}</Text>
                      <Group gap={6}>
                        <Button size="xs" color="red" onClick={() => void remove(d)} loading={busy}>
                          {t('common.confirm')}
                        </Button>
                        <Button size="xs" variant="default" onClick={() => setConfirmId(null)} disabled={busy}>
                          {t('common.cancel')}
                        </Button>
                      </Group>
                    </Group>
                  </Alert>
                )}
              </div>
              {d.validTo && <DeadlineChip validTo={d.validTo} remindDays={d.remindDays} />}
              {canManage && editing === null && (
                <Group gap={2} wrap="nowrap">
                  <Tooltip label={t('common.edit')}>
                    <ActionIcon variant="subtle" color="gray" aria-label={t('common.edit')} onClick={() => setEditing(d)}>
                      <IconPencil size={16} />
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label={t('fleet.delete')}>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label={t('fleet.delete')}
                      onClick={() => setConfirmId(d.id)}
                      disabled={busy}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              )}
            </li>
          ),
        )}
      </ul>
    </Stack>
  );
}
