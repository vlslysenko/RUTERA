import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, NumberInput, Paper, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { HR_KEY, hrApi, PAYMENT_KINDS, type DriverPayment, type DriverPaymentInput } from '@/api/hr';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { monthOf } from './hrFormat';

/* Same rules as DriverHrService: period YYYY-MM, amount above zero with 2 decimals, ISO currency, note up to 500. */
const schema = z.object({
  driverId: z.string().nullable().refine((v) => !!v, 'validation.required'),
  period: z.string().regex(/^\d{4}-\d{2}$/, 'validation.required'),
  kind: z.enum(PAYMENT_KINDS),
  amount: numberField(0.01, 999_999_999_999.99).refine((v) => v != null, 'validation.required'),
  currency: z.string().nullable().refine((v) => !!v, 'validation.required'),
  paidAt: z.string(),
  note: z.string().max(500, 'validation.too_long'),
});

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
const FIELDS: readonly FieldPath<FormInput>[] = ['period', 'kind', 'amount', 'currency', 'paidAt', 'note'];

/**
 * Add or change a payment to a driver. In the driver card the driver is fixed; on the firm-wide payments page it is
 * chosen here (`driverId` absent).
 */
export function PaymentForm({
  driverId,
  payment,
  defaultPeriod,
  defaultCurrency,
  onDone,
}: {
  driverId?: string;
  payment: DriverPayment | null;
  defaultPeriod?: string;
  defaultCurrency?: string;
  onDone: () => void;
}) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list, enabled: !driverId && !payment });
  const {
    control,
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: payment
      ? {
          driverId: String(payment.driver.id),
          period: payment.period,
          kind: payment.kind,
          amount: payment.amount,
          currency: payment.currency,
          paidAt: payment.paidAt ?? '',
          note: payment.note ?? '',
        }
      : {
          driverId: driverId ?? null,
          period: defaultPeriod ?? monthOf(),
          kind: 'SALARY',
          amount: null,
          currency: defaultCurrency ?? 'PLN',
          paidAt: '',
          note: '',
        },
  });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };
  const currency = watch('currency');
  const currencies = currency && !CURRENCIES.includes(currency) ? [...CURRENCIES, currency] : CURRENCIES;

  const onSubmit = handleSubmit(async (f) => {
    const target = f.driverId ?? '';
    const body: DriverPaymentInput = {
      period: f.period,
      kind: f.kind,
      amount: f.amount ?? 0,
      currency: f.currency ?? '',
      paidAt: f.paidAt || null,
      note: f.note.trim() || null,
    };
    try {
      if (payment) await hrApi.updatePayment(target, payment.id, body);
      else await hrApi.createPayment(target, body);
      void qc.invalidateQueries({ queryKey: [...DRIVERS_KEY, target, 'payments'] });
      void qc.invalidateQueries({ queryKey: HR_KEY });
      notifications.show({ color: 'green', message: t(payment ? 'payments.updated' : 'payments.created') });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          {!driverId && !payment && (
            <Controller
              name="driverId"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('trips.driver')}
                  data={(drivers.data ?? []).filter((d) => d.status === 'ACTIVE').map((d) => ({ value: String(d.id), label: d.name }))}
                  value={field.value}
                  onChange={field.onChange}
                  searchable
                  error={err('driverId') ?? (drivers.isError ? t(errorCode(drivers.error)) : undefined)}
                />
              )}
            />
          )}
          <Group grow align="flex-start">
            <TextInput type="month" label={t('payments.period')} error={err('period')} {...register('period')} />
            <Controller
              name="kind"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('payments.kind')}
                  data={PAYMENT_KINDS.map((k) => ({ value: k, label: t(`payment.kind.${k}`) }))}
                  value={field.value}
                  onChange={(v) => v && field.onChange(v)}
                  allowDeselect={false}
                  error={err('kind')}
                />
              )}
            />
          </Group>
          <Group grow align="flex-start">
            <Controller
              name="amount"
              control={control}
              render={({ field }) => (
                <NumberInput
                  label={t('payments.amount')}
                  value={field.value ?? ''}
                  onChange={(v) => field.onChange(toNumberInputValue(v))}
                  decimalScale={2}
                  decimalSeparator={decimalSeparator(lang)}
                  allowNegative={false}
                  hideControls
                  error={err('amount')}
                />
              )}
            />
            <Controller
              name="currency"
              control={control}
              render={({ field }) => (
                <Select label={t('orders.currency')} data={currencies} value={field.value} onChange={field.onChange} searchable error={err('currency')} />
              )}
            />
          </Group>
          <TextInput type="date" label={t('payments.paid_at')} description={t('payments.paid_at_hint')} error={err('paidAt')} {...register('paidAt')} />
          <Textarea label={t('drivers.notes')} autosize minRows={1} maxRows={4} error={err('note')} {...register('note')} />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onDone} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {payment ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}
