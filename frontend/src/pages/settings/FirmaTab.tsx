import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, Paper, Select, Stack, Switch, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { FIRMA_KEY, firmaApi, type FirmaSettings, type FirmaSettingsInput } from '@/api/admin';
import { errorCode } from '@/api/errors';
import { ME_QUERY_KEY } from '@/auth/useAuth';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { KeyValueList } from '@/components/KeyValueList';
import { applyServerErrors } from '@/forms/serverErrors';
import { LANGUAGES } from '@/i18n/languages';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { countryName } from '@/utils/countries';
import { TIME_ZONES as ZONES } from '@/utils/zone';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const schema = z.object({
  name: z.string().trim().min(1, 'validation.required').max(200, 'validation.too_long'),
  vatId: z.string().trim().max(32, 'validation.too_long'),
  address: z.string().max(500, 'validation.too_long'),
  email: z
    .string()
    .trim()
    .max(254, 'validation.too_long')
    .refine((v) => v === '' || EMAIL_RE.test(v), 'validation.invalid_format'),
  lang: z.string().min(1, 'validation.required'),
  timezone: z.string().min(1, 'validation.required'),
  baseCurrency: z.string().min(1, 'validation.required'),
  driversSeePay: z.boolean(),
});

type FormValues = z.infer<typeof schema>;
const FIELDS: readonly FieldPath<FormValues>[] = ['name', 'vatId', 'address', 'email', 'lang', 'timezone', 'baseCurrency', 'driversSeePay'];

const toForm = (f: FirmaSettings): FormValues => ({
  name: f.name,
  vatId: f.vatId ?? '',
  address: f.address ?? '',
  email: f.email ?? '',
  lang: f.lang,
  timezone: f.timezone,
  baseCurrency: f.baseCurrency,
  driversSeePay: f.driversSeePay,
});

const blank = (s: string) => s.trim() || null;

/** Settings -> Company (admin.firma/manage): name and contact data, language, time zone, base currency, driver pay visibility. */
export function FirmaTab() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const q = useQuery({ queryKey: FIRMA_KEY, queryFn: firmaApi.get });
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({ resolver: zodResolver(schema), values: q.data ? toForm(q.data) : undefined });
  const err = (name: keyof FormValues) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const body: FirmaSettingsInput = {
      name: f.name.trim(),
      vatId: blank(f.vatId),
      address: blank(f.address),
      email: blank(f.email),
      lang: f.lang,
      timezone: f.timezone,
      baseCurrency: f.baseCurrency,
      driversSeePay: f.driversSeePay,
    };
    try {
      qc.setQueryData(FIRMA_KEY, await firmaApi.update(body));
      // The firma's name, zone and pay visibility come with /auth/me.
      void qc.invalidateQueries({ queryKey: ME_QUERY_KEY });
      notifications.show({ color: 'green', message: t('common.saved') });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  if (q.isPending) return <FullScreenLoader fullScreen={false} />;
  if (q.isError) return <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />;
  const f = q.data;
  const zones = ZONES.includes(f.timezone) ? ZONES : [f.timezone, ...ZONES];
  const currencies = CURRENCIES.includes(f.baseCurrency) ? CURRENCIES : [f.baseCurrency, ...CURRENCIES];

  return (
    <Stack gap="md" maw={720}>
      <Paper withBorder p="md">
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            <TextInput label={t('firma.name')} error={err('name')} {...register('name')} />
            <Group grow align="flex-start">
              <TextInput label={t('contractors.vat_id')} error={err('vatId')} {...register('vatId')} />
              <TextInput type="email" label={t('drivers.email')} error={err('email')} {...register('email')} />
            </Group>
            <Textarea label={t('contractors.address')} autosize minRows={2} maxRows={4} error={err('address')} {...register('address')} />
            <Group grow align="flex-start">
              <Controller
                name="lang"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('firma.lang')}
                    description={t('firma.lang_hint')}
                    data={LANGUAGES.map((l) => ({ value: l.code, label: l.name }))}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    allowDeselect={false}
                    searchable
                    error={err('lang')}
                  />
                )}
              />
              <Controller
                name="timezone"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('firma.timezone')}
                    description={t('firma.timezone_hint')}
                    data={zones}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    allowDeselect={false}
                    searchable
                    limit={50}
                    error={err('timezone')}
                  />
                )}
              />
              <Controller
                name="baseCurrency"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('firma.base_currency')}
                    description={t('firma.base_currency_hint')}
                    data={currencies}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    allowDeselect={false}
                    searchable
                    error={err('baseCurrency')}
                  />
                )}
              />
            </Group>
            <Controller
              name="driversSeePay"
              control={control}
              render={({ field }) => (
                <Switch label={t('firma.drivers_see_pay')} description={t('firma.drivers_see_pay_hint')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
              )}
            />
            <Group justify="flex-end">
              <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
                {t('common.save')}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
      <Paper withBorder p="md">
        <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={6}>
          {t('firma.billing')}
        </Text>
        <KeyValueList
          items={[
            { label: t('firma.short_code'), value: f.shortCode },
            { label: t('trips.country'), value: countryName(f.country, lang) },
            { label: t('firma.plan'), value: f.plan },
            { label: t('firma.vehicle_limit'), value: f.vehicleLimit ?? '—' },
            { label: t('firma.ai_access'), value: t(f.aiAccess ? 'firma.ai_on' : 'firma.ai_off') },
          ]}
        />
        <Text size="xs" c="dimmed" mt={6}>
          {t('firma.billing_hint')}
        </Text>
      </Paper>
    </Stack>
  );
}
