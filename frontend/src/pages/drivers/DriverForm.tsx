import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { DRIVER_STATUSES, DRIVERS_KEY, driversApi, type Driver, type DriverInput } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { applyServerErrors } from '@/forms/serverErrors';
import { countryOptions } from '@/utils/countries';
import classes from './DriversPage.module.css';

/* Same rules as the backend. Card number: spaces removed, letters uppercased, letters and digits only. */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CARD_RE = /^[A-Z0-9]{1,32}$/;
const cardOf = (v: string) => v.replace(/\s+/g, '').toUpperCase();

const schema = z
  .object({
    firstName: z.string().trim().min(1, 'validation.required').max(100, 'validation.too_long'),
    lastName: z.string().trim().min(1, 'validation.required').max(100, 'validation.too_long'),
    birthDate: z.string(),
    phone: z.string().trim().max(40, 'validation.too_long'),
    email: z
      .string()
      .trim()
      .max(254, 'validation.too_long')
      .refine((v) => v === '' || EMAIL_RE.test(v), 'validation.invalid_format'),
    country: z.string().nullable(),
    employmentFrom: z.string(),
    employmentTo: z.string(),
    status: z.enum(DRIVER_STATUSES),
    notes: z.string().max(2000, 'validation.too_long'),
    cardNumber: z.string().refine((v) => cardOf(v) === '' || CARD_RE.test(cardOf(v)), 'validation.invalid_format'),
    cardIssuingCountry: z.string().nullable(),
    cardValidTo: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.employmentFrom && v.employmentTo && v.employmentTo < v.employmentFrom) {
      ctx.addIssue({ code: 'custom', path: ['employmentTo'], message: 'validation.invalid_value' });
    }
    if (cardOf(v.cardNumber) && !v.cardIssuingCountry) {
      ctx.addIssue({ code: 'custom', path: ['cardIssuingCountry'], message: 'validation.required' });
    }
  });

type FormValues = z.infer<typeof schema>;

const FIELDS: readonly FieldPath<FormValues>[] = [
  'firstName',
  'lastName',
  'birthDate',
  'phone',
  'email',
  'country',
  'employmentFrom',
  'employmentTo',
  'status',
  'notes',
  'cardNumber',
  'cardIssuingCountry',
  'cardValidTo',
];

const EMPTY: FormValues = {
  firstName: '',
  lastName: '',
  birthDate: '',
  phone: '',
  email: '',
  country: null,
  employmentFrom: '',
  employmentTo: '',
  status: 'ACTIVE',
  notes: '',
  cardNumber: '',
  cardIssuingCountry: null,
  cardValidTo: '',
};

function toFormValues(d: Driver): FormValues {
  return {
    firstName: d.firstName,
    lastName: d.lastName,
    birthDate: d.birthDate ?? '',
    phone: d.phone ?? '',
    email: d.email ?? '',
    country: d.country,
    employmentFrom: d.employmentFrom ?? '',
    employmentTo: d.employmentTo ?? '',
    status: d.status,
    notes: d.notes ?? '',
    cardNumber: d.card?.cardNumber ?? '',
    cardIssuingCountry: d.card?.issuingCountry ?? null,
    cardValidTo: d.card?.validTo ?? '',
  };
}

const blankToNull = (s: string) => (s.trim() === '' ? null : s.trim());

function toInput(f: FormValues): DriverInput {
  const card = cardOf(f.cardNumber);
  return {
    firstName: f.firstName.trim(),
    lastName: f.lastName.trim(),
    birthDate: f.birthDate || null,
    phone: blankToNull(f.phone),
    email: blankToNull(f.email),
    country: f.country,
    employmentFrom: f.employmentFrom || null,
    employmentTo: f.employmentTo || null,
    status: f.status,
    notes: blankToNull(f.notes),
    // An empty number removes the card.
    cardNumber: card || null,
    cardIssuingCountry: card ? f.cardIssuingCountry : null,
    cardValidTo: card ? f.cardValidTo || null : null,
  };
}

/** Create (/drivers/new) or edit (/drivers/:id/edit) a driver in the right panel. */
export function DriverForm() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const lang = i18n.resolvedLanguage ?? 'en';

  const q = useQuery({ queryKey: [...DRIVERS_KEY, id], queryFn: () => driversApi.get(id), enabled: !isNew });
  const current = q.data;
  const backPath = isNew ? '/drivers' : `/drivers/${id}`;

  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
    values: current ? toFormValues(current) : undefined,
  });

  const err = (name: keyof FormValues) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      const saved = isNew ? await driversApi.create(body) : await driversApi.update(id, body);
      qc.setQueryData([...DRIVERS_KEY, String(saved.id)], saved);
      await qc.invalidateQueries({ queryKey: DRIVERS_KEY, exact: true });
      notifications.show({ color: 'green', message: t(isNew ? 'drivers.created' : 'drivers.updated') });
      await navigate(`/drivers/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) {
        notifications.show({ color: 'red', message: t(errorCode(e)) });
      }
    }
  });

  const countrySelect = (name: 'country' | 'cardIssuingCountry', label: string) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <Select
          label={label}
          data={countryOptions(lang, field.value)}
          value={field.value}
          onChange={(v) => field.onChange(v)}
          clearable
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
      title={isNew ? t('drivers.new') : (current?.name ?? '…')}
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
                <TextInput
                  label={t('drivers.first_name')}
                  autoComplete="off"
                  autoFocus={isNew}
                  error={err('firstName')}
                  {...register('firstName')}
                />
                <TextInput label={t('drivers.last_name')} autoComplete="off" error={err('lastName')} {...register('lastName')} />
              </Group>
              <Group grow align="flex-start">
                <TextInput type="date" label={t('drivers.birth_date')} error={err('birthDate')} {...register('birthDate')} />
                {countrySelect('country', t('drivers.country'))}
              </Group>
              <Group grow align="flex-start">
                <TextInput type="tel" label={t('drivers.phone')} autoComplete="off" error={err('phone')} {...register('phone')} />
                <TextInput type="email" label={t('drivers.email')} autoComplete="off" error={err('email')} {...register('email')} />
              </Group>
            </Stack>

            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('drivers.section.work')}</h3>
              <Group grow align="flex-start">
                <TextInput
                  type="date"
                  label={t('drivers.employment_from')}
                  error={err('employmentFrom')}
                  {...register('employmentFrom')}
                />
                <TextInput type="date" label={t('drivers.employment_to')} error={err('employmentTo')} {...register('employmentTo')} />
                <Controller
                  name="status"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={t('drivers.status')}
                      data={DRIVER_STATUSES.map((s) => ({ value: s, label: t(`driver.status.${s}`) }))}
                      value={field.value}
                      onChange={(v) => v && field.onChange(v)}
                      allowDeselect={false}
                    />
                  )}
                />
              </Group>
              <div className={classes.hint}>{t('drivers.vehicle_hint')}</div>
            </section>

            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('drivers.section.card')}</h3>
              <Group grow align="flex-start">
                <TextInput
                  label={t('drivers.card_number')}
                  autoComplete="off"
                  maxLength={40}
                  classNames={{ input: classes.mono }}
                  error={err('cardNumber')}
                  {...register('cardNumber')}
                />
                {countrySelect('cardIssuingCountry', t('drivers.card_country'))}
              </Group>
              <Group grow align="flex-start">
                <TextInput type="date" label={t('drivers.card_valid_to')} error={err('cardValidTo')} {...register('cardValidTo')} />
                <div />
              </Group>
              <div className={classes.hint}>{t('drivers.card_hint')}</div>
            </section>

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
