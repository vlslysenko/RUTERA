import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, useWatch, type Control, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, NumberInput, Select, Stack, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import {
  EURO_CLASSES,
  fleetApi,
  FUEL_TYPES,
  isTrailer,
  TACHO_GENS,
  VEHICLE_KINDS,
  VEHICLE_STATUSES,
  VEHICLES_KEY,
  type Vehicle,
  type VehicleInput,
} from '@/api/fleet';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { Plate } from '@/components/Plate';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import classes from './VehicleDetail.module.css';

/* Same rules as VehicleService on the backend. Plate: letters, digits, spaces and hyphens. VIN: up to 17 letters
 * and digits after removing spaces (older trailers carry shorter serial numbers). */
const PLATE_RE = /^[\p{L}\p{N}][\p{L}\p{N} -]*$/u;
const VIN_RE = /^[A-Z0-9]{1,17}$/;
const vinOf = (v: string) => v.replace(/\s+/g, '').toUpperCase();

const text = (max: number) => z.string().trim().max(max, 'validation.too_long');

const schema = z.object({
  kind: z.enum(VEHICLE_KINDS),
  status: z.enum(VEHICLE_STATUSES),
  plate: z
    .string()
    .trim()
    .min(1, 'validation.required')
    .max(20, 'validation.too_long')
    .regex(PLATE_RE, 'validation.invalid_format'),
  vin: z.string().refine((v) => vinOf(v) === '' || VIN_RE.test(vinOf(v)), 'validation.invalid_format'),
  make: text(60),
  model: text(60),
  axles: numberField(1, 10, { integer: true }),
  grossWeightKg: numberField(1, 100_000, { integer: true }),
  heightM: numberField(0.5, 6),
  euroClass: z.enum(EURO_CLASSES).nullable(),
  fuelType: z.enum(FUEL_TYPES).nullable(),
  avgL100km: numberField(0, 999.99),
  tachoGen: z.enum(TACHO_GENS),
  odometerKm: numberField(0, 99_999_999, { integer: true }),
  defaultTrailerId: z.string().nullable(),
});

/** What the inputs hold (numbers may be unfinished strings) and what validation produces. */
type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;

const FIELDS: readonly FieldPath<FormInput>[] = [
  'kind',
  'status',
  'plate',
  'vin',
  'make',
  'model',
  'axles',
  'grossWeightKg',
  'heightM',
  'euroClass',
  'fuelType',
  'avgL100km',
  'tachoGen',
  'odometerKm',
  'defaultTrailerId',
];

const EMPTY: FormInput = {
  kind: 'TRACTOR',
  status: 'ACTIVE',
  plate: '',
  vin: '',
  make: '',
  model: '',
  axles: null,
  grossWeightKg: null,
  heightM: null,
  euroClass: null,
  fuelType: 'DIESEL',
  avgL100km: null,
  tachoGen: 'GEN2',
  odometerKm: null,
  defaultTrailerId: null,
};

function toFormValues(v: Vehicle): FormInput {
  return {
    kind: v.kind,
    status: v.status,
    plate: v.plate,
    vin: v.vin ?? '',
    make: v.make ?? '',
    model: v.model ?? '',
    axles: v.axles,
    grossWeightKg: v.grossWeightKg,
    heightM: v.heightMm == null ? null : v.heightMm / 1000,
    euroClass: v.euroClass,
    fuelType: v.fuelType,
    avgL100km: v.avgL100km,
    tachoGen: v.tachoGen,
    odometerKm: v.odometerKm,
    defaultTrailerId: v.defaultTrailer ? String(v.defaultTrailer.id) : null,
  };
}

const blankToNull = (s: string) => (s.trim() === '' ? null : s.trim());

/** Trailers have no engine fields; they are sent as null so a kind change does not leave stale values behind. */
function toInput(f: FormValues, current: Vehicle | undefined): VehicleInput {
  const trailer = isTrailer(f.kind);
  return {
    kind: f.kind,
    status: f.status,
    plate: f.plate.trim().toUpperCase().replace(/\s+/g, ' '),
    vin: vinOf(f.vin) || null,
    make: blankToNull(f.make),
    model: blankToNull(f.model),
    axles: f.axles,
    grossWeightKg: f.grossWeightKg,
    heightMm: f.heightM == null ? null : Math.round(f.heightM * 1000),
    euroClass: trailer ? null : f.euroClass,
    fuelType: trailer ? null : f.fuelType,
    avgL100km: trailer ? null : f.avgL100km,
    tachoGen: trailer ? 'NONE' : f.tachoGen,
    odometerKm: trailer ? null : f.odometerKm,
    // Drivers are assigned in the drivers module (not there yet, the backend accepts only null); keep the current one.
    defaultDriverId: trailer ? null : (current?.defaultDriver?.id ?? null),
    defaultTrailerId: trailer || !f.defaultTrailerId ? null : Number(f.defaultTrailerId),
  };
}

type NumberField = 'axles' | 'grossWeightKg' | 'heightM' | 'avgL100km' | 'odometerKm';

function NumberField({
  control,
  name,
  label,
  decimals = 0,
  separator,
}: {
  control: Control<FormInput, unknown, FormValues>;
  name: NumberField;
  label: string;
  decimals?: number;
  separator: string;
}) {
  const { t } = useTranslation();
  return (
    <Controller
      name={name}
      control={control}
      render={({ field, fieldState }) => (
        <NumberInput
          label={label}
          value={field.value ?? ''}
          onChange={(v) => field.onChange(toNumberInputValue(v))}
          onBlur={field.onBlur}
          allowNegative={false}
          allowDecimal={decimals > 0}
          decimalScale={decimals}
          decimalSeparator={separator}
          hideControls
          error={fieldState.error?.message && t(fieldState.error.message)}
        />
      )}
    />
  );
}

/** Create (/fleet/new) or edit (/fleet/:id/edit) a vehicle in the right panel. */
export function VehicleForm() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const separator = decimalSeparator(i18n.resolvedLanguage ?? 'en');

  const q = useQuery({ queryKey: [...VEHICLES_KEY, id], queryFn: () => fleetApi.get(id), enabled: !isNew });
  const list = useQuery({ queryKey: VEHICLES_KEY, queryFn: fleetApi.list });
  const current = q.data;
  const backPath = isNew ? '/fleet' : `/fleet/${id}`;

  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
    values: current ? toFormValues(current) : undefined,
  });
  const kind = useWatch({ control, name: 'kind' });
  const tachoGen = useWatch({ control, name: 'tachoGen' });
  const trailer = isTrailer(kind);

  const trailerOptions = useMemo(
    () =>
      (list.data ?? [])
        .filter((v) => isTrailer(v.kind) && String(v.id) !== id)
        .map((v) => ({ value: String(v.id), label: v.plate })),
    [list.data, id],
  );

  const options = <T extends string>(values: readonly T[], prefix: string) =>
    values.map((v) => ({ value: v, label: t(`${prefix}.${v}`) }));

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f, current);
      const saved = isNew ? await fleetApi.create(body) : await fleetApi.update(id, body);
      qc.setQueryData([...VEHICLES_KEY, String(saved.id)], saved);
      await qc.invalidateQueries({ queryKey: VEHICLES_KEY, exact: true });
      notifications.show({ color: 'green', message: t(isNew ? 'fleet.created' : 'fleet.updated') });
      await navigate(`/fleet/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS, { heightMm: 'heightM' })) {
        notifications.show({ color: 'red', message: t(errorCode(e)) });
      }
    }
  });

  return (
    <DetailDrawer
      mode="drawer"
      listPath={backPath}
      title={isNew ? t('fleet.new') : current ? <Plate size="lg">{current.plate}</Plate> : '…'}
      subtitle={!isNew && t('common.edit')}
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="lg">
            <Stack gap="sm">
              <Group grow align="flex-start">
                <Controller
                  name="kind"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={t('fleet.kind')}
                      data={options(VEHICLE_KINDS, 'vehicle.kind')}
                      value={field.value}
                      onChange={(v) => v && field.onChange(v)}
                      allowDeselect={false}
                    />
                  )}
                />
                <Controller
                  name="status"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={t('fleet.status')}
                      data={options(VEHICLE_STATUSES, 'vehicle.status')}
                      value={field.value}
                      onChange={(v) => v && field.onChange(v)}
                      allowDeselect={false}
                    />
                  )}
                />
              </Group>
              <Group grow align="flex-start">
                <TextInput
                  label={t('fleet.plate')}
                  autoComplete="off"
                  autoFocus={isNew}
                  classNames={{ input: classes.mono }}
                  error={errors.plate?.message && t(errors.plate.message)}
                  {...register('plate')}
                />
                <TextInput
                  label={t('fleet.vin')}
                  autoComplete="off"
                  maxLength={25}
                  classNames={{ input: classes.mono }}
                  error={errors.vin?.message && t(errors.vin.message)}
                  {...register('vin')}
                />
              </Group>
              <Group grow align="flex-start">
                <TextInput
                  label={t('fleet.make')}
                  autoComplete="off"
                  error={errors.make?.message && t(errors.make.message)}
                  {...register('make')}
                />
                <TextInput
                  label={t('fleet.model')}
                  autoComplete="off"
                  error={errors.model?.message && t(errors.model.message)}
                  {...register('model')}
                />
              </Group>
            </Stack>

            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('fleet.section.route')}</h3>
              <Group grow align="flex-start">
                <NumberField control={control} name="axles" label={t('fleet.axles')} separator={separator} />
                <NumberField control={control} name="grossWeightKg" label={t('fleet.gross_weight_kg')} separator={separator} />
                <NumberField control={control} name="heightM" label={t('fleet.height_m')} decimals={2} separator={separator} />
              </Group>
              {!trailer && (
                <Group grow align="flex-start">
                  <Controller
                    name="euroClass"
                    control={control}
                    render={({ field }) => (
                      <Select
                        label={t('fleet.euro_class')}
                        data={options(EURO_CLASSES, 'vehicle.euro')}
                        value={field.value}
                        onChange={(v) => field.onChange(v)}
                        clearable
                      />
                    )}
                  />
                  <Controller
                    name="fuelType"
                    control={control}
                    render={({ field }) => (
                      <Select
                        label={t('fleet.fuel_type')}
                        data={options(FUEL_TYPES, 'vehicle.fuel')}
                        value={field.value}
                        onChange={(v) => field.onChange(v)}
                        clearable
                      />
                    )}
                  />
                  <NumberField
                    control={control}
                    name="avgL100km"
                    label={t('fleet.consumption_l100')}
                    decimals={2}
                    separator={separator}
                  />
                </Group>
              )}
            </section>

            {!trailer && (
              <section className={classes.section}>
                <h3 className={classes.sectionTitle}>{t('fleet.section.operation')}</h3>
                <Group grow align="flex-start">
                  <Controller
                    name="tachoGen"
                    control={control}
                    render={({ field }) => (
                      <Select
                        label={t('fleet.tacho')}
                        data={options(TACHO_GENS, 'vehicle.tacho')}
                        value={field.value}
                        onChange={(v) => v && field.onChange(v)}
                        allowDeselect={false}
                      />
                    )}
                  />
                  <NumberField control={control} name="odometerKm" label={t('fleet.odometer_km')} separator={separator} />
                </Group>
                <Controller
                  name="defaultTrailerId"
                  control={control}
                  render={({ field, fieldState }) => (
                    <Select
                      label={t('fleet.default_trailer')}
                      placeholder={trailerOptions.length === 0 ? t('fleet.no_trailers') : undefined}
                      data={trailerOptions}
                      value={field.value}
                      onChange={(v) => field.onChange(v)}
                      clearable
                      searchable
                      disabled={trailerOptions.length === 0 && !field.value}
                      classNames={{ input: classes.mono }}
                      error={fieldState.error?.message && t(fieldState.error.message)}
                    />
                  )}
                />
                {tachoGen === 'NONE' && <div className={classes.docSub}>{t('fleet.no_tacho_hint')}</div>}
              </section>
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
