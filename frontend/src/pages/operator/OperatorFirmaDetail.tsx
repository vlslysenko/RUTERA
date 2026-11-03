import { Link, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Anchor, Button, Group, NumberInput, Stack, Switch, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { expiresAtOf, expiresDay, OPERATOR_KEY, operatorApi, type OperatorFirma, type OperatorFirmaInput } from '@/api/operator';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { FormDateField } from '@/forms/DateField';
import { numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { countryName } from '@/utils/countries';
import { FirmaStateChips } from './OperatorChips';
import classes from './Operator.module.css';

// Same rules as OperatorService: plan letters, digits and _ up to 32 (upper-cased), vehicle limit 0..100000.
const schema = z.object({
  plan: z
    .string()
    .trim()
    .min(1, 'validation.required')
    .max(32, 'validation.too_long')
    .regex(/^[A-Za-z0-9_]+$/, 'validation.invalid_value'),
  vehicleLimit: numberField(0, 100_000, { integer: true }).refine((v) => v !== null, 'validation.required'),
  aiAccess: z.boolean(),
  active: z.boolean(),
  /** "YYYY-MM-DD" or "" (no end). */
  expiresOn: z.string(),
});

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
const FIELDS: readonly FieldPath<FormInput>[] = ['plan', 'vehicleLimit', 'aiAccess', 'active', 'expiresOn'];

const toForm = (f: OperatorFirma): FormInput => ({
  plan: f.plan,
  vehicleLimit: f.vehicleLimit,
  aiAccess: f.aiAccess,
  active: f.active,
  expiresOn: expiresDay(f.expiresAt) ?? '',
});

/** Card of a firma in the operator console: counts and the paid options (plan, vehicle limit, AI access, access). */
export function OperatorFirmaDetail({ mode = 'drawer' }: { mode?: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: [...OPERATOR_KEY, id], queryFn: () => operatorApi.get(id) });
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({ resolver: zodResolver(schema), values: q.data ? toForm(q.data) : undefined });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (v) => {
    const body: OperatorFirmaInput = {
      plan: v.plan.trim().toUpperCase(),
      vehicleLimit: v.vehicleLimit ?? 0,
      aiAccess: v.aiAccess,
      active: v.active,
      expiresAt: expiresAtOf(v.expiresOn),
    };
    try {
      qc.setQueryData([...OPERATOR_KEY, id], await operatorApi.update(id, body));
      void qc.invalidateQueries({ queryKey: [...OPERATOR_KEY, 'list'] });
      notifications.show({ color: 'green', message: t('common.saved') });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS, { expiresAt: 'expiresOn' })) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const f = q.data;
  return (
    <DetailDrawer
      mode={mode}
      title={f?.name ?? '…'}
      subtitle={f ? `${f.shortCode} · ${countryName(f.country, lang)}` : undefined}
      status={f && <FirmaStateChips firma={f} />}
      listPath="/operator"
      pagePath={`/operator/${id}/page`}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        f && (
          <Stack gap="lg">
            <KeyValueList
              items={[
                { label: t('firma.short_code'), value: <span className={classes.mono}>{f.shortCode}</span> },
                { label: t('operator.col.users'), value: f.users },
                {
                  label: t('operator.col.vehicles'),
                  value: (
                    <span className={f.vehicles > f.vehicleLimit ? classes.over : undefined}>
                      {f.vehicles} / {f.vehicleLimit}
                    </span>
                  ),
                },
                { label: t('operator.created'), value: formatInstantShort(f.createdAt, lang) },
              ]}
            />
            <Anchor component={Link} to={`/operator/audit?firma=${f.id}`} size="sm">
              {t('settings.tab.audit')}
            </Anchor>
            <form onSubmit={(e) => void onSubmit(e)} noValidate>
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
                    <Switch
                      label={t('firma.ai_access')}
                      description={t('operator.ai_hint')}
                      checked={field.value}
                      onChange={(e) => field.onChange(e.currentTarget.checked)}
                    />
                  )}
                />
                <Controller
                  name="active"
                  control={control}
                  render={({ field }) => (
                    <Switch
                      label={t('operator.active')}
                      description={t('operator.active_hint')}
                      checked={field.value}
                      onChange={(e) => field.onChange(e.currentTarget.checked)}
                      color="green"
                    />
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
                <Group justify="flex-end">
                  <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
                    {t('common.save')}
                  </Button>
                </Group>
              </section>
            </form>
          </Stack>
        )
      )}
    </DetailDrawer>
  );
}
