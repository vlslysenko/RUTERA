import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Checkbox, Group, NumberInput, Select, Stack, TagsInput, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { CONTRACTORS_KEY, contractorsApi, type Contractor, type ContractorInput } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { LANGUAGES } from '@/i18n/languages';
import { countryOptions } from '@/utils/countries';
import { LabelHint } from '@/components/LabelHint';
import classes from './Trips.module.css';

const LIST = '/trips/contractors';
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/* Same rules as ContractorService: name and country required, up to 5 invoice addresses, payment 0..365 days. */
const schema = z
  .object({
    name: z.string().trim().min(1, 'validation.required').max(200, 'validation.too_long'),
    vatId: z.string().trim().max(40, 'validation.too_long'),
    country: z.string().nullable().refine((v) => !!v, 'validation.required'),
    address: z.string().trim().max(500, 'validation.too_long'),
    phone: z.string().trim().max(40, 'validation.too_long'),
    invoiceEmails: z
      .array(z.string())
      .max(5, 'validation.too_long')
      .refine((a) => a.every((e) => EMAIL_RE.test(e)), 'validation.invalid_format'),
    paymentDays: numberField(0, 365, { integer: true }),
    lang: z.string().nullable(),
    isCustomer: z.boolean(),
    isCarrier: z.boolean(),
    notes: z.string().max(2000, 'validation.too_long'),
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;

const FIELDS: readonly FieldPath<FormInput>[] = [
  'name', 'vatId', 'country', 'address', 'phone', 'invoiceEmails', 'paymentDays', 'lang', 'isCustomer', 'isCarrier', 'notes',
];

const EMPTY: FormInput = {
  name: '',
  vatId: '',
  country: 'PL',
  address: '',
  phone: '',
  invoiceEmails: [],
  paymentDays: 30,
  lang: null,
  isCustomer: true,
  isCarrier: false,
  notes: '',
};

function toFormValues(c: Contractor): FormInput {
  return {
    name: c.name,
    vatId: c.vatId ?? '',
    country: c.country,
    address: c.address ?? '',
    phone: c.phone ?? '',
    invoiceEmails: c.invoiceEmails,
    paymentDays: c.paymentDays,
    lang: c.lang,
    isCustomer: c.isCustomer,
    isCarrier: c.isCarrier,
    notes: c.notes ?? '',
  };
}

const blank = (s: string) => s.trim() || null;

function toInput(f: FormValues): ContractorInput {
  return {
    name: f.name.trim(),
    vatId: blank(f.vatId),
    country: f.country ?? '',
    address: blank(f.address),
    phone: blank(f.phone),
    invoiceEmails: f.invoiceEmails.map((e) => e.trim()).filter(Boolean),
    paymentDays: f.paymentDays,
    lang: f.lang,
    isCustomer: f.isCustomer,
    isCarrier: f.isCarrier,
    notes: blank(f.notes),
  };
}

/** Contractor card = its form (read-only without trip.contractor/manage); also creates one (/trips/contractors/new). */
export function ContractorDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined || params.id === 'new';
  const id = isNew ? '' : (params.id ?? '');
  const canManage = hasPrivilege(me, PRIV.TRIP_CONTRACTOR_MANAGE);
  const readOnly = !canManage;

  const q = useQuery({ queryKey: [...CONTRACTORS_KEY, id], queryFn: () => contractorsApi.get(id), enabled: !isNew });
  const c = q.data;
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
    values: c ? toFormValues(c) : undefined,
  });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    return code ? t(code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      const saved = isNew ? await contractorsApi.create(body) : await contractorsApi.update(id, body);
      qc.setQueryData([...CONTRACTORS_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...CONTRACTORS_KEY, 'list'] });
      notifications.show({ color: 'green', message: t(isNew ? 'contractors.created' : 'contractors.updated') });
      if (isNew) await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const remove = async () => {
    setBusy(true);
    try {
      await contractorsApi.remove(id);
      notifications.show({ color: 'green', message: t('contractors.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: [...CONTRACTORS_KEY, 'list'] });
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
      title={isNew ? t('contractors.new') : (c?.name ?? '…')}
      subtitle={c?.vatId && <span className={classes.mono}>{c.vatId}</span>}
      actions={
        !isNew && c && canManage ? (
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
                <Text size="sm">{t('contractors.delete_confirm')}</Text>
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
              <TextInput label={t('contractors.name')} readOnly={readOnly} autoFocus={isNew} error={err('name')} {...register('name')} />
              <Group grow align="flex-start">
                <TextInput
                  label={t('contractors.vat_id')}
                  readOnly={readOnly}
                  classNames={{ input: classes.mono }}
                  error={err('vatId')}
                  {...register('vatId')}
                />
                <Controller
                  name="country"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={t('trips.country')}
                      data={countryOptions(lang, field.value)}
                      value={field.value}
                      onChange={field.onChange}
                      searchable
                      disabled={readOnly}
                      error={err('country')}
                    />
                  )}
                />
              </Group>
              <Textarea label={t('contractors.address')} readOnly={readOnly} autosize minRows={2} error={err('address')} {...register('address')} />
              <Group grow align="flex-start">
                <TextInput label={t('drivers.phone')} type="tel" readOnly={readOnly} error={err('phone')} {...register('phone')} />
                <Controller
                  name="paymentDays"
                  control={control}
                  render={({ field }) => (
                    <NumberInput
                      label={<LabelHint label={t('contractors.payment_days')} hint={t('hint.field.payment_days')} />}
                      value={field.value ?? ''}
                      onChange={(v) => field.onChange(toNumberInputValue(v))}
                      allowNegative={false}
                      allowDecimal={false}
                      hideControls
                      readOnly={readOnly}
                      error={err('paymentDays')}
                    />
                  )}
                />
              </Group>
              <Controller
                name="invoiceEmails"
                control={control}
                render={({ field }) => (
                  <TagsInput
                    label={t('contractors.invoice_emails')}
                    description={t('contractors.invoice_emails_hint')}
                    value={field.value}
                    onChange={field.onChange}
                    maxTags={5}
                    splitChars={[',', ' ', ';']}
                    disabled={readOnly}
                    error={err('invoiceEmails')}
                  />
                )}
              />
              <Group grow align="flex-start">
                <Controller
                  name="lang"
                  control={control}
                  render={({ field }) => (
                    <Select
                      label={<LabelHint label={t('contractors.lang')} hint={t('hint.field.contractor_lang')} />}
                      data={LANGUAGES.map((l) => ({ value: l.code, label: l.name }))}
                      value={field.value}
                      onChange={field.onChange}
                      clearable
                      searchable
                      disabled={readOnly}
                      error={err('lang')}
                    />
                  )}
                />
                <Stack gap={6} pt={26}>
                  <Controller
                    name="isCustomer"
                    control={control}
                    render={({ field }) => (
                      <Checkbox
                        label={<LabelHint label={t('contractors.customer')} hint={t('hint.contractor.customer')} />}
                        checked={field.value}
                        onChange={(e) => field.onChange(e.currentTarget.checked)}
                        disabled={readOnly}
                      />
                    )}
                  />
                  <Controller
                    name="isCarrier"
                    control={control}
                    render={({ field }) => (
                      <Checkbox
                        label={<LabelHint label={t('contractors.carrier')} hint={t('hint.contractor.carrier')} />}
                        checked={field.value}
                        onChange={(e) => field.onChange(e.currentTarget.checked)}
                        disabled={readOnly}
                      />
                    )}
                  />
                </Stack>
              </Group>
              <Textarea label={t('drivers.notes')} readOnly={readOnly} autosize minRows={2} maxRows={6} error={err('notes')} {...register('notes')} />
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
