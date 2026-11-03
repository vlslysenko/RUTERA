import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Alert, Button, Group, NumberInput, Paper, Select, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronLeft, IconChevronRight, IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { driverHrKey, HR_KEY, hrApi, NEGATIVE_PAYMENT_KINDS, type DriverPayment, type PayRate, type PayRateInput } from '@/api/hr';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { FormDateField } from '@/forms/DateField';
import { PaymentKindChip } from './HrChips';
import { formatAmount, formatMonth, formatPayment } from './hrFormat';
import { PaymentForm } from './PaymentForm';
import classes from './Hr.module.css';

const MONEY = 999_999_999_999.99;

/* Same rules as DriverHrService: validFrom, at least one amount (error on monthlyBase), 2 decimals, per km 4. */
const rateSchema = z
  .object({
    validFrom: z.string().min(1, 'validation.required'),
    monthlyBase: numberField(0, MONEY),
    perDiemHome: numberField(0, MONEY),
    perDiemAbroad: numberField(0, MONEY),
    perKm: numberField(0, 999_999.9999),
    currency: z.string().nullable().refine((v) => !!v, 'validation.required'),
  })
  .superRefine((v, ctx) => {
    if (v.monthlyBase == null && v.perDiemHome == null && v.perDiemAbroad == null && v.perKm == null) {
      ctx.addIssue({ code: 'custom', path: ['monthlyBase'], message: 'validation.required' });
    }
  });

type RateInput = z.input<typeof rateSchema>;
type RateValues = z.output<typeof rateSchema>;
const RATE_FIELDS: readonly FieldPath<RateInput>[] = ['validFrom', 'monthlyBase', 'perDiemHome', 'perDiemAbroad', 'perKm', 'currency'];

function RateForm({ driverId, rate, onDone }: { driverId: string; rate: PayRate | null; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RateInput, unknown, RateValues>({
    resolver: zodResolver(rateSchema),
    defaultValues: rate
      ? { ...rate }
      : { validFrom: '', monthlyBase: null, perDiemHome: null, perDiemAbroad: null, perKm: null, currency: 'PLN' },
  });
  const err = (name: keyof RateInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const body: PayRateInput = { ...f, currency: f.currency ?? '' };
    try {
      if (rate) await hrApi.updatePayRate(driverId, rate.id, body);
      else await hrApi.createPayRate(driverId, body);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'pay-rates') });
      notifications.show({ color: 'green', message: t('pay_rates.saved') });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setError, RATE_FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const money = (name: 'monthlyBase' | 'perDiemHome' | 'perDiemAbroad' | 'perKm', label: string) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <NumberInput
          label={label}
          value={field.value ?? ''}
          onChange={(v) => field.onChange(toNumberInputValue(v))}
          decimalScale={name === 'perKm' ? 4 : 2}
          decimalSeparator={decimalSeparator(lang)}
          allowNegative={false}
          hideControls
          error={err(name)}
        />
      )}
    />
  );

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          <Group grow align="flex-start">
            <FormDateField label={t('pay_rates.valid_from')} error={err('validFrom')} control={control} name="validFrom" />
            <Controller
              name="currency"
              control={control}
              render={({ field }) => (
                <Select label={t('orders.currency')} data={CURRENCIES} value={field.value} onChange={field.onChange} searchable error={err('currency')} />
              )}
            />
          </Group>
          <Group grow align="flex-start">
            {money('monthlyBase', t('pay_rates.monthly_base'))}
            {money('perKm', t('pay_rates.per_km'))}
          </Group>
          <Group grow align="flex-start">
            {money('perDiemHome', t('pay_rates.per_diem_home'))}
            {money('perDiemAbroad', t('pay_rates.per_diem_abroad'))}
          </Group>
          <Text size="xs" c="dimmed">
            {t('pay_rates.hint')}
          </Text>
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onDone} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {rate ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}

function RowActions({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
  const { t } = useTranslation();
  return (
    <Group gap={2} wrap="nowrap" justify="flex-end">
      <Tooltip label={t('common.edit')}>
        <ActionIcon variant="subtle" color="gray" size="sm" onClick={onEdit} aria-label={t('common.edit')}>
          <IconPencil size={15} />
        </ActionIcon>
      </Tooltip>
      <Tooltip label={t('common.delete')}>
        <ActionIcon variant="subtle" color="red" size="sm" onClick={onDelete} aria-label={t('common.delete')}>
          <IconTrash size={15} />
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}

function ConfirmDelete({ text, busy, onConfirm, onCancel }: { text: string; busy: boolean; onConfirm: () => void; onCancel: () => void }) {
  const { t } = useTranslation();
  return (
    <Alert color="red" variant="light" p="xs">
      <Group gap="xs">
        <Text size="sm">{text}</Text>
        <Button size="xs" color="red" onClick={onConfirm} loading={busy}>
          {t('common.confirm')}
        </Button>
        <Button size="xs" variant="default" onClick={onCancel} disabled={busy}>
          {t('common.cancel')}
        </Button>
      </Group>
    </Alert>
  );
}

function PayRates({ driverId, canManage }: { driverId: string; canManage: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const q = useQuery({ queryKey: driverHrKey(driverId, 'pay-rates'), queryFn: () => hrApi.payRates(driverId) });
  const [editing, setEditing] = useState<PayRate | 'new' | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (id: number) => {
    setBusy(true);
    try {
      await hrApi.removePayRate(driverId, id);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'pay-rates') });
      notifications.show({ color: 'green', message: t('pay_rates.deleted') });
      setDeleting(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section>
      <Group justify="space-between" mb={6}>
        <Text size="xs" fw={600} tt="uppercase" c="dimmed">
          {t('pay_rates.title')}
        </Text>
        {canManage && editing === null && (
          <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            {t('pay_rates.add')}
          </Button>
        )}
      </Group>
      {editing === 'new' && <RateForm driverId={driverId} rate={null} onDone={() => setEditing(null)} />}
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        editing === null && <Text size="sm" c="dimmed">{t('pay_rates.empty')}</Text>
      ) : (
        <Stack gap={6}>
          {q.data.map((r, i) =>
            editing !== null && editing !== 'new' && editing.id === r.id ? (
              <RateForm key={r.id} driverId={driverId} rate={r} onDone={() => setEditing(null)} />
            ) : (
              <Paper key={r.id} withBorder p="xs">
                <Group justify="space-between" wrap="nowrap" align="flex-start">
                  <Stack gap={2}>
                    <Text size="sm" fw={600}>
                      {t('pay_rates.from', { date: formatDate(r.validFrom, lang) })}
                      {i === 0 && r.validFrom <= new Date().toISOString().slice(0, 10) ? ` · ${t('pay_rates.current')}` : ''}
                    </Text>
                    <Text size="sm" c="dimmed">
                      {[
                        r.monthlyBase != null && `${t('pay_rates.monthly_base')} ${formatAmount(r.monthlyBase, r.currency, lang)}`,
                        r.perKm != null && `${t('pay_rates.per_km')} ${formatAmount(r.perKm, r.currency, lang, 4)}`,
                        r.perDiemHome != null && `${t('pay_rates.per_diem_home')} ${formatAmount(r.perDiemHome, r.currency, lang)}`,
                        r.perDiemAbroad != null && `${t('pay_rates.per_diem_abroad')} ${formatAmount(r.perDiemAbroad, r.currency, lang)}`,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </Text>
                    {deleting === r.id && (
                      <ConfirmDelete text={t('pay_rates.delete_confirm')} busy={busy} onConfirm={() => void remove(r.id)} onCancel={() => setDeleting(null)} />
                    )}
                  </Stack>
                  {canManage && <RowActions onEdit={() => setEditing(r)} onDelete={() => setDeleting(r.id)} />}
                </Group>
              </Paper>
            ),
          )}
        </Stack>
      )}
    </section>
  );
}

/** Signed sum per currency: advances and deductions subtract (same rule as the firm-wide totals). */
function paymentTotals(items: DriverPayment[]): { currency: string; amount: number }[] {
  const sums = new Map<string, number>();
  for (const p of items) sums.set(p.currency, (sums.get(p.currency) ?? 0) + (NEGATIVE_PAYMENT_KINDS.includes(p.kind) ? -p.amount : p.amount));
  return [...sums].map(([currency, amount]) => ({ currency, amount: Math.round(amount * 100) / 100 }));
}

function Payments({ driverId, canManage }: { driverId: string; canManage: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const [year, setYear] = useState(new Date().getFullYear());
  const q = useQuery({
    queryKey: [...driverHrKey(driverId, 'payments'), year],
    queryFn: () => hrApi.payments(driverId, `${year}-01`, `${year}-12`),
  });
  const [editing, setEditing] = useState<DriverPayment | 'new' | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const items = [...(q.data ?? [])].sort((a, b) => b.period.localeCompare(a.period) || b.id - a.id);

  const remove = async (id: number) => {
    setBusy(true);
    try {
      await hrApi.removePayment(driverId, id);
      void qc.invalidateQueries({ queryKey: driverHrKey(driverId, 'payments') });
      void qc.invalidateQueries({ queryKey: HR_KEY });
      notifications.show({ color: 'green', message: t('payments.deleted') });
      setDeleting(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section>
      <Group justify="space-between" mb={6} wrap="nowrap">
        <Group gap={4} wrap="nowrap">
          <Text size="xs" fw={600} tt="uppercase" c="dimmed" mr={6}>
            {t('payments.title')}
          </Text>
          <ActionIcon variant="subtle" size="sm" onClick={() => setYear((y) => y - 1)} aria-label={t('payments.prev_year')}>
            <IconChevronLeft size={14} />
          </ActionIcon>
          <Text size="sm" fw={600} className={classes.mono}>
            {year}
          </Text>
          <ActionIcon variant="subtle" size="sm" onClick={() => setYear((y) => y + 1)} aria-label={t('payments.next_year')}>
            <IconChevronRight size={14} />
          </ActionIcon>
        </Group>
        {canManage && editing === null && (
          <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => setEditing('new')}>
            {t('payments.add')}
          </Button>
        )}
      </Group>
      {editing === 'new' && <PaymentForm driverId={driverId} payment={null} onDone={() => setEditing(null)} />}
      {editing !== null && editing !== 'new' && <PaymentForm driverId={driverId} payment={editing} onDone={() => setEditing(null)} />}
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState text={t('payments.empty')} />
      ) : (
        <>
          <Stack gap={6}>
            {items.map((p) => (
              <Paper key={p.id} withBorder p="xs">
                <Group justify="space-between" wrap="nowrap" align="flex-start">
                  <Stack gap={4}>
                    <Group gap={6} wrap="wrap">
                      <Text size="sm" fw={500} className={classes.month}>
                        {formatMonth(p.period, lang)}
                      </Text>
                      <PaymentKindChip kind={p.kind} />
                    </Group>
                    <Text size="sm" c="dimmed">
                      {p.paidAt ? t('payments.paid_on', { date: formatDate(p.paidAt, lang) }) : t('payments.unpaid')}
                      {p.note ? ` · ${p.note}` : ''}
                    </Text>
                    {deleting === p.id && (
                      <ConfirmDelete text={t('payments.delete_confirm')} busy={busy} onConfirm={() => void remove(p.id)} onCancel={() => setDeleting(null)} />
                    )}
                  </Stack>
                  <Group gap={6} wrap="nowrap" align="flex-start">
                    <Text size="sm" fw={600} className={cx(classes.num, NEGATIVE_PAYMENT_KINDS.includes(p.kind) && classes.negative)}>
                      {formatPayment(p.amount, p.currency, p.kind, lang)}
                    </Text>
                    {canManage && <RowActions onEdit={() => setEditing(p)} onDelete={() => setDeleting(p.id)} />}
                  </Group>
                </Group>
              </Paper>
            ))}
          </Stack>
          <Group justify="flex-end" gap="md" mt={4}>
            <Text size="sm" c="dimmed">
              {t('payments.total_year', { year })}
            </Text>
            {paymentTotals(items).map((s) => (
              <Text key={s.currency} size="sm" fw={600} className={cx(classes.num, s.amount < 0 && classes.negative)}>
                {formatAmount(s.amount, s.currency, lang)}
              </Text>
            ))}
          </Group>
        </>
      )}
    </section>
  );
}

/** Driver card tab "Pay": pay rates by validity and payments of a year (driver.payment/view, changed with /manage). */
export function DriverPay({ driverId, canManage }: { driverId: string; canManage: boolean }) {
  return (
    <Stack gap="lg">
      <PayRates driverId={driverId} canManage={canManage} />
      <Payments driverId={driverId} canManage={canManage} />
    </Stack>
  );
}
