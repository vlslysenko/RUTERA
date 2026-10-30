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
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { DEFAULT_REMIND_DAYS, formatDate } from '@/utils/deadline';
import { DeadlineChip } from './DeadlineChip';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import classes from './DeadlineDocuments.module.css';

/** A document with a validity period: vehicle_document, driver_document. Cost only where the module has it. */
export interface DeadlineDocument {
  id: number;
  kind: string;
  number: string | null;
  validFrom: string | null;
  validTo: string | null;
  remindDays: number;
  cost?: number | null;
  currency?: string | null;
}

export interface DeadlineDocumentInput {
  kind: string;
  number: string | null;
  validFrom: string | null;
  validTo: string | null;
  remindDays: number | null;
  cost?: number | null;
  currency?: string | null;
}

export interface DeadlineDocumentsApi {
  list: () => Promise<DeadlineDocument[]>;
  create: (body: DeadlineDocumentInput) => Promise<unknown>;
  update: (id: number, body: DeadlineDocumentInput) => Promise<unknown>;
  remove: (id: number) => Promise<void>;
}

export interface DeadlineDocumentsProps {
  queryKey: readonly unknown[];
  /** Prefix of the queries to refresh after a change: the list, the card and the documents themselves. */
  invalidateKey: readonly unknown[];
  api: DeadlineDocumentsApi;
  kinds: readonly string[];
  /** i18n prefix of the kind labels, e.g. vehicle.document -> vehicle.document.INSPECTION */
  kindLabelPrefix: string;
  /** Cost and currency (vehicle documents). */
  withCost?: boolean;
  canManage: boolean;
}

const DASH = '—';

/* Same rules as the backend: validTo not before validFrom, currency (3 letters) required together with cost. */
const schema = z
  .object({
    kind: z.string().min(1, 'validation.required'),
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

function toFormValues(d: DeadlineDocument | null, defaultKind: string): FormInput {
  return {
    kind: d?.kind ?? defaultKind,
    number: d?.number ?? '',
    validFrom: d?.validFrom ?? '',
    validTo: d?.validTo ?? '',
    cost: d?.cost ?? null,
    currency: d?.currency ?? '',
    remindDays: d?.remindDays ?? DEFAULT_REMIND_DAYS,
  };
}

function toInput(f: FormValues, withCost: boolean): DeadlineDocumentInput {
  const input: DeadlineDocumentInput = {
    kind: f.kind,
    number: f.number.trim() || null,
    validFrom: f.validFrom || null,
    validTo: f.validTo || null,
    remindDays: f.remindDays,
  };
  if (withCost) {
    input.cost = f.cost;
    input.currency = f.cost == null ? null : f.currency.trim().toUpperCase();
  }
  return input;
}

interface FormProps extends Pick<DeadlineDocumentsProps, 'api' | 'invalidateKey' | 'kinds' | 'kindLabelPrefix' | 'withCost'> {
  doc: DeadlineDocument | null;
  onDone: () => void;
}

/** Add or edit one document. */
function DocumentForm({ api, invalidateKey, kinds, kindLabelPrefix, withCost = false, doc, onDone }: FormProps) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const separator = decimalSeparator(i18n.resolvedLanguage ?? 'en');
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormValues>({ resolver: zodResolver(schema), defaultValues: toFormValues(doc, kinds[0]) });

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f, withCost);
      if (doc) await api.update(doc.id, body);
      else await api.create(body);
      await qc.invalidateQueries({ queryKey: invalidateKey });
      notifications.show({ color: 'green', message: t('document.saved') });
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
          <Text fw={600}>{doc ? t(`${kindLabelPrefix}.${doc.kind}`) : t('document.new')}</Text>
          <Group grow align="flex-start">
            <Controller
              name="kind"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('document.kind')}
                  data={kinds.map((k) => ({ value: k, label: t(`${kindLabelPrefix}.${k}`) }))}
                  value={field.value}
                  onChange={(v) => v && field.onChange(v)}
                  allowDeselect={false}
                />
              )}
            />
            <TextInput label={t('document.number')} autoComplete="off" error={err('number')} {...register('number')} />
          </Group>
          <Group grow align="flex-start">
            <TextInput type="date" label={t('document.valid_from')} error={err('validFrom')} {...register('validFrom')} />
            <TextInput type="date" label={t('document.valid_to')} error={err('validTo')} {...register('validTo')} />
          </Group>
          <Group grow align="flex-start">
            {withCost && (
              <>
                <Controller
                  name="cost"
                  control={control}
                  render={({ field }) => (
                    <NumberInput
                      label={t('document.cost')}
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
                  label={t('document.currency')}
                  placeholder="PLN"
                  maxLength={3}
                  autoComplete="off"
                  classNames={{ input: classes.mono }}
                  error={err('currency')}
                  {...register('currency')}
                />
              </>
            )}
            <Controller
              name="remindDays"
              control={control}
              render={({ field }) => (
                <NumberInput
                  label={t('document.remind_days')}
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

/**
 * "Documents and deadlines" tab of a card (vehicle, driver): the list with deadline chips, and add / edit / delete
 * for users who may manage them.
 */
export function DeadlineDocuments(props: DeadlineDocumentsProps) {
  const { queryKey, invalidateKey, api, kindLabelPrefix, canManage } = props;
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const lang = i18n.resolvedLanguage ?? 'en';
  const q = useQuery({ queryKey, queryFn: api.list });
  const [editing, setEditing] = useState<DeadlineDocument | 'new' | null>(null);
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (doc: DeadlineDocument) => {
    setBusy(true);
    try {
      await api.remove(doc.id);
      await qc.invalidateQueries({ queryKey: invalidateKey });
      notifications.show({ color: 'green', message: t('document.deleted') });
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

  const form = (doc: DeadlineDocument | null) => <DocumentForm {...props} doc={doc} onDone={() => setEditing(null)} />;

  return (
    <Stack gap="sm">
      {canManage && editing === null && (
        <Group justify="flex-end">
          <Button size="xs" variant="default" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            {t('document.add')}
          </Button>
        </Group>
      )}
      {editing === 'new' && form(null)}
      {q.data.length === 0 && editing === null && <EmptyState text={t('document.empty')} />}
      <ul className={classes.docs}>
        {q.data.map((d) =>
          editing !== null && editing !== 'new' && editing.id === d.id ? (
            <li key={d.id}>{form(d)}</li>
          ) : (
            <li key={d.id} className={classes.doc}>
              <div className={classes.docText}>
                <div className={classes.docTitle}>{t(`${kindLabelPrefix}.${d.kind}`)}</div>
                <div className={classes.docSub}>
                  {[d.number, d.validTo && t('deadline.until', { date: formatDate(d.validTo, lang) })]
                    .filter(Boolean)
                    .join(' · ') || DASH}
                </div>
                {confirmId === d.id && (
                  <Alert color="red" variant="light" p="xs" mt={6}>
                    <Group gap="xs" justify="space-between">
                      <Text size="sm">{t('document.delete_confirm')}</Text>
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
                  <Tooltip label={t('common.delete')}>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label={t('common.delete')}
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
