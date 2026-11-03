import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, CopyButton, Group, NumberInput, PasswordInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconCopy } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { expiresAtOf, OPERATOR_KEY, operatorApi, type OperatorFirmaCreated } from '@/api/operator';
import { DetailDrawer } from '@/components/DetailDrawer';
import { FormDateField } from '@/forms/DateField';
import { numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { isLanguageCode, LANGUAGES } from '@/i18n/languages';
import { PASSWORD_MAX, PASSWORD_MIN } from '@/pages/settings/PasswordTab';
import { CURRENCIES } from '@/pages/trips/tripFormat';
import { countryOptions } from '@/utils/countries';
import { TIME_ZONES } from '@/utils/zone';
import classes from './Operator.module.css';

const LIST_PATH = '/operator';

// Same rules as the backend (2d16fdc): short code 2..32 of letters, digits, - and _ (upper-cased); login as for users.
const schema = z.object({
  name: z.string().trim().min(1, 'validation.required').max(200, 'validation.too_long'),
  shortCode: z
    .string()
    .trim()
    .min(1, 'validation.required')
    .regex(/^[A-Za-z0-9_-]{2,32}$/, 'validation.invalid_format'),
  country: z.string().min(1, 'validation.required'),
  lang: z.string().refine((v): boolean => isLanguageCode(v), 'validation.invalid_value'),
  timezone: z.string().min(1, 'validation.required'),
  baseCurrency: z.string().regex(/^[A-Za-z]{3}$/, 'validation.invalid_format'),
  plan: z
    .string()
    .trim()
    .min(1, 'validation.required')
    .max(32, 'validation.too_long')
    .regex(/^[A-Za-z0-9_]+$/, 'validation.invalid_value'),
  vehicleLimit: numberField(0, 100_000, { integer: true }).refine((v) => v !== null, 'validation.required'),
  aiAccess: z.boolean(),
  test: z.boolean(),
  expiresOn: z.string(),
  ownerLogin: z
    .string()
    .trim()
    .min(3, 'validation.too_short')
    .max(254, 'validation.too_long')
    .regex(/^[\p{L}\p{N}._@+-]+$/u, 'validation.invalid_format'),
  ownerFirstName: z.string().trim().min(1, 'validation.required').max(100, 'validation.too_long'),
  ownerLastName: z.string().trim().max(100, 'validation.too_long'),
  /** Empty: the backend generates one and returns it once. */
  ownerPassword: z
    .string()
    .max(PASSWORD_MAX, 'validation.too_long')
    .refine((v) => v === '' || v.length >= PASSWORD_MIN, 'validation.too_short'),
});

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
const FIELDS: readonly FieldPath<FormInput>[] = [
  'name', 'shortCode', 'country', 'lang', 'timezone', 'baseCurrency', 'plan', 'vehicleLimit', 'aiAccess', 'test', 'expiresOn',
  'ownerLogin', 'ownerFirstName', 'ownerLastName', 'ownerPassword',
];
const ALIASES: Partial<Record<string, FieldPath<FormInput>>> = {
  expiresAt: 'expiresOn',
  'owner.login': 'ownerLogin',
  'owner.firstName': 'ownerFirstName',
  'owner.lastName': 'ownerLastName',
  'owner.password': 'ownerPassword',
};

const browserZone = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
})();

/** The result of the creation: the owner's login and, when generated, the password shown once with a copy button. */
function Created({ created, onClose }: { created: OperatorFirmaCreated; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <Alert color="green" variant="light" title={t('operator.created_title', { name: created.firma.name })}>
      <Stack gap="sm">
        <Text size="sm">
          {t('users.login')}: <span className={classes.mono}>{created.ownerLogin}</span>
        </Text>
        {created.password && (
          <>
            <Text size="sm">{t('operator.password_once')}</Text>
            <code className={classes.secret}>{created.password}</code>
            <CopyButton value={created.password}>
              {({ copied, copy }) => (
                <Button
                  size="xs"
                  w="fit-content"
                  variant={copied ? 'filled' : 'light'}
                  color={copied ? 'teal' : 'blue'}
                  leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  onClick={copy}
                >
                  {t(copied ? 'tokens.copied' : 'tokens.copy')}
                </Button>
              )}
            </CopyButton>
          </>
        )}
        <Group gap="xs">
          <Button size="xs" onClick={() => void navigate(`${LIST_PATH}/${created.firma.id}`)}>
            {t('operator.open')}
          </Button>
          <Button size="xs" variant="default" onClick={onClose}>
            {t('common.close')}
          </Button>
        </Group>
      </Stack>
    </Alert>
  );
}

/** New firma with its first owner (/operator/new); the backend defaults are prefilled, language and zone from the browser. */
export function OperatorFirmaCreate() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [created, setCreated] = useState<OperatorFirmaCreated | null>(null);
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '',
      shortCode: '',
      country: '',
      lang: isLanguageCode(lang) ? lang : 'en',
      timezone: TIME_ZONES.includes(browserZone) ? browserZone : 'UTC',
      baseCurrency: 'EUR',
      plan: 'STANDARD',
      vehicleLimit: 10,
      aiAccess: false,
      test: false,
      expiresOn: '',
      ownerLogin: '',
      ownerFirstName: '',
      ownerLastName: '',
      ownerPassword: '',
    },
  });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };
  const close = () => void navigate(LIST_PATH);

  const onSubmit = handleSubmit(async (v) => {
    try {
      const result = await operatorApi.create({
        name: v.name.trim(),
        shortCode: v.shortCode.trim().toUpperCase(),
        country: v.country,
        lang: v.lang,
        timezone: v.timezone,
        baseCurrency: v.baseCurrency.toUpperCase(),
        plan: v.plan.trim().toUpperCase(),
        vehicleLimit: v.vehicleLimit ?? 10,
        aiAccess: v.aiAccess,
        test: v.test,
        expiresAt: expiresAtOf(v.expiresOn),
        owner: {
          login: v.ownerLogin.trim(),
          firstName: v.ownerFirstName.trim(),
          lastName: v.ownerLastName.trim() || null,
          password: v.ownerPassword || null,
        },
      });
      void qc.invalidateQueries({ queryKey: [...OPERATOR_KEY, 'list'] });
      setCreated(result);
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS, ALIASES)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const select = (name: 'country' | 'lang' | 'timezone' | 'baseCurrency', label: string, data: { value: string; label: string }[] | string[], limit?: number) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <Select
          label={label}
          data={data}
          value={field.value || null}
          onChange={(v) => field.onChange(v ?? '')}
          searchable
          allowDeselect={false}
          limit={limit}
          error={err(name)}
        />
      )}
    />
  );

  return (
    <DetailDrawer title={t('operator.add')} listPath={LIST_PATH}>
      {created ? (
        <Created created={created} onClose={close} />
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="lg">
            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('operator.col.company')}</h3>
              <TextInput label={t('firma.name')} autoComplete="off" error={err('name')} {...register('name')} />
              <Group grow align="flex-start">
                <TextInput
                  label={t('firma.short_code')}
                  description={t('operator.short_code_hint')}
                  classNames={{ input: classes.mono }}
                  autoComplete="off"
                  error={err('shortCode')}
                  {...register('shortCode')}
                />
                {select('country', t('trips.country'), countryOptions(lang))}
              </Group>
              <Group grow align="flex-start">
                {select('lang', t('firma.lang'), LANGUAGES.map((l) => ({ value: l.code, label: l.name })))}
                {select('timezone', t('firma.timezone'), TIME_ZONES, 50)}
                {select('baseCurrency', t('firma.base_currency'), CURRENCIES)}
              </Group>
            </section>

            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('operator.options')}</h3>
              <Group grow align="flex-start">
                <TextInput
                  label={t('firma.plan')}
                  description={t('operator.plan_hint')}
                  classNames={{ input: classes.mono }}
                  autoComplete="off"
                  error={err('plan')}
                  {...register('plan')}
                />
                <Controller
                  name="vehicleLimit"
                  control={control}
                  render={({ field }) => (
                    <NumberInput
                      label={t('firma.vehicle_limit')}
                      description={t('operator.vehicle_limit_hint')}
                      value={field.value ?? ''}
                      onChange={(v) => field.onChange(toNumberInputValue(v))}
                      onBlur={field.onBlur}
                      allowNegative={false}
                      allowDecimal={false}
                      hideControls
                      error={err('vehicleLimit')}
                    />
                  )}
                />
              </Group>
              <Controller
                name="aiAccess"
                control={control}
                render={({ field }) => (
                  <Switch label={t('firma.ai_access')} description={t('operator.ai_hint')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )}
              />
              <Controller
                name="test"
                control={control}
                render={({ field }) => (
                  <Switch label={t('operator.test_company')} description={t('operator.test_hint')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )}
              />
              <FormDateField
                control={control}
                name="expiresOn"
                label={t('operator.expires')}
                description={t('operator.expires_hint')}
                error={err('expiresOn')}
                maw={260}
              />
            </section>

            <section className={classes.section}>
              <h3 className={classes.sectionTitle}>{t('operator.owner')}</h3>
              <Text size="xs" c="dimmed">
                {t('operator.owner_hint')}
              </Text>
              <TextInput label={t('users.login')} autoComplete="off" classNames={{ input: classes.mono }} error={err('ownerLogin')} {...register('ownerLogin')} />
              <Group grow align="flex-start">
                <TextInput label={t('users.first_name')} autoComplete="off" error={err('ownerFirstName')} {...register('ownerFirstName')} />
                <TextInput label={t('users.last_name')} autoComplete="off" error={err('ownerLastName')} {...register('ownerLastName')} />
              </Group>
              <PasswordInput
                label={t('users.password')}
                description={t('operator.password_hint')}
                autoComplete="new-password"
                error={err('ownerPassword')}
                {...register('ownerPassword')}
              />
            </section>

            <Group justify="flex-end" gap="xs">
              <Button variant="default" onClick={close} disabled={isSubmitting}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={isSubmitting}>
                {t('common.create')}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </DetailDrawer>
  );
}
