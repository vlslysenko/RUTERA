import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Alert, Anchor, Button, Group, NumberInput, Paper, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBell, IconCash, IconPencil, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { DEBTS_KEY, financeApi, INVOICES_KEY, PAYMENT_METHODS, type InvoiceDetail as Detail, type InvoicePaymentInput } from '@/api/finance';
import { ORDERS_KEY } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { formatInstantShort, formatMoney } from '@/pages/trips/tripFormat';
import { formatDate } from '@/utils/deadline';
import { FormDateField } from '@/forms/DateField';
import { InvoiceStatusChip, ReminderStatusChip } from './FinanceChips';
import { reminderStepLabel, vatLabel } from './financeFormat';
import classes from '@/pages/trips/Trips.module.css';

const LIST = '/finance';
const DASH = '—';

function Section({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className={classes.section}>
      <Group justify="space-between">
        <h3 className={classes.sectionTitle}>{title}</h3>
        {actions}
      </Group>
      {children}
    </section>
  );
}

const today = () => new Date().toISOString().slice(0, 10);

function PaymentForm({ invoice, onDone }: { invoice: Detail; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const schema = z.object({
    paidAt: z.string().min(1, 'validation.required'),
    amount: numberField(0.01, invoice.openAmount).refine((v) => v != null, 'validation.required'),
    method: z.enum(PAYMENT_METHODS),
    reference: z.string().trim().max(128, 'validation.too_long'),
    note: z.string().trim().max(500, 'validation.too_long'),
  });
  type FormInput = z.input<typeof schema>;
  type FormValues = z.output<typeof schema>;
  const FIELDS: readonly FieldPath<FormInput>[] = ['paidAt', 'amount', 'method', 'reference', 'note'];
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { paidAt: today(), amount: invoice.openAmount, method: 'TRANSFER', reference: '', note: '' },
  });
  const err = (name: keyof FormInput) => {
    const code = errors[name]?.message;
    // Above the open amount: the same message the backend gives.
    return code ? t(name === 'amount' && code === 'validation.too_long' ? 'finance.payment.too_much' : code) : undefined;
  };

  const onSubmit = handleSubmit(async (f) => {
    const body: InvoicePaymentInput = { paidAt: f.paidAt, amount: f.amount ?? 0, method: f.method, reference: f.reference || null, note: f.note || null };
    try {
      await financeApi.addPayment(invoice.id, body);
      await qc.invalidateQueries({ queryKey: INVOICES_KEY });
      void qc.invalidateQueries({ queryKey: DEBTS_KEY });
      notifications.show({ color: 'green', message: t('invoices.payment_added') });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  return (
    <Paper withBorder p="sm">
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="sm">
          <Group grow align="flex-start">
            <FormDateField label={t('payments.paid_at')} error={err('paidAt')} control={control} name="paidAt" />
            <Controller
              name="amount"
              control={control}
              render={({ field }) => (
                <NumberInput
                  label={`${t('payments.amount')}, ${invoice.currency}`}
                  value={field.value ?? ''}
                  onChange={(v) => field.onChange(toNumberInputValue(v))}
                  decimalScale={2}
                  decimalSeparator={decimalSeparator(lang)}
                  allowNegative={false}
                  hideControls
                  error={err('amount')}
                />
              )}
            />
          </Group>
          <Group grow align="flex-start">
            <Controller
              name="method"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('invoices.method')}
                  data={PAYMENT_METHODS.map((m) => ({ value: m, label: t(`payment.method.${m}`) }))}
                  value={field.value}
                  onChange={(v) => v && field.onChange(v)}
                  allowDeselect={false}
                  error={err('method')}
                />
              )}
            />
            <TextInput label={t('invoices.reference')} error={err('reference')} {...register('reference')} />
          </Group>
          <TextInput label={t('drivers.notes')} error={err('note')} {...register('note')} />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onDone} disabled={isSubmitting}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {t('invoices.add_payment')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}

/** Invoice card: amounts, lines, payments (add / remove) and reminders (send one now); right panel or page. */
export function InvoiceDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...INVOICES_KEY, id], queryFn: () => financeApi.get(id), enabled: id !== '' });
  const inv = q.data;
  const canManage = hasPrivilege(me, PRIV.FINANCE_INVOICE_MANAGE);
  const canPay = hasPrivilege(me, PRIV.FINANCE_PAYMENT_MANAGE);
  const canRemind = hasPrivilege(me, PRIV.FINANCE_REMINDER_MANAGE);
  const [paying, setPaying] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState<'delete' | 'remind' | number | null>(null);
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setPaying(false);
    setConfirmDelete(false);
  }
  const money = (v: number) => formatMoney(v, inv?.currency ?? null, lang);

  const refresh = async () => {
    await qc.invalidateQueries({ queryKey: INVOICES_KEY });
    void qc.invalidateQueries({ queryKey: DEBTS_KEY });
  };

  const remove = async () => {
    setBusy('delete');
    try {
      await financeApi.remove(id);
      notifications.show({ color: 'green', message: t('invoices.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: INVOICES_KEY, predicate: (x) => x.queryKey[INVOICES_KEY.length] !== id });
      void qc.invalidateQueries({ queryKey: DEBTS_KEY });
      // Billed orders follow their trips again.
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(null);
    }
  };

  const removePayment = async (paymentId: number) => {
    setBusy(paymentId);
    try {
      await financeApi.removePayment(id, paymentId);
      await refresh();
      notifications.show({ color: 'green', message: t('invoices.payment_deleted') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  const remind = async () => {
    setBusy('remind');
    try {
      await financeApi.sendReminder(id);
      await refresh();
      notifications.show({ color: 'green', message: t('invoices.reminder_queued') });
    } catch (e) {
      // 422 contractor: finance.reminder.no_email names the reason in the details.
      const detail = e instanceof ApiError ? e.details?.contractor : undefined;
      notifications.show({ color: 'red', message: t(detail ?? errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  const open = inv && inv.status !== 'CANCELLED' && inv.openAmount > 0;
  const actions = inv ? (
    <>
      {canPay && open && (
        <Tooltip label={t('hint.action.add_payment')}>
          <Button size="xs" leftSection={<IconCash size={14} />} onClick={() => setPaying(true)} disabled={paying || busy !== null}>
            {t('invoices.add_payment')}
          </Button>
        </Tooltip>
      )}
      {canRemind && open && inv.direction === 'SALES' && (
        <Tooltip label={t('hint.action.remind_now')}>
          <Button size="xs" variant="default" leftSection={<IconBell size={14} />} onClick={() => void remind()} loading={busy === 'remind'} disabled={busy !== null && busy !== 'remind'}>
            {t('invoices.remind_now')}
          </Button>
        </Tooltip>
      )}
      {canManage && (
        <Button size="xs" variant="default" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)} disabled={busy !== null}>
          {t('common.edit')}
        </Button>
      )}
      {canManage && inv.payments.length === 0 && (
        <Button size="xs" variant="default" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)} disabled={busy !== null}>
          {t('common.delete')}
        </Button>
      )}
    </>
  ) : undefined;

  const summary: KeyValueItem[] = inv
    ? [
        { label: t('invoices.direction'), value: t(`invoice.direction.${inv.direction}`) },
        {
          label: t('invoices.contractor'),
          value: (
            <Anchor component={Link} to={`/trips/contractors/${inv.contractor.id}`}>
              {inv.contractor.name}
            </Anchor>
          ),
        },
        { label: t('invoices.issue_date'), value: formatDate(inv.issueDate, lang) },
        { label: t('invoices.due_date'), value: formatDate(inv.dueDate, lang) },
        { label: t('invoices.net'), value: money(inv.net) },
        { label: t('invoices.vat'), value: money(inv.vat) },
        { label: t('invoices.gross'), value: <b>{money(inv.gross)}</b> },
        { label: t('invoices.paid'), value: money(inv.paidAmount) },
        { label: t('invoices.open'), value: <b>{inv.status === 'CANCELLED' ? DASH : money(inv.openAmount)}</b> },
        ...(inv.orders.length
          ? [
              {
                label: t('trips.tab.orders'),
                value: (
                  <Group gap={8}>
                    {inv.orders.map((o) => (
                      <Anchor key={o.id} component={Link} to={`/trips/orders/${o.id}`} className={classes.mono}>
                        {o.number}
                      </Anchor>
                    ))}
                  </Group>
                ),
              },
            ]
          : []),
        ...(inv.trip
          ? [
              {
                label: t('orders.trip'),
                value: (
                  <Anchor component={Link} to={`/trips/${inv.trip.id}`} className={classes.mono}>
                    {inv.trip.number}
                  </Anchor>
                ),
              },
            ]
          : []),
        { label: t('invoices.reminders_on'), value: t(inv.remindersOn ? 'invoices.yes' : 'invoices.no') },
      ]
    : [];

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={`${LIST}/${id}/page`}
      title={inv ? <span className={classes.mono}>{inv.number}</span> : '…'}
      subtitle={inv?.contractor.name}
      status={inv && <InvoiceStatusChip status={inv.status} daysOverdue={inv.daysOverdue} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : inv ? (
        <Stack gap="lg">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('invoices.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy === 'delete'}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy !== null}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          {paying && open && <PaymentForm invoice={inv} onDone={() => setPaying(false)} />}
          <KeyValueList items={summary} />

          <Section title={t('invoices.lines')}>
            {inv.lines.length === 0 ? (
              <Text size="sm" c="dimmed">
                {t('invoices.no_lines')}
              </Text>
            ) : (
              <Stack gap={6}>
                {inv.lines.map((l) => (
                  <Paper key={l.id} withBorder p="xs">
                    <Group justify="space-between" wrap="nowrap" align="flex-start">
                      <Stack gap={2}>
                        <Text size="sm" fw={500}>
                          {l.description}
                        </Text>
                        <Text size="sm" c="dimmed">
                          {new Intl.NumberFormat(lang, { maximumFractionDigits: 3 }).format(l.qty)} {l.unit ?? ''} ×{' '}
                          {formatMoney(l.unitPrice, inv.currency, lang)} · {vatLabel(t, l.vatCode, l.vatRate, lang)}
                        </Text>
                      </Stack>
                      <Text size="sm" fw={600} className={classes.num}>
                        {money(l.gross)}
                      </Text>
                    </Group>
                  </Paper>
                ))}
              </Stack>
            )}
          </Section>

          <Section title={t('payments.title')}>
            {inv.payments.length === 0 ? (
              <Text size="sm" c="dimmed">
                {t('invoices.no_payments')}
              </Text>
            ) : (
              <Stack gap={6}>
                {inv.payments.map((p) => (
                  <Paper key={p.id} withBorder p="xs">
                    <Group justify="space-between" wrap="nowrap">
                      <Stack gap={2}>
                        <Text size="sm" fw={500}>
                          {formatDate(p.paidAt, lang)} · {t(`payment.method.${p.method}`)}
                        </Text>
                        {(p.reference || p.note) && (
                          <Text size="sm" c="dimmed">
                            {[p.reference, p.note].filter(Boolean).join(' · ')}
                          </Text>
                        )}
                      </Stack>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" fw={600} className={classes.num}>
                          {formatMoney(p.amount, p.currency, lang)}
                        </Text>
                        {canPay && (
                          <Tooltip label={t('common.delete')}>
                            <ActionIcon variant="subtle" color="red" size="sm" loading={busy === p.id} onClick={() => void removePayment(p.id)} aria-label={t('common.delete')}>
                              <IconTrash size={15} />
                            </ActionIcon>
                          </Tooltip>
                        )}
                      </Group>
                    </Group>
                  </Paper>
                ))}
              </Stack>
            )}
          </Section>

          {inv.direction === 'SALES' && (
            <Section title={t('invoices.reminders')}>
              {inv.reminders.length === 0 ? (
                <Text size="sm" c="dimmed">
                  {t(inv.remindersOn ? 'invoices.no_reminders' : 'invoices.reminders_off')}
                </Text>
              ) : (
                <Stack gap={6}>
                  {inv.reminders.map((r) => (
                    <Paper key={r.id} withBorder p="xs">
                      <Stack gap={2}>
                        <Group gap={6} justify="space-between" wrap="nowrap">
                          <Text size="sm" fw={500}>
                            {reminderStepLabel(t, r.offsetDays)}
                          </Text>
                          <ReminderStatusChip status={r.status} />
                        </Group>
                        <Text size="sm" c="dimmed">
                          {formatInstantShort(r.sentAt ?? r.createdAt, lang)} · {r.toEmails.join(', ') || DASH}
                        </Text>
                        {r.error && (
                          <Text size="sm" c="red">
                            {r.error}
                          </Text>
                        )}
                      </Stack>
                    </Paper>
                  ))}
                </Stack>
              )}
            </Section>
          )}

          {inv.notes && (
            <Section title={t('drivers.notes')}>
              <Text size="sm" className={classes.notes}>
                {inv.notes}
              </Text>
            </Section>
          )}
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
