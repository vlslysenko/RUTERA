import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Checkbox, Group, NumberInput, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { CONTRACTORS_KEY, contractorsApi, ORDERS_KEY, ordersApi, type Order, type OrderInput } from '@/api/trips';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { countryOptions } from '@/utils/countries';
import { FormDateField } from '@/forms/DateField';
import { CURRENCIES } from './tripFormat';
import classes from './Trips.module.css';

const LIST = '/trips/orders';
const NUMBER_RE = /^[\p{L}\p{N}][\p{L}\p{N} ./_-]*$/u;

/* Same rules as OrderDto.Input and OrderService: places and countries required, currency with a price, unloading not before loading. */
const schema = z
  .object({
    number: z
      .string()
      .trim()
      .max(32, 'validation.too_long')
      .refine((v) => v === '' || NUMBER_RE.test(v), 'validation.invalid_format'),
    orderDate: z.string().min(1, 'validation.required'),
    customerId: z.string().nullable().refine((v) => !!v, 'validation.required'),
    customerRef: z.string().trim().max(64, 'validation.too_long'),
    price: numberField(0, 999_999_999_999.99),
    currency: z.string().nullable(),
    cargo: z.string().trim().max(500, 'validation.too_long'),
    weightKg: numberField(0, 100_000, { integer: true }),
    loadingPlace: z.string().trim().min(1, 'validation.required').max(200, 'validation.too_long'),
    loadingCountry: z.string().nullable().refine((v) => !!v, 'validation.required'),
    loadingDate: z.string(),
    unloadingPlace: z.string().trim().min(1, 'validation.required').max(200, 'validation.too_long'),
    unloadingCountry: z.string().nullable().refine((v) => !!v, 'validation.required'),
    unloadingDate: z.string(),
    cancelled: z.boolean(),
    notes: z.string().max(2000, 'validation.too_long'),
  })
  .superRefine((v, ctx) => {
    if (v.price != null && !v.currency) ctx.addIssue({ code: 'custom', path: ['currency'], message: 'validation.required' });
    if (v.loadingDate && v.unloadingDate && v.unloadingDate < v.loadingDate) {
      ctx.addIssue({ code: 'custom', path: ['unloadingDate'], message: 'validation.invalid_value' });
    }
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;

const FIELDS: readonly FieldPath<FormInput>[] = [
  'number', 'orderDate', 'customerId', 'customerRef', 'price', 'currency', 'cargo', 'weightKg', 'loadingPlace',
  'loadingCountry', 'loadingDate', 'unloadingPlace', 'unloadingCountry', 'unloadingDate', 'cancelled', 'notes',
];

const today = () => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

const empty = (): FormInput => ({
  number: '',
  orderDate: today(),
  customerId: null,
  customerRef: '',
  price: null,
  currency: 'EUR',
  cargo: '',
  weightKg: null,
  loadingPlace: '',
  loadingCountry: 'PL',
  loadingDate: '',
  unloadingPlace: '',
  unloadingCountry: null,
  unloadingDate: '',
  cancelled: false,
  notes: '',
});

function toFormValues(o: Order): FormInput {
  return {
    number: o.number,
    orderDate: o.orderDate,
    customerId: String(o.customer.id),
    customerRef: o.customerRef ?? '',
    price: o.price,
    currency: o.currency ?? 'EUR',
    cargo: o.cargo ?? '',
    weightKg: o.weightKg,
    loadingPlace: o.loading.place,
    loadingCountry: o.loading.country,
    loadingDate: o.loading.date ?? '',
    unloadingPlace: o.unloading.place,
    unloadingCountry: o.unloading.country,
    unloadingDate: o.unloading.date ?? '',
    cancelled: o.status === 'CANCELLED',
    notes: o.notes ?? '',
  };
}

const blank = (s: string) => s.trim() || null;

function toInput(f: FormValues): OrderInput {
  return {
    number: blank(f.number),
    orderDate: f.orderDate,
    customerId: Number(f.customerId),
    customerRef: blank(f.customerRef),
    price: f.price,
    currency: f.price == null ? null : f.currency,
    cargo: blank(f.cargo),
    weightKg: f.weightKg,
    loadingPlace: f.loadingPlace.trim(),
    loadingCountry: f.loadingCountry ?? '',
    loadingDate: f.loadingDate || null,
    unloadingPlace: f.unloadingPlace.trim(),
    unloadingCountry: f.unloadingCountry ?? '',
    unloadingDate: f.unloadingDate || null,
    cancelled: f.cancelled,
    notes: blank(f.notes),
  };
}

/** Create (/trips/orders/new) or edit (/trips/orders/:id/edit) an order in the right panel. */
export function OrderForm() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const backPath = isNew ? LIST : `${LIST}/${id}`;

  const q = useQuery({ queryKey: [...ORDERS_KEY, id], queryFn: () => ordersApi.get(id), enabled: !isNew });
  const current = q.data;
  const customers = useQuery({ queryKey: [...CONTRACTORS_KEY, 'list', '', 'CUSTOMER'], queryFn: () => contractorsApi.list(undefined, 'CUSTOMER') });

  const {
    control,
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: empty(),
    values: current ? toFormValues(current) : undefined,
  });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const customerOptions = (customers.data ?? []).map((c) => ({ value: String(c.id), label: c.name }));
  // A customer that is no longer marked as one still shows on its old orders.
  if (current && !customerOptions.some((c) => c.value === String(current.customer.id))) {
    customerOptions.push({ value: String(current.customer.id), label: current.customer.name });
  }
  const currency = watch('currency');
  const currencyOptions = currency && !CURRENCIES.includes(currency) ? [...CURRENCIES, currency] : CURRENCIES;

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      const saved = isNew ? await ordersApi.create(body) : await ordersApi.update(id, body);
      qc.setQueryData([...ORDERS_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...ORDERS_KEY, 'list'] });
      notifications.show({ color: 'green', message: t(isNew ? 'orders.created' : 'orders.updated', { number: saved.number }) });
      await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const country = (name: 'loadingCountry' | 'unloadingCountry') => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <Select
          label={t('trips.country')}
          data={countryOptions(lang, field.value)}
          value={field.value}
          onChange={field.onChange}
          searchable
          error={err(name)}
        />
      )}
    />
  );

  return (
    <DetailDrawer
      mode="drawer"
      listPath={backPath}
      title={isNew ? t('orders.new') : (current?.number ?? '…')}
      subtitle={!isNew && t('common.edit')}
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            <Group grow align="flex-start">
              <TextInput
                label={t('orders.number')}
                placeholder={t('orders.number_auto')}
                classNames={{ input: classes.mono }}
                error={err('number')}
                {...register('number')}
              />
              <FormDateField label={t('orders.date')} error={err('orderDate')} control={control} name="orderDate" />
            </Group>
            <Group grow align="flex-start">
              <Controller
                name="customerId"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('orders.customer')}
                    data={customerOptions}
                    value={field.value}
                    onChange={field.onChange}
                    searchable
                    autoFocus={isNew}
                    nothingFoundMessage={t('fleet.nothing_found')}
                    error={err('customerId') ?? (customers.isError ? t(errorCode(customers.error)) : undefined)}
                  />
                )}
              />
              <TextInput label={t('orders.customer_ref')} classNames={{ input: classes.mono }} error={err('customerRef')} {...register('customerRef')} />
            </Group>

            <h3 className={classes.sectionTitle}>{t('orders.section.route')}</h3>
            <Group grow align="flex-start">
              <TextInput label={t('orders.loading')} error={err('loadingPlace')} {...register('loadingPlace')} />
              {country('loadingCountry')}
            </Group>
            <FormDateField label={t('orders.loading_date')} error={err('loadingDate')} control={control} name="loadingDate" />
            <Group grow align="flex-start">
              <TextInput label={t('orders.unloading')} error={err('unloadingPlace')} {...register('unloadingPlace')} />
              {country('unloadingCountry')}
            </Group>
            <FormDateField label={t('orders.unloading_date')} error={err('unloadingDate')} control={control} name="unloadingDate" />

            <h3 className={classes.sectionTitle}>{t('orders.section.cargo')}</h3>
            <Group grow align="flex-start">
              <Controller
                name="price"
                control={control}
                render={({ field }) => (
                  <NumberInput
                    label={t('orders.price')}
                    value={field.value ?? ''}
                    onChange={(v) => field.onChange(toNumberInputValue(v))}
                    decimalScale={2}
                    decimalSeparator={decimalSeparator(lang)}
                    allowNegative={false}
                    hideControls
                    error={err('price')}
                  />
                )}
              />
              <Controller
                name="currency"
                control={control}
                render={({ field }) => (
                  <Select label={t('orders.currency')} data={currencyOptions} value={field.value} onChange={field.onChange} searchable error={err('currency')} />
                )}
              />
            </Group>
            <Group grow align="flex-start">
              <TextInput label={t('orders.cargo')} error={err('cargo')} {...register('cargo')} />
              <Controller
                name="weightKg"
                control={control}
                render={({ field }) => (
                  <NumberInput
                    label={t('orders.weight_kg')}
                    value={field.value ?? ''}
                    onChange={(v) => field.onChange(toNumberInputValue(v))}
                    allowNegative={false}
                    allowDecimal={false}
                    thousandSeparator=" "
                    hideControls
                    error={err('weightKg')}
                  />
                )}
              />
            </Group>
            <Textarea label={t('drivers.notes')} autosize minRows={2} maxRows={6} error={err('notes')} {...register('notes')} />
            {!isNew && current && current.status !== 'INVOICED' && (
              <Controller
                name="cancelled"
                control={control}
                render={({ field }) => (
                  <Checkbox
                    label={t('orders.cancelled')}
                    description={current.trip ? t('orders.cancel_hint') : undefined}
                    checked={field.value}
                    onChange={(e) => field.onChange(e.currentTarget.checked)}
                  />
                )}
              />
            )}
            <Group justify="flex-end" gap="xs">
              <Button variant="default" onClick={() => void navigate(backPath)} disabled={isSubmitting}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={isSubmitting} disabled={!isNew && !isDirty}>
                {isNew ? t('common.create') : t('common.save')}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </DetailDrawer>
  );
}
