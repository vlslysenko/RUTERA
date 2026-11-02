import { useNavigate, useParams, useSearchParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, get, useFieldArray, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Button, Checkbox, Group, MultiSelect, NumberInput, Paper, SegmentedControl, Select, Stack, Text, Textarea, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import {
  DEBTS_KEY,
  financeApi,
  INVOICE_DIRECTIONS,
  INVOICES_KEY,
  lineAmounts,
  VAT_CODES,
  type InvoiceDetail,
  type InvoiceDirection,
  type InvoiceInput,
} from '@/api/finance';
import { CONTRACTORS_KEY, contractorsApi, ORDERS_KEY, ordersApi } from '@/api/trips';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { CURRENCIES, formatMoney } from '@/pages/trips/tripFormat';
import classes from '@/pages/trips/Trips.module.css';

const LIST = '/finance';
const MONEY = 999_999_999_999.99;

const lineSchema = z
  .object({
    description: z.string().trim().min(1, 'validation.required').max(500, 'validation.too_long'),
    qty: numberField(0.001, 99_999_999_999.999).refine((v) => v != null, 'validation.required'),
    unit: z.string().trim().max(16, 'validation.too_long'),
    unitPrice: numberField(-9_999_999_999.9999, 9_999_999_999.9999).refine((v) => v != null, 'validation.required'),
    vatCode: z.enum(VAT_CODES),
    vatRate: numberField(0, 999.99),
  })
  .superRefine((l, ctx) => {
    if (l.vatCode === 'RATE' && l.vatRate == null) ctx.addIssue({ code: 'custom', path: ['vatRate'], message: 'validation.required' });
  });

/* Same rules as InvoiceService: purchase invoices need the supplier's number; with lines the totals are computed,
 * without lines net and gross are required and net + VAT must equal gross. */
const schema = z
  .object({
    direction: z.enum(INVOICE_DIRECTIONS),
    number: z.string().trim().max(64, 'validation.too_long'),
    contractorId: z.string().nullable().refine((v) => !!v, 'validation.required'),
    issueDate: z.string().min(1, 'validation.required'),
    dueDate: z.string(),
    currency: z.string().nullable().refine((v) => !!v, 'validation.required'),
    lines: z.array(lineSchema).max(200, 'validation.too_long'),
    net: numberField(-MONEY, MONEY),
    vat: numberField(-MONEY, MONEY),
    gross: numberField(-MONEY, MONEY),
    orderIds: z.array(z.string()),
    remindersOn: z.boolean(),
    cancelled: z.boolean(),
    notes: z.string().max(2000, 'validation.too_long'),
  })
  .superRefine((v, ctx) => {
    if (v.direction === 'PURCHASE' && !v.number) ctx.addIssue({ code: 'custom', path: ['number'], message: 'validation.required' });
    if (v.dueDate && v.issueDate && v.dueDate < v.issueDate) ctx.addIssue({ code: 'custom', path: ['dueDate'], message: 'validation.invalid_value' });
    if (v.lines.length === 0) {
      if (v.net == null) ctx.addIssue({ code: 'custom', path: ['net'], message: 'validation.required' });
      if (v.gross == null) ctx.addIssue({ code: 'custom', path: ['gross'], message: 'validation.required' });
      if (v.net != null && v.gross != null && v.vat != null && Math.round((v.net + v.vat) * 100) !== Math.round(v.gross * 100)) {
        ctx.addIssue({ code: 'custom', path: ['gross'], message: 'validation.invalid_value' });
      }
    }
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
type LineForm = FormInput['lines'][number];

const FIELDS: readonly FieldPath<FormInput>[] = [
  'direction', 'number', 'contractorId', 'issueDate', 'dueDate', 'currency', 'lines', 'net', 'vat', 'gross', 'orderIds', 'remindersOn', 'cancelled', 'notes',
];
const LINE_FIELDS = ['description', 'qty', 'unit', 'unitPrice', 'vatCode', 'vatRate'] as const;

const today = () => new Date().toISOString().slice(0, 10);
const newLine = (vatCode: LineForm['vatCode'] = 'NP'): LineForm => ({ description: '', qty: 1, unit: '', unitPrice: null, vatCode, vatRate: vatCode === 'RATE' ? 23 : null });

function toFormValues(i: InvoiceDetail): FormInput {
  return {
    direction: i.direction,
    number: i.number,
    contractorId: String(i.contractor.id),
    issueDate: i.issueDate,
    dueDate: i.dueDate,
    currency: i.currency,
    lines: i.lines.map((l) => ({ description: l.description, qty: l.qty, unit: l.unit ?? '', unitPrice: l.unitPrice, vatCode: l.vatCode, vatRate: l.vatRate })),
    net: i.lines.length ? null : i.net,
    vat: i.lines.length ? null : i.vat,
    gross: i.lines.length ? null : i.gross,
    orderIds: i.orders.map((o) => String(o.id)),
    remindersOn: i.remindersOn,
    cancelled: i.status === 'CANCELLED',
    notes: i.notes ?? '',
  };
}

/** Create (/finance/new?direction=) or edit (/finance/:id/edit) an invoice: header, lines or plain totals, billed orders. */
export function InvoiceForm() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const [search] = useSearchParams();
  const isNew = params.id === undefined;
  const id = params.id ?? '';
  const backPath = isNew ? LIST : `${LIST}/${id}`;
  const startDirection: InvoiceDirection = search.get('direction') === 'PURCHASE' ? 'PURCHASE' : 'SALES';

  const q = useQuery({ queryKey: [...INVOICES_KEY, id], queryFn: () => financeApi.get(id), enabled: !isNew });
  const current = q.data;
  const contractors = useQuery({ queryKey: [...CONTRACTORS_KEY, 'list', '', 'all'], queryFn: () => contractorsApi.list() });

  const {
    control,
    register,
    handleSubmit,
    setError,
    setValue,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      direction: startDirection,
      number: '',
      contractorId: null,
      issueDate: today(),
      dueDate: '',
      currency: 'EUR',
      lines: [newLine()],
      net: null,
      vat: null,
      gross: null,
      orderIds: [],
      remindersOn: startDirection === 'SALES',
      cancelled: false,
      notes: '',
    },
    values: current ? toFormValues(current) : undefined,
  });
  const lines = useFieldArray({ control, name: 'lines' });
  const err = (name: FieldPath<FormInput>) => {
    const code = (get(errors, name) as { message?: string } | undefined)?.message;
    return code ? t(code) : undefined;
  };

  const direction = watch('direction');
  const contractorId = watch('contractorId');
  const currency = watch('currency');
  const watchedLines = watch('lines');
  // Orders of this customer that may still be billed: not cancelled, not invoiced (except the ones on this invoice).
  const orders = useQuery({
    queryKey: [...ORDERS_KEY, 'list', { customerId: contractorId, size: 200 }],
    queryFn: () => ordersApi.list({ customerId: Number(contractorId), size: 200 }),
    enabled: direction === 'SALES' && !!contractorId,
  });
  const linked = new Set((current?.orders ?? []).map((o) => String(o.id)));
  const orderOptions = (orders.data?.items ?? [])
    .filter((o) => linked.has(String(o.id)) || (o.status !== 'CANCELLED' && o.status !== 'INVOICED'))
    .map((o) => ({ value: String(o.id), label: `${o.number} · ${o.loading.place} → ${o.unloading.place}` }));

  const num = (v: number | string | null | undefined) => (v === null || v === undefined || v === '' ? null : Number(v));
  const totals = watchedLines.reduce(
    (s, l) => {
      const qty = num(l.qty);
      const price = num(l.unitPrice);
      if (qty == null || price == null) return s;
      const a = lineAmounts({ qty, unitPrice: price, vatCode: l.vatCode, vatRate: num(l.vatRate) });
      return { net: s.net + a.net, vat: s.vat + a.vat, gross: s.gross + a.gross };
    },
    { net: 0, vat: 0, gross: 0 },
  );
  const money = (v: number) => formatMoney(Math.round(v * 100) / 100, currency, lang);
  const currencies = currency && !CURRENCIES.includes(currency) ? [...CURRENCIES, currency] : CURRENCIES;

  const onSubmit = handleSubmit(async (f) => {
    const body: InvoiceInput = {
      direction: f.direction,
      number: f.number || null,
      contractorId: Number(f.contractorId),
      issueDate: f.issueDate,
      dueDate: f.dueDate || null,
      currency: f.currency ?? '',
      lines: f.lines.map((l) => ({
        description: l.description,
        qty: l.qty ?? 0,
        unit: l.unit || null,
        unitPrice: l.unitPrice ?? 0,
        vatCode: l.vatCode,
        vatRate: l.vatCode === 'RATE' ? l.vatRate : null,
      })),
      net: f.lines.length ? null : f.net,
      vat: f.lines.length ? null : f.vat,
      gross: f.lines.length ? null : f.gross,
      orderIds: f.direction === 'SALES' ? f.orderIds.map(Number) : [],
      tripId: current?.trip?.id ?? null,
      remindersOn: f.remindersOn,
      cancelled: f.cancelled,
      notes: f.notes.trim() || null,
    };
    try {
      const saved = isNew ? await financeApi.create(body) : await financeApi.update(id, body);
      qc.setQueryData([...INVOICES_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...INVOICES_KEY, 'list'] });
      void qc.invalidateQueries({ queryKey: DEBTS_KEY });
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
      notifications.show({ color: 'green', message: t(isNew ? 'invoices.created' : 'invoices.updated', { number: saved.number }) });
      await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      // Line errors come as lines[2].qty; the form names that field lines.2.qty.
      const aliases: Record<string, FieldPath<FormInput>> = {};
      const linePaths: FieldPath<FormInput>[] = [];
      f.lines.forEach((_, i) => {
        for (const field of LINE_FIELDS) {
          const path = `lines.${i}.${field}` as FieldPath<FormInput>;
          aliases[`lines[${i}].${field}`] = path;
          linePaths.push(path);
        }
      });
      // A reminder-related code can only concern the contractor.
      aliases.contractor = 'contractorId';
      if (!applyServerErrors(e, setError, [...FIELDS, ...linePaths], aliases)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const amount = (name: 'net' | 'vat' | 'gross', label: string) => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <NumberInput
          label={label}
          value={field.value ?? ''}
          onChange={(v) => field.onChange(toNumberInputValue(v))}
          decimalScale={2}
          decimalSeparator={decimalSeparator(lang)}
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
      title={isNew ? t(direction === 'SALES' ? 'invoices.new_sales' : 'invoices.new_purchase') : (current?.number ?? '…')}
      subtitle={!isNew && t('common.edit')}
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            {isNew && (
              <Controller
                name="direction"
                control={control}
                render={({ field }) => (
                  <SegmentedControl
                    value={field.value}
                    onChange={(v) => {
                      field.onChange(v);
                      setValue('remindersOn', v === 'SALES');
                      if (v === 'PURCHASE') setValue('orderIds', []);
                    }}
                    data={INVOICE_DIRECTIONS.map((d) => ({ value: d, label: t(`invoice.direction.${d}`) }))}
                    fullWidth
                  />
                )}
              />
            )}
            <Group grow align="flex-start">
              <TextInput
                label={t('orders.number')}
                placeholder={direction === 'SALES' ? t('invoices.number_auto') : undefined}
                classNames={{ input: classes.mono }}
                error={err('number')}
                {...register('number')}
              />
              <Controller
                name="contractorId"
                control={control}
                render={({ field }) => (
                  <Select
                    label={t('invoices.contractor')}
                    data={(contractors.data ?? []).map((c) => ({ value: String(c.id), label: c.name }))}
                    value={field.value}
                    onChange={(v) => {
                      field.onChange(v);
                      setValue('orderIds', []);
                    }}
                    searchable
                    nothingFoundMessage={t('fleet.nothing_found')}
                    error={err('contractorId')}
                  />
                )}
              />
            </Group>
            <Group grow align="flex-start">
              <TextInput type="date" label={t('invoices.issue_date')} error={err('issueDate')} {...register('issueDate')} />
              <TextInput type="date" label={t('invoices.due_date')} description={t('invoices.due_date_hint')} error={err('dueDate')} {...register('dueDate')} />
              <Controller
                name="currency"
                control={control}
                render={({ field }) => (
                  <Select label={t('orders.currency')} data={currencies} value={field.value} onChange={field.onChange} searchable error={err('currency')} />
                )}
              />
            </Group>

            <Group justify="space-between">
              <h3 className={classes.sectionTitle}>{t('invoices.lines')}</h3>
              <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => lines.append(newLine(lines.fields.at(-1)?.vatCode))} disabled={lines.fields.length >= 200}>
                {t('invoices.add_line')}
              </Button>
            </Group>
            {lines.fields.map((l, i) => {
              const code = watchedLines[i]?.vatCode;
              const qty = num(watchedLines[i]?.qty);
              const price = num(watchedLines[i]?.unitPrice);
              const a = qty != null && price != null ? lineAmounts({ qty, unitPrice: price, vatCode: code ?? 'NP', vatRate: num(watchedLines[i]?.vatRate) }) : null;
              return (
                <Paper key={l.id} withBorder p="xs">
                  <Stack gap={6}>
                    <Group gap={6} align="flex-start" wrap="nowrap">
                      <TextInput size="xs" label={t('invoices.line_description')} style={{ flex: 1 }} error={err(`lines.${i}.description`)} {...register(`lines.${i}.description`)} />
                      <Tooltip label={t('common.delete')}>
                        <ActionIcon variant="subtle" color="red" mt={22} onClick={() => lines.remove(i)} aria-label={t('common.delete')}>
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Group>
                    <Group grow gap={6} align="flex-start">
                      <Controller
                        name={`lines.${i}.qty`}
                        control={control}
                        render={({ field }) => (
                          <NumberInput
                            size="xs"
                            label={t('invoices.qty')}
                            value={field.value ?? ''}
                            onChange={(v) => field.onChange(toNumberInputValue(v))}
                            decimalScale={3}
                            decimalSeparator={decimalSeparator(lang)}
                            allowNegative={false}
                            hideControls
                            error={err(`lines.${i}.qty`)}
                          />
                        )}
                      />
                      <TextInput size="xs" label={t('invoices.unit')} error={err(`lines.${i}.unit`)} {...register(`lines.${i}.unit`)} />
                      <Controller
                        name={`lines.${i}.unitPrice`}
                        control={control}
                        render={({ field }) => (
                          <NumberInput
                            size="xs"
                            label={t('invoices.unit_price')}
                            value={field.value ?? ''}
                            onChange={(v) => field.onChange(toNumberInputValue(v))}
                            decimalScale={4}
                            decimalSeparator={decimalSeparator(lang)}
                            hideControls
                            error={err(`lines.${i}.unitPrice`)}
                          />
                        )}
                      />
                    </Group>
                    <Group grow gap={6} align="flex-start">
                      <Controller
                        name={`lines.${i}.vatCode`}
                        control={control}
                        render={({ field }) => (
                          <Select
                            size="xs"
                            label={t('invoices.vat')}
                            data={VAT_CODES.map((c) => ({ value: c, label: t(`vat.code.${c}`) }))}
                            value={field.value}
                            onChange={(v) => {
                              if (!v) return;
                              field.onChange(v);
                              setValue(`lines.${i}.vatRate`, v === 'RATE' ? 23 : null);
                            }}
                            allowDeselect={false}
                            error={err(`lines.${i}.vatCode`)}
                          />
                        )}
                      />
                      {code === 'RATE' ? (
                        <Controller
                          name={`lines.${i}.vatRate`}
                          control={control}
                          render={({ field }) => (
                            <NumberInput
                              size="xs"
                              label={t('invoices.vat_percent')}
                              value={field.value ?? ''}
                              onChange={(v) => field.onChange(toNumberInputValue(v))}
                              decimalScale={2}
                              decimalSeparator={decimalSeparator(lang)}
                              allowNegative={false}
                              hideControls
                              error={err(`lines.${i}.vatRate`)}
                            />
                          )}
                        />
                      ) : (
                        <span />
                      )}
                      <Stack gap={0} align="flex-end" pt={22}>
                        <Text size="sm" fw={600} className={classes.num}>
                          {a ? money(a.gross) : '—'}
                        </Text>
                      </Stack>
                    </Group>
                  </Stack>
                </Paper>
              );
            })}
            {lines.fields.length > 0 ? (
              <Group justify="flex-end" gap="md">
                <Text size="sm" c="dimmed">
                  {t('invoices.net')}: {money(totals.net)}
                </Text>
                <Text size="sm" c="dimmed">
                  {t('invoices.vat')}: {money(totals.vat)}
                </Text>
                <Text size="sm" fw={600}>
                  {t('invoices.gross')}: {money(totals.gross)}
                </Text>
              </Group>
            ) : (
              <>
                <Text size="xs" c="dimmed">
                  {t('invoices.no_lines_hint')}
                </Text>
                <Group grow align="flex-start">
                  {amount('net', t('invoices.net'))}
                  {amount('vat', t('invoices.vat'))}
                  {amount('gross', t('invoices.gross'))}
                </Group>
              </>
            )}

            {direction === 'SALES' && (
              <Controller
                name="orderIds"
                control={control}
                render={({ field }) => (
                  <MultiSelect
                    label={t('trips.tab.orders')}
                    description={t(contractorId ? 'invoices.orders_hint' : 'invoices.orders_pick_contractor')}
                    data={orderOptions}
                    value={field.value}
                    onChange={field.onChange}
                    disabled={!contractorId}
                    searchable
                    clearable
                    nothingFoundMessage={t('fleet.nothing_found')}
                    error={err('orderIds')}
                  />
                )}
              />
            )}
            {direction === 'SALES' && (
              <Controller
                name="remindersOn"
                control={control}
                render={({ field }) => (
                  <Checkbox label={t('invoices.reminders_on')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )}
              />
            )}
            <Textarea label={t('drivers.notes')} autosize minRows={2} maxRows={6} error={err('notes')} {...register('notes')} />
            {!isNew && (
              <Controller
                name="cancelled"
                control={control}
                render={({ field }) => (
                  <Checkbox label={t('invoices.cancelled')} description={t('invoices.cancel_hint')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )}
              />
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
