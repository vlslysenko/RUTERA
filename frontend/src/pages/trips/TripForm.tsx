import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, get, useFieldArray, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Button, Group, MultiSelect, NumberInput, Select, Stack, Text, Textarea, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowDown, IconArrowUp, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import {
  ORDERS_KEY,
  ordersApi,
  STOP_KINDS,
  STOP_STATUSES,
  TRIP_STATUSES,
  TRIPS_KEY,
  tripsApi,
  type Order,
  type Trip,
  type TripInput,
} from '@/api/trips';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { countryOptions } from '@/utils/countries';
import { idOrNull, useCrewOptions } from './crew';
import { fromLocalInput, toLocalInput } from './tripFormat';
import classes from './Trips.module.css';
import { FormDateField } from '@/forms/DateField';

const LIST = '/trips';
const NUMBER_RE = /^[\p{L}\p{N}][\p{L}\p{N} ./_-]*$/u;
const MAX_STOPS = 100;

const stopSchema = z.object({
  // Not "id": useFieldArray keeps its own row key under that name.
  stopId: z.number().nullable(),
  kind: z.enum(STOP_KINDS),
  orderId: z.string().nullable(),
  name: z.string().trim().max(200, 'validation.too_long'),
  address: z.string().trim().min(1, 'validation.required').max(500, 'validation.too_long'),
  country: z.string().nullable().refine((v) => !!v, 'validation.required'),
  plannedAt: z.string(),
  actualAt: z.string(),
  status: z.enum(STOP_STATUSES),
});

/* Same rules as TripService: a vehicle, a trailer of trailer kind, the second driver needs a different main, up to 100 stops. */
const schema = z
  .object({
    number: z
      .string()
      .trim()
      .max(32, 'validation.too_long')
      .refine((v) => v === '' || NUMBER_RE.test(v), 'validation.invalid_format'),
    vehicleId: z.string().nullable().refine((v) => !!v, 'validation.required'),
    trailerId: z.string().nullable(),
    mainDriverId: z.string().nullable(),
    secondDriverId: z.string().nullable(),
    status: z.enum(TRIP_STATUSES),
    plannedStart: z.string(),
    plannedEnd: z.string(),
    actualStart: z.string(),
    actualEnd: z.string(),
    kmPlanned: numberField(0, 100_000, { integer: true }),
    kmActual: numberField(0, 100_000, { integer: true }),
    orderIds: z.array(z.string()),
    stops: z.array(stopSchema).max(MAX_STOPS, 'validation.too_long'),
    notes: z.string().max(2000, 'validation.too_long'),
  })
  .superRefine((v, ctx) => {
    if (v.secondDriverId && (!v.mainDriverId || v.secondDriverId === v.mainDriverId)) {
      ctx.addIssue({ code: 'custom', path: ['secondDriverId'], message: 'validation.invalid_value' });
    }
    if (v.plannedStart && v.plannedEnd && v.plannedEnd < v.plannedStart) {
      ctx.addIssue({ code: 'custom', path: ['plannedEnd'], message: 'validation.invalid_value' });
    }
    if (v.actualStart && v.actualEnd && v.actualEnd < v.actualStart) {
      ctx.addIssue({ code: 'custom', path: ['actualEnd'], message: 'validation.invalid_value' });
    }
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
type StopForm = FormInput['stops'][number];

const FIELDS: readonly FieldPath<FormInput>[] = [
  'number', 'vehicleId', 'trailerId', 'mainDriverId', 'secondDriverId', 'status', 'plannedStart', 'plannedEnd',
  'actualStart', 'actualEnd', 'kmPlanned', 'kmActual', 'orderIds', 'stops', 'notes',
];
const STOP_FIELDS = ['kind', 'orderId', 'name', 'address', 'country', 'plannedAt', 'actualAt', 'status'] as const;

const EMPTY: FormInput = {
  number: '',
  vehicleId: null,
  trailerId: null,
  mainDriverId: null,
  secondDriverId: null,
  status: 'PLANNED',
  plannedStart: '',
  plannedEnd: '',
  actualStart: '',
  actualEnd: '',
  kmPlanned: null,
  kmActual: null,
  orderIds: [],
  stops: [],
  notes: '',
};

const newStop = (kind: StopForm['kind'] = 'OTHER'): StopForm => ({
  stopId: null,
  kind,
  orderId: null,
  name: '',
  address: '',
  country: null,
  plannedAt: '',
  actualAt: '',
  status: 'PLANNED',
});

/** Loading and unloading of an order as two new stops (as POST /orders/{id}/trip does). */
function stopsOfOrder(o: Order): StopForm[] {
  const at = (date: string | null) => (date ? `${date}T08:00` : '');
  return [
    { ...newStop('LOADING'), orderId: String(o.id), address: o.loading.place, country: o.loading.country, plannedAt: at(o.loading.date) },
    { ...newStop('UNLOADING'), orderId: String(o.id), address: o.unloading.place, country: o.unloading.country, plannedAt: at(o.unloading.date) },
  ];
}

function toFormValues(t: Trip): FormInput {
  return {
    number: t.number,
    vehicleId: String(t.vehicle.id),
    trailerId: t.trailer ? String(t.trailer.id) : null,
    mainDriverId: idString(t.drivers.find((d) => d.role === 'MAIN')?.id),
    secondDriverId: idString(t.drivers.find((d) => d.role === 'SECOND')?.id),
    status: t.status,
    plannedStart: toLocalInput(t.plannedStart),
    plannedEnd: toLocalInput(t.plannedEnd),
    actualStart: toLocalInput(t.actualStart),
    actualEnd: toLocalInput(t.actualEnd),
    kmPlanned: t.kmPlanned,
    kmActual: t.kmActual,
    orderIds: t.orders.map((o) => String(o.id)),
    stops: t.stops.map((s) => ({
      stopId: s.id,
      kind: s.kind,
      orderId: idString(s.orderId),
      name: s.name ?? '',
      address: s.address,
      country: s.country,
      plannedAt: toLocalInput(s.plannedAt),
      actualAt: toLocalInput(s.actualAt),
      status: s.status,
    })),
    notes: t.notes ?? '',
  };
}

const idString = (id: number | null | undefined) => (id == null ? null : String(id));
const blank = (s: string) => s.trim() || null;

function toInput(f: FormValues): TripInput {
  return {
    number: blank(f.number),
    vehicleId: Number(f.vehicleId),
    trailerId: idOrNull(f.trailerId),
    mainDriverId: idOrNull(f.mainDriverId),
    secondDriverId: idOrNull(f.secondDriverId),
    status: f.status,
    plannedStart: fromLocalInput(f.plannedStart),
    plannedEnd: fromLocalInput(f.plannedEnd),
    actualStart: fromLocalInput(f.actualStart),
    actualEnd: fromLocalInput(f.actualEnd),
    kmPlanned: f.kmPlanned,
    kmActual: f.kmActual,
    orderIds: f.orderIds.map(Number),
    stops: f.stops.map((s) => ({
      id: s.stopId,
      kind: s.kind,
      orderId: idOrNull(s.orderId),
      name: blank(s.name),
      address: s.address.trim(),
      country: s.country ?? '',
      plannedAt: fromLocalInput(s.plannedAt),
      actualAt: s.status === 'PLANNED' ? null : fromLocalInput(s.actualAt),
      status: s.status,
    })),
    notes: blank(f.notes),
  };
}

/** Create (/trips/new) or edit (/trips/:id/edit) a trip: crew, schedule, orders and the ordered list of stops. */
export function TripForm() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const backPath = isNew ? LIST : `${LIST}/${id}`;

  const q = useQuery({ queryKey: [...TRIPS_KEY, id], queryFn: () => tripsApi.get(id), enabled: !isNew });
  const current = q.data;
  // Orders that may join: those without a trip (NEW); the trip's own orders come with the trip.
  const free = useQuery({ queryKey: [...ORDERS_KEY, 'list', { status: 'NEW', size: 200 }], queryFn: () => ordersApi.list({ status: 'NEW', size: 200 }) });

  const {
    control,
    register,
    handleSubmit,
    setError,
    setValue,
    getValues,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
    values: current ? toFormValues(current) : undefined,
  });
  const stops = useFieldArray({ control, name: 'stops' });
  const crew = useCrewOptions([watch('vehicleId'), watch('trailerId'), watch('mainDriverId'), watch('secondDriverId')]);
  // Also nested paths (stops.2.address).
  const err = (name: FieldPath<FormInput>) => {
    const code = (get(errors, name) as { message?: string } | undefined)?.message;
    return code ? t(code) : undefined;
  };

  const orderOptions = [
    ...(current?.orders ?? []).map((o) => ({ value: String(o.id), label: `${o.number} · ${o.customer.name}` })),
    ...(free.data?.items ?? [])
      .filter((o) => !current?.orders.some((x) => x.id === o.id))
      .map((o) => ({ value: String(o.id), label: `${o.number} · ${o.customer.name} · ${o.loading.place} → ${o.unloading.place}` })),
  ];
  const selectedOrders = watch('orderIds');
  const stopOrderOptions = orderOptions.filter((o) => selectedOrders.includes(o.value)).map((o) => ({ value: o.value, label: o.label.split(' · ')[0] }));

  /** A newly picked order brings its loading and unloading; a removed one takes its stops along. */
  const changeOrders = (next: string[]) => {
    const prev = getValues('orderIds');
    const added = next.filter((x) => !prev.includes(x));
    const removed = prev.filter((x) => !next.includes(x));
    let list = getValues('stops');
    if (removed.length) list = list.filter((s) => !s.orderId || !removed.includes(s.orderId));
    for (const oid of added) {
      const o = free.data?.items.find((x) => String(x.id) === oid);
      if (o && !list.some((s) => s.orderId === oid)) list = [...list, ...stopsOfOrder(o)];
    }
    setValue('orderIds', next, { shouldDirty: true });
    if (added.length || removed.length) stops.replace(list.slice(0, MAX_STOPS));
  };

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      const saved = isNew ? await tripsApi.create(body) : await tripsApi.update(id, body);
      qc.setQueryData([...TRIPS_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...TRIPS_KEY, 'list'] });
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
      notifications.show({ color: 'green', message: t(isNew ? 'trips.created' : 'trips.updated', { number: saved.number }) });
      await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      // Stop errors come as stops[2].address; the form names that field stops.2.address.
      const aliases: Record<string, FieldPath<FormInput>> = {};
      const stopPaths: FieldPath<FormInput>[] = [];
      f.stops.forEach((_, i) => {
        for (const field of STOP_FIELDS) {
          const path = `stops.${i}.${field}` as FieldPath<FormInput>;
          aliases[`stops[${i}].${field}`] = path;
          stopPaths.push(path);
        }
        aliases[`stops[${i}].id`] = `stops.${i}.kind` as FieldPath<FormInput>;
      });
      if (!applyServerErrors(e, setError, [...FIELDS, ...stopPaths], aliases)) {
        notifications.show({ color: 'red', message: t(errorCode(e)) });
      }
    }
  });

  const select = (name: 'vehicleId' | 'trailerId' | 'mainDriverId' | 'secondDriverId', label: string, data: { value: string; label: string }[]) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <Select
          label={label}
          data={data}
          value={field.value}
          onChange={(v) => {
            field.onChange(v);
            if (name === 'vehicleId') {
              const veh = crew.vehicle(v);
              if (veh?.defaultTrailer && !getValues('trailerId')) setValue('trailerId', String(veh.defaultTrailer.id));
              if (veh?.defaultDriver && !getValues('mainDriverId')) setValue('mainDriverId', String(veh.defaultDriver.id));
            }
          }}
          clearable={name !== 'vehicleId'}
          searchable
          nothingFoundMessage={t('fleet.nothing_found')}
          error={err(name)}
        />
      )}
    />
  );
  const km = (name: 'kmPlanned' | 'kmActual', label: string) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <NumberInput
          label={label}
          value={field.value ?? ''}
          onChange={(v) => field.onChange(toNumberInputValue(v))}
          allowNegative={false}
          allowDecimal={false}
          thousandSeparator=" "
          hideControls
          error={err(name)}
        />
      )}
    />
  );

  return (
    <DetailDrawer
      mode="drawer"
      listPath={backPath}
      title={isNew ? t('trips.new') : (current?.number ?? '…')}
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
                label={t('trips.number')}
                placeholder={t('orders.number_auto')}
                classNames={{ input: classes.mono }}
                error={err('number')}
                {...register('number')}
              />
              <Controller
                name="status"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('users.status')}
                    data={TRIP_STATUSES.map((s) => ({ value: s, label: t(`trip.status.${s}`) }))}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    allowDeselect={false}
                    error={err('status')}
                  />
                )}
              />
            </Group>
            {crew.error && (
              <Text size="sm" c="red">
                {t(errorCode(crew.error))}
              </Text>
            )}
            <Group grow align="flex-start">
              {select('vehicleId', t('trips.vehicle'), crew.vehicles)}
              {select('trailerId', t('trips.trailer'), crew.trailers)}
            </Group>
            <Group grow align="flex-start">
              {select('mainDriverId', t('trips.main_driver'), crew.drivers)}
              {select('secondDriverId', t('trips.second_driver'), crew.drivers)}
            </Group>

            <h3 className={classes.sectionTitle}>{t('trips.section.schedule')}</h3>
            <Group grow align="flex-start">
              <FormDateField withTime label={t('trips.planned_start')} error={err('plannedStart')} control={control} name="plannedStart" />
              <FormDateField withTime label={t('trips.planned_end')} error={err('plannedEnd')} control={control} name="plannedEnd" />
            </Group>
            <Group grow align="flex-start">
              <FormDateField withTime label={t('trips.actual_start')} error={err('actualStart')} control={control} name="actualStart" />
              <FormDateField withTime label={t('trips.actual_end')} error={err('actualEnd')} control={control} name="actualEnd" />
            </Group>
            <Group grow align="flex-start">
              {km('kmPlanned', t('trips.km_planned'))}
              {km('kmActual', t('trips.km_actual'))}
            </Group>

            <h3 className={classes.sectionTitle}>{t('trips.tab.orders')}</h3>
            <Controller
              name="orderIds"
              control={control}
              render={({ field }) => (
                <MultiSelect
                  aria-label={t('trips.tab.orders')}
                  description={t('trips.orders_hint')}
                  data={orderOptions}
                  value={field.value}
                  onChange={changeOrders}
                  searchable
                  clearable
                  nothingFoundMessage={t('fleet.nothing_found')}
                  error={err('orderIds')}
                />
              )}
            />

            <Group justify="space-between">
              <h3 className={classes.sectionTitle}>{t('trips.section.stops')}</h3>
              <Button
                size="xs"
                variant="light"
                leftSection={<IconPlus size={14} />}
                onClick={() => stops.append(newStop())}
                disabled={stops.fields.length >= MAX_STOPS}
              >
                {t('trips.add_stop')}
              </Button>
            </Group>
            {err('stops') && (
              <Text size="sm" c="red">
                {err('stops')}
              </Text>
            )}
            {stops.fields.map((s, i) => {
              const status = watch(`stops.${i}.status`);
              return (
                <div key={s.id} className={classes.stopEditor}>
                  <Stack gap={6}>
                    <Group gap={6} wrap="nowrap" align="flex-end">
                      <Text className={classes.seq} pb={6}>
                        {i + 1}
                      </Text>
                      <Controller
                        name={`stops.${i}.kind`}
                        control={control}
                        render={({ field }) => (
                          <Select
                            size="xs"
                            label={t('trips.stop_kind')}
                            data={STOP_KINDS.map((k) => ({ value: k, label: t(`trip.stop.kind.${k}`) }))}
                            value={field.value}
                            onChange={(v) => v && field.onChange(v)}
                            allowDeselect={false}
                            error={err(`stops.${i}.kind`)}
                            style={{ flex: 1 }}
                          />
                        )}
                      />
                      <Controller
                        name={`stops.${i}.orderId`}
                        control={control}
                        render={({ field }) => (
                          <Select
                            size="xs"
                            label={t('orders.number')}
                            data={stopOrderOptions}
                            value={field.value}
                            onChange={field.onChange}
                            clearable
                            error={err(`stops.${i}.orderId`)}
                            style={{ flex: 1 }}
                          />
                        )}
                      />
                      <Tooltip label={t('trips.move_up')}>
                        <ActionIcon variant="subtle" color="gray" disabled={i === 0} onClick={() => stops.move(i, i - 1)} aria-label={t('trips.move_up')} mb={2}>
                          <IconArrowUp size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <Tooltip label={t('trips.move_down')}>
                        <ActionIcon
                          variant="subtle"
                          color="gray"
                          disabled={i === stops.fields.length - 1}
                          onClick={() => stops.move(i, i + 1)}
                          aria-label={t('trips.move_down')}
                          mb={2}
                        >
                          <IconArrowDown size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <Tooltip label={t('common.delete')}>
                        <ActionIcon variant="subtle" color="red" onClick={() => stops.remove(i)} aria-label={t('common.delete')} mb={2}>
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Group>
                    <Group grow align="flex-start" gap={6}>
                      <TextInput size="xs" label={t('trips.stop_name')} error={err(`stops.${i}.name`)} {...register(`stops.${i}.name`)} />
                      <Controller
                        name={`stops.${i}.country`}
                        control={control}
                        render={({ field }) => (
                          <Select
                            size="xs"
                            label={t('trips.country')}
                            data={countryOptions(lang, field.value)}
                            value={field.value}
                            onChange={field.onChange}
                            searchable
                            error={err(`stops.${i}.country`)}
                          />
                        )}
                      />
                    </Group>
                    <TextInput size="xs" label={t('trips.stop_address')} error={err(`stops.${i}.address`)} {...register(`stops.${i}.address`)} />
                    <Group grow align="flex-start" gap={6}>
                      <FormDateField withTime size="xs" label={t('trips.stop_planned')} error={err(`stops.${i}.plannedAt`)} control={control} name={`stops.${i}.plannedAt`} />
                      <Controller
                        name={`stops.${i}.status`}
                        control={control}
                        render={({ field }) => (
                          <Select
                            size="xs"
                            label={t('users.status')}
                            data={STOP_STATUSES.map((st) => ({ value: st, label: t(`trip.stop.status.${st}`) }))}
                            value={field.value}
                            onChange={(v) => v && field.onChange(v)}
                            allowDeselect={false}
                            error={err(`stops.${i}.status`)}
                          />
                        )}
                      />
                    </Group>
                    {status !== 'PLANNED' && (
                      <FormDateField withTime
                        size="xs"
                        label={t('trips.stop_actual')}
                        description={t('trips.stop_actual_hint')}
                        error={err(`stops.${i}.actualAt`)}
                        control={control}
                        name={`stops.${i}.actualAt`}
                      />
                    )}
                  </Stack>
                </div>
              );
            })}

            <Textarea label={t('drivers.notes')} autosize minRows={2} maxRows={6} error={err('notes')} {...register('notes')} />
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
