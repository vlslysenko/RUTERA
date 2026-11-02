import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, NumberInput, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { FUEL_CARDS_KEY, FUEL_PRODUCTS, FUEL_TX_KEY, FUEL_UNITS, fuelApi, type FuelTransaction, type FuelTransactionInput } from '@/api/fuel';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { countryOptions } from '@/utils/countries';
import { idOrNull, useCrewOptions } from '@/pages/trips/crew';
import { useUser } from '@/auth/useAuth';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { fromZonedInput, toZonedInput } from '@/utils/zone';
import { FormDateField } from '@/forms/DateField';

const LIST = '/fuel';
const MONEY = 999_999_999.99;

/* A manual transaction: time, gross and currency required; amounts as on a receipt. */
const schema = z.object({
  at: z.string().min(1, 'validation.required'),
  cardId: z.string().nullable(),
  vehicleId: z.string().nullable(),
  driverId: z.string().nullable(),
  station: z.string().trim().max(200, 'validation.too_long'),
  country: z.string().nullable(),
  product: z.enum(FUEL_PRODUCTS),
  quantity: numberField(0, 999_999),
  unit: z.enum(FUEL_UNITS).nullable(),
  unitPrice: numberField(0, 99_999),
  net: numberField(-MONEY, MONEY),
  vat: numberField(-MONEY, MONEY),
  gross: numberField(-MONEY, MONEY).refine((v) => v != null, 'validation.required'),
  currency: z.string().nullable().refine((v) => !!v, 'validation.required'),
  odometerKm: numberField(0, 9_999_999, { integer: true }),
  notes: z.string().max(500, 'validation.too_long'),
});

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
const FIELDS: readonly FieldPath<FormInput>[] = [
  'at', 'cardId', 'vehicleId', 'driverId', 'station', 'country', 'product', 'quantity', 'unit', 'unitPrice', 'net', 'vat', 'gross', 'currency', 'odometerKm', 'notes',
];

const ids = (v: number | null | undefined) => (v == null ? null : String(v));

function toForm(tx: FuelTransaction, tz?: string): FormInput {
  return {
    at: toZonedInput(tx.at, tz),
    cardId: ids(tx.card?.id),
    vehicleId: ids(tx.vehicle?.id),
    driverId: ids(tx.driver?.id),
    station: tx.station ?? '',
    country: tx.country,
    product: tx.product,
    quantity: tx.quantity,
    unit: tx.unit,
    unitPrice: tx.unitPrice,
    net: tx.net,
    vat: tx.vat,
    gross: tx.gross,
    currency: tx.currency,
    odometerKm: tx.odometerKm,
    notes: tx.notes ?? '',
  };
}

/** Enter (/fuel/new) or correct (/fuel/:id/edit) a transaction typed in by hand, e.g. a cash receipt. */
export function TransactionForm() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const backPath = isNew ? LIST : `${LIST}/${id}`;
  // The time is typed as on the receipt, in the firma zone.
  const tz = useUser().firma?.timezone;
  const q = useQuery({ queryKey: [...FUEL_TX_KEY, id], queryFn: () => fuelApi.transaction(id), enabled: !isNew });
  const cards = useQuery({ queryKey: FUEL_CARDS_KEY, queryFn: fuelApi.cards });

  const {
    control,
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      at: toZonedInput(new Date().toISOString(), tz),
      cardId: null,
      vehicleId: null,
      driverId: null,
      station: '',
      country: 'PL',
      product: 'DIESEL',
      quantity: null,
      unit: 'L',
      unitPrice: null,
      net: null,
      vat: null,
      gross: null,
      currency: 'PLN',
      odometerKm: null,
      notes: '',
    },
    values: q.data ? toForm(q.data, tz) : undefined,
  });
  const crew = useCrewOptions([watch('vehicleId'), watch('driverId')]);
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const body: FuelTransactionInput = {
      at: fromZonedInput(f.at, tz) ?? '',
      cardId: idOrNull(f.cardId),
      vehicleId: idOrNull(f.vehicleId),
      driverId: idOrNull(f.driverId),
      tripId: null,
      station: f.station || null,
      country: f.country,
      product: f.product,
      quantity: f.quantity,
      unit: f.quantity != null ? f.unit : null,
      unitPrice: f.unitPrice,
      net: f.net,
      vat: f.vat,
      gross: f.gross ?? 0,
      currency: f.currency ?? '',
      odometerKm: f.odometerKm,
      notes: f.notes.trim() || null,
    };
    try {
      const saved = isNew ? await fuelApi.createTransaction(body) : await fuelApi.updateTransaction(id, body);
      qc.setQueryData([...FUEL_TX_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...FUEL_TX_KEY, 'list'] });
      notifications.show({ color: 'green', message: t(isNew ? 'fuel.created' : 'fuel.updated') });
      await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const num = (name: 'quantity' | 'unitPrice' | 'net' | 'vat' | 'gross' | 'odometerKm', label: string, scale = 2) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <NumberInput
          label={label}
          value={field.value ?? ''}
          onChange={(v) => field.onChange(toNumberInputValue(v))}
          decimalScale={scale}
          allowDecimal={scale > 0}
          decimalSeparator={decimalSeparator(lang)}
          hideControls
          error={err(name)}
        />
      )}
    />
  );
  const select = (name: 'cardId' | 'vehicleId' | 'driverId', label: string, data: { value: string; label: string }[]) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => <Select label={label} data={data} value={field.value} onChange={field.onChange} clearable searchable error={err(name)} />}
    />
  );

  return (
    <DetailDrawer mode="drawer" listPath={backPath} title={isNew ? t('fuel.new') : t('common.edit')} loading={!isNew && q.isPending}>
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            <Group grow align="flex-start">
              <FormDateField withTime label={t('fuel.at')} error={err('at')} control={control} name="at" />
              {select('cardId', t('fuel.card'), (cards.data ?? []).map((c) => ({ value: String(c.id), label: `${c.provider} ${c.cardNumberMasked}` })))}
            </Group>
            <Group grow align="flex-start">
              {select('vehicleId', t('trips.vehicle'), crew.vehicles)}
              {select('driverId', t('trips.driver'), crew.drivers)}
            </Group>
            <Group grow align="flex-start">
              <TextInput label={t('fuel.station')} error={err('station')} {...register('station')} />
              <Controller
                name="country"
                control={control}
                render={({ field }) => (
                  <Select label={t('trips.country')} data={countryOptions(lang, field.value)} value={field.value} onChange={field.onChange} searchable clearable error={err('country')} />
                )}
              />
            </Group>
            <Group grow align="flex-start">
              <Controller
                name="product"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('fuel.product')}
                    data={FUEL_PRODUCTS.map((p) => ({ value: p, label: t(`fuel.product.${p}`) }))}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    allowDeselect={false}
                    error={err('product')}
                  />
                )}
              />
              {num('quantity', t('fuel.quantity'))}
              <Controller
                name="unit"
                control={control}
                render={({ field }) => (
                  <Select label={t('invoices.unit')} data={FUEL_UNITS.map((u) => ({ value: u, label: t(`fuel.unit.${u}`) }))} value={field.value} onChange={field.onChange} error={err('unit')} />
                )}
              />
            </Group>
            <Group grow align="flex-start">
              {num('unitPrice', t('invoices.unit_price'), 4)}
              {num('net', t('invoices.net'))}
              {num('vat', t('invoices.vat'))}
            </Group>
            <Group grow align="flex-start">
              {num('gross', t('invoices.gross'))}
              <Controller
                name="currency"
                control={control}
                render={({ field }) => <Select label={t('orders.currency')} data={CURRENCIES} value={field.value} onChange={field.onChange} searchable error={err('currency')} />}
              />
              {num('odometerKm', t('fuel.odometer'), 0)}
            </Group>
            <Textarea label={t('drivers.notes')} autosize minRows={1} maxRows={4} error={err('notes')} {...register('notes')} />
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
