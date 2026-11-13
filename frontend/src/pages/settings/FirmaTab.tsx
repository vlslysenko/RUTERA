import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, Paper, Select, SimpleGrid, Stack, Switch, Text, Textarea, TextInput, UnstyledButton } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronRight, IconCreditCard, IconGasStation, IconHistory, IconKey, IconMailForward, IconRoad, IconUsers } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { FIRMA_KEY, firmaApi, type FirmaSettings, type FirmaSettingsInput } from '@/api/admin';
import { errorCode } from '@/api/errors';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { ME_QUERY_KEY, useUser } from '@/auth/useAuth';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { KeyValueList } from '@/components/KeyValueList';
import { LabelHint } from '@/components/LabelHint';
import { applyServerErrors } from '@/forms/serverErrors';
import { LANGUAGES } from '@/i18n/languages';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { countryName } from '@/utils/countries';
import { TIME_ZONES as ZONES } from '@/utils/zone';
import classes from './FirmaTab.module.css';

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

/** A card that leads to another firma-level setting: icon, title and what it is for. */
function SettingLink({ to, icon, title, text }: { to: string; icon: ReactNode; title: string; text: string }) {
  return (
    <UnstyledButton component={Link} to={to} className={classes.link}>
      <span className={classes.linkIcon}>{icon}</span>
      <span className={classes.linkBody}>
        <Text size="sm" fw={600}>
          {title}
        </Text>
        <Text size="xs" c="dimmed">
          {text}
        </Text>
      </span>
      <IconChevronRight size={16} className={classes.chevron} />
    </UnstyledButton>
  );
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <Text size="xs" fw={600} tt="uppercase" c="dimmed" mb={8}>
      {children}
    </Text>
  );
}

/**
 * Settings -> Company (admin.firma/manage), the one place for the firma: its details and country of registration,
 * language, time zone and base currency, what drivers see, the plan, and links to every other firma-level setting
 * (users, API tokens, event log, payment reminders, toll tariffs, fuel prices, fuel cards) the user may open.
 */
export function FirmaTab() {
  const { t, i18n } = useTranslation();
  const user = useUser();
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

  const icon = { size: 20, stroke: 1.6 };
  const links = [
    hasPrivilege(user, PRIV.ADMIN_USER_VIEW) && { to: '/settings/users', icon: <IconUsers {...icon} />, key: 'users' },
    hasPrivilege(user, PRIV.ADMIN_API_TOKEN_MANAGE) && { to: '/settings/api-tokens', icon: <IconKey {...icon} />, key: 'api_tokens' },
    hasPrivilege(user, PRIV.ADMIN_AUDIT_VIEW) && { to: '/settings/audit', icon: <IconHistory {...icon} />, key: 'audit' },
    hasPrivilege(user, PRIV.FINANCE_REMINDER_MANAGE) && hasPrivilege(user, PRIV.FINANCE_INVOICE_VIEW) && { to: '/finance/reminders', icon: <IconMailForward {...icon} />, key: 'reminders' },
    hasPrivilege(user, PRIV.TRIP_TRIP_VIEW) && { to: '/trips/tolls', icon: <IconRoad {...icon} />, key: 'tolls' },
    hasPrivilege(user, PRIV.TRIP_TRIP_VIEW) && { to: '/trips/fuel-prices', icon: <IconGasStation {...icon} />, key: 'fuel_prices' },
    hasPrivilege(user, PRIV.FUEL_TRANSACTION_VIEW) && { to: '/fuel/cards', icon: <IconCreditCard {...icon} />, key: 'fuel_cards' },
  ].filter((l): l is { to: string; icon: React.JSX.Element; key: string } => Boolean(l));

  return (
    <Stack gap="md" maw={880}>
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="md">
          <Paper withBorder p="md">
            <SectionTitle>{t('firma.section.company')}</SectionTitle>
            <Stack gap="sm">
              <TextInput label={t('firma.name')} error={err('name')} {...register('name')} />
              <Group grow align="flex-start">
                <TextInput label={t('contractors.vat_id')} error={err('vatId')} {...register('vatId')} />
                <TextInput type="email" label={t('drivers.email')} error={err('email')} {...register('email')} />
              </Group>
              <Textarea label={t('contractors.address')} autosize minRows={2} maxRows={4} error={err('address')} {...register('address')} />
              <KeyValueList
                items={[
                  { label: <LabelHint label={t('firma.country')} hint={t('hint.firma.country')} />, value: countryName(f.country, lang) },
                  { label: t('firma.short_code'), value: f.shortCode },
                ]}
              />
            </Stack>
          </Paper>
          <Paper withBorder p="md">
            <SectionTitle>{t('firma.section.regional')}</SectionTitle>
            <Group grow align="flex-start" className={classes.wrap}>
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
          </Paper>
          <Paper withBorder p="md">
            <SectionTitle>{t('firma.section.drivers')}</SectionTitle>
            <Controller
              name="driversSeePay"
              control={control}
              render={({ field }) => (
                <Switch label={t('firma.drivers_see_pay')} description={t('firma.drivers_see_pay_hint')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
              )}
            />
          </Paper>
          <Group justify="flex-end">
            <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>

      <Paper withBorder p="md">
        <SectionTitle>{t('firma.billing')}</SectionTitle>
        <KeyValueList
          items={[
            { label: t('firma.plan'), value: f.plan },
            { label: t('firma.vehicle_limit'), value: f.vehicleLimit ?? '—' },
            { label: t('firma.ai_access'), value: t(f.aiAccess ? 'firma.ai_on' : 'firma.ai_off') },
          ]}
        />
        <Text size="xs" c="dimmed" mt={6}>
          {t('firma.billing_hint')}
        </Text>
      </Paper>

      {links.length > 0 && (
        <Paper withBorder p="md">
          <SectionTitle>{t('firma.more')}</SectionTitle>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            {links.map((l) => (
              <SettingLink key={l.key} to={l.to} icon={l.icon} title={t(`firma.link.${l.key}`)} text={t(`firma.link.${l.key}_desc`)} />
            ))}
          </SimpleGrid>
        </Paper>
      )}
    </Stack>
  );
}
