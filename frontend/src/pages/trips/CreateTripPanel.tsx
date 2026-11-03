import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, Paper, Select, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { ORDERS_KEY, ordersApi, TRIPS_KEY, type Order } from '@/api/trips';
import { applyServerErrors } from '@/forms/serverErrors';
import { FormDateField } from '@/forms/DateField';
import { fromLocalInput } from './tripFormat';
import { idOrNull, useCrewOptions } from './crew';

/* Same rules as TripService.createForOrder: a vehicle, a trailer only of trailer kind, the second driver needs a different main. */
const schema = z
  .object({
    vehicleId: z.string().nullable().refine((v) => !!v, 'validation.required'),
    trailerId: z.string().nullable(),
    mainDriverId: z.string().nullable(),
    secondDriverId: z.string().nullable(),
    plannedStart: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.secondDriverId && (!v.mainDriverId || v.secondDriverId === v.mainDriverId)) {
      ctx.addIssue({ code: 'custom', path: ['secondDriverId'], message: 'validation.invalid_value' });
    }
  });

type FormValues = z.infer<typeof schema>;
const FIELDS: readonly FieldPath<FormValues>[] = ['vehicleId', 'trailerId', 'mainDriverId', 'secondDriverId', 'plannedStart'];

/** "Create trip" from an order: vehicle with its default trailer and driver, start time optional (the loading day). */
export function CreateTripPanel({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const {
    control,
    handleSubmit,
    setError,
    setValue,
    getValues,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { vehicleId: null, trailerId: null, mainDriverId: null, secondDriverId: null, plannedStart: '' },
  });
  const crew = useCrewOptions([watch('vehicleId'), watch('trailerId'), watch('mainDriverId'), watch('secondDriverId')]);
  const err = (name: keyof FormValues) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    try {
      const trip = await ordersApi.createTrip(order.id, {
        vehicleId: Number(f.vehicleId),
        trailerId: idOrNull(f.trailerId),
        mainDriverId: idOrNull(f.mainDriverId),
        secondDriverId: idOrNull(f.secondDriverId),
        plannedStart: fromLocalInput(f.plannedStart),
      });
      qc.setQueryData([...TRIPS_KEY, String(trip.id)], trip);
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
      void qc.invalidateQueries({ queryKey: [...TRIPS_KEY, 'list'] });
      notifications.show({ color: 'green', message: t('orders.trip_created', { number: trip.number }) });
      await navigate(`/trips/${trip.id}`);
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
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
            // A chosen vehicle brings its default trailer and driver into empty fields.
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

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          <Text fw={600}>{t('orders.create_trip')}</Text>
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
          <FormDateField withTime
            label={t('trips.planned_start')}
            description={t('orders.planned_start_hint')}
            error={err('plannedStart')}
            control={control}
            name="plannedStart"
          />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onClose} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}
