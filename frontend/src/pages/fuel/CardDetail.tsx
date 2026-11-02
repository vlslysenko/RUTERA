import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Checkbox, Group, Select, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { FUEL_CARDS_KEY, FUEL_PROVIDERS, FUEL_TX_KEY, fuelApi, type FuelCard, type FuelCardInput } from '@/api/fuel';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { applyServerErrors } from '@/forms/serverErrors';
import { idOrNull, useCrewOptions } from '@/pages/trips/crew';
import classes from '@/pages/trips/Trips.module.css';

const LIST = '/fuel/cards';
const CARD_RE = /^[0-9 -]{6,30}$/;

/* The number is write-only: required for a new card or another provider, empty keeps the stored one. */
const schema = z.object({
  provider: z.enum(FUEL_PROVIDERS),
  cardNumber: z.string().trim().refine((v) => v === '' || CARD_RE.test(v), 'validation.invalid_format'),
  vehicleId: z.string().nullable(),
  driverId: z.string().nullable(),
  validTo: z.string(),
  active: z.boolean(),
  notes: z.string().max(500, 'validation.too_long'),
});

type FormValues = z.infer<typeof schema>;
const FIELDS: readonly FieldPath<FormValues>[] = ['provider', 'cardNumber', 'vehicleId', 'driverId', 'validTo', 'active', 'notes'];

const toForm = (c: FuelCard): FormValues => ({
  provider: c.provider,
  cardNumber: '',
  vehicleId: c.vehicle ? String(c.vehicle.id) : null,
  driverId: c.driver ? String(c.driver.id) : null,
  validTo: c.validTo ?? '',
  active: c.active,
  notes: c.notes ?? '',
});

/** Fuel card = its form (read-only without fuel.import/manage); also creates one (/fuel/cards/new). */
export function CardDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined || params.id === 'new';
  const id = isNew ? '' : (params.id ?? '');
  const canManage = hasPrivilege(me, PRIV.FUEL_IMPORT_MANAGE);
  const q = useQuery({ queryKey: [...FUEL_CARDS_KEY, id], queryFn: () => fuelApi.card(id), enabled: !isNew });
  const card = q.data;
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const {
    control,
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { provider: 'DKV', cardNumber: '', vehicleId: null, driverId: null, validTo: '', active: true, notes: '' },
    values: card ? toForm(card) : undefined,
  });
  const crew = useCrewOptions([watch('vehicleId'), watch('driverId')]);
  const providerChanged = !!card && watch('provider') !== card.provider;
  const err = (name: keyof FormValues) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const number = f.cardNumber.replace(/[\s-]/g, '');
    if ((isNew || providerChanged) && !number) {
      setError('cardNumber', { type: 'required', message: 'validation.required' });
      return;
    }
    const body: FuelCardInput = {
      provider: f.provider,
      cardNumber: number || null,
      vehicleId: idOrNull(f.vehicleId),
      driverId: idOrNull(f.driverId),
      validTo: f.validTo || null,
      active: f.active,
      notes: f.notes.trim() || null,
    };
    try {
      const saved = isNew ? await fuelApi.createCard(body) : await fuelApi.updateCard(id, body);
      qc.setQueryData([...FUEL_CARDS_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: FUEL_CARDS_KEY, exact: true });
      // A vehicle or driver set on a card fills its transactions that had none.
      void qc.invalidateQueries({ queryKey: FUEL_TX_KEY });
      notifications.show({ color: 'green', message: t(isNew ? 'fuel.cards.created' : 'fuel.cards.updated') });
      if (isNew) await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const remove = async () => {
    setBusy(true);
    try {
      await fuelApi.removeCard(id);
      notifications.show({ color: 'green', message: t('fuel.cards.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: FUEL_CARDS_KEY, predicate: (x) => x.queryKey[FUEL_CARDS_KEY.length] !== id });
      void qc.invalidateQueries({ queryKey: FUEL_TX_KEY });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={isNew ? undefined : `${LIST}/${id}/page`}
      title={isNew ? t('fuel.cards.new') : card ? <span className={classes.mono}>{card.cardNumberMasked}</span> : '…'}
      subtitle={card && t(`fuel.provider.${card.provider}`)}
      actions={
        !isNew && card && canManage ? (
          <Button variant="default" size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)}>
            {t('common.delete')}
          </Button>
        ) : undefined
      }
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('fuel.cards.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          <form onSubmit={(e) => void onSubmit(e)} noValidate>
            <Stack gap="sm">
              <Group grow align="flex-start">
                <Controller
                  name="provider"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={t('fuel.provider')}
                      data={FUEL_PROVIDERS.map((p) => ({ value: p, label: t(`fuel.provider.${p}`) }))}
                      value={field.value}
                      onChange={(v) => v && field.onChange(v)}
                      allowDeselect={false}
                      disabled={!canManage}
                      error={err('provider')}
                    />
                  )}
                />
                <TextInput
                  label={t('fuel.cards.number')}
                  description={isNew ? undefined : t(providerChanged ? 'fuel.cards.number_again' : 'fuel.cards.number_keep')}
                  placeholder={card?.cardNumberMasked}
                  classNames={{ input: classes.mono }}
                  readOnly={!canManage}
                  autoComplete="off"
                  error={err('cardNumber')}
                  {...register('cardNumber')}
                />
              </Group>
              <Group grow align="flex-start">
                <Controller
                  name="vehicleId"
                  control={control}
                  render={({ field }) => (
                    <Select label={t('trips.vehicle')} data={crew.vehicles} value={field.value} onChange={field.onChange} clearable searchable disabled={!canManage} error={err('vehicleId')} />
                  )}
                />
                <Controller
                  name="driverId"
                  control={control}
                  render={({ field }) => (
                    <Select label={t('trips.driver')} data={crew.drivers} value={field.value} onChange={field.onChange} clearable searchable disabled={!canManage} error={err('driverId')} />
                  )}
                />
              </Group>
              <Text size="xs" c="dimmed">
                {t('fuel.cards.links_hint')}
              </Text>
              <Group grow align="flex-start">
                <TextInput type="date" label={t('fuel.cards.valid_to')} readOnly={!canManage} error={err('validTo')} {...register('validTo')} />
                <Controller
                  name="active"
                  control={control}
                  render={({ field }) => (
                    <Checkbox mt={30} label={t('fuel.cards.active')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} disabled={!canManage} />
                  )}
                />
              </Group>
              <Textarea label={t('drivers.notes')} autosize minRows={2} maxRows={5} readOnly={!canManage} error={err('notes')} {...register('notes')} />
              {canManage && (
                <Group justify="flex-end" gap="xs">
                  <Button variant="default" onClick={() => void navigate(LIST)} disabled={isSubmitting}>
                    {t('common.cancel')}
                  </Button>
                  <Button type="submit" loading={isSubmitting} disabled={!isNew && !isDirty}>
                    {isNew ? t('common.create') : t('common.save')}
                  </Button>
                </Group>
              )}
            </Stack>
          </form>
        </Stack>
      )}
    </DetailDrawer>
  );
}
