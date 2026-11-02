import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Alert, Anchor, Button, Group, Paper, Select, Stack, Table, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronLeft, IconChevronRight, IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { HR_KEY, hrApi, NEGATIVE_PAYMENT_KINDS, PAYMENT_KINDS, type DriverPayment, type PaymentKind } from '@/api/hr';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { PaymentKindChip } from './HrChips';
import { formatAmount, formatMonth, formatPayment, monthOf, shiftMonth } from './hrFormat';
import { PaymentForm } from './PaymentForm';
import classes from './Hr.module.css';
import tableClasses from './DriversPage.module.css';

/** Payments of the firma for one month (payroll): filters by driver and kind, totals per currency from the backend. */
export function PaymentsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const qc = useQueryClient();
  const canManage = hasPrivilege(me, PRIV.DRIVER_PAYMENT_MANAGE);
  const [period, setPeriod] = useState(monthOf());
  const [driverId, setDriverId] = useState<string | null>(null);
  const [kind, setKind] = useState<PaymentKind | null>(null);
  const [editing, setEditing] = useState<DriverPayment | 'new' | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list });
  const q = useQuery({
    queryKey: [...HR_KEY, 'payments', period, driverId, kind],
    queryFn: () => hrApi.firmPayments(period, driverId ? Number(driverId) : undefined, kind ?? undefined),
  });

  const remove = async (p: DriverPayment) => {
    setBusy(true);
    try {
      await hrApi.removePayment(p.driver.id, p.id);
      void qc.invalidateQueries({ queryKey: HR_KEY });
      void qc.invalidateQueries({ queryKey: [...DRIVERS_KEY, String(p.driver.id), 'payments'] });
      notifications.show({ color: 'green', message: t('payments.deleted') });
      setDeleting(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="sm">
      <Group justify="space-between" wrap="wrap" align="flex-end">
        <Group gap="sm" wrap="wrap" align="center">
          <Group gap={4} wrap="nowrap">
            <Tooltip label={t('days.prev_month')}>
              <ActionIcon variant="default" size="lg" onClick={() => setPeriod((m) => shiftMonth(m, -1))} aria-label={t('days.prev_month')}>
                <IconChevronLeft size={16} />
              </ActionIcon>
            </Tooltip>
            <Text fw={600} px={6} className={classes.monthNav}>
              {formatMonth(period, lang)}
            </Text>
            <Tooltip label={t('days.next_month')}>
              <ActionIcon variant="default" size="lg" onClick={() => setPeriod((m) => shiftMonth(m, 1))} aria-label={t('days.next_month')}>
                <IconChevronRight size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
          <Select
            placeholder={t('trips.driver')}
            aria-label={t('trips.driver')}
            data={(drivers.data ?? []).map((d) => ({ value: String(d.id), label: d.name }))}
            value={driverId}
            onChange={setDriverId}
            clearable
            searchable
            w={200}
          />
          <Select
            placeholder={t('payments.kind')}
            aria-label={t('payments.kind')}
            data={PAYMENT_KINDS.map((k) => ({ value: k, label: t(`payment.kind.${k}`) }))}
            value={kind}
            onChange={(v) => setKind(v as PaymentKind | null)}
            clearable
            w={170}
          />
        </Group>
        {canManage && editing === null && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('payments.add')}
          </Button>
        )}
      </Group>

      {editing !== null && (
        <div style={{ maxWidth: 560 }}>
          <PaymentForm
            payment={editing === 'new' ? null : editing}
            driverId={editing === 'new' ? (driverId ?? undefined) : String(editing.driver.id)}
            defaultPeriod={period}
            onDone={() => setEditing(null)}
          />
        </div>
      )}

      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.items.length === 0 ? (
        <EmptyState text={t('payments.empty_month')} />
      ) : (
        <Paper className={tableClasses.tableWrap}>
          <Table className={tableClasses.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('trips.driver')}</Table.Th>
                <Table.Th>{t('payments.kind')}</Table.Th>
                <Table.Th className={classes.num}>{t('payments.amount')}</Table.Th>
                <Table.Th>{t('payments.paid_at')}</Table.Th>
                <Table.Th>{t('drivers.notes')}</Table.Th>
                {canManage && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.items.map((p) => (
                <Table.Tr key={p.id}>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>
                    <Anchor component={Link} to={`/drivers/${p.driver.id}?tab=pay`} size="sm">
                      {p.driver.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <PaymentKindChip kind={p.kind} />
                  </Table.Td>
                  <Table.Td className={cx(classes.num, NEGATIVE_PAYMENT_KINDS.includes(p.kind) && classes.negative)}>
                    {formatPayment(p.amount, p.currency, p.kind, lang)}
                  </Table.Td>
                  <Table.Td>{p.paidAt ? formatDate(p.paidAt, lang) : <Text span c="dimmed">{t('payments.unpaid')}</Text>}</Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1} maw={260} title={p.note ?? undefined}>
                      {p.note ?? '—'}
                    </Text>
                    {deleting === p.id && (
                      <Alert color="red" variant="light" p="xs" mt={4}>
                        <Group gap="xs">
                          <Text size="sm">{t('payments.delete_confirm')}</Text>
                          <Button size="xs" color="red" onClick={() => void remove(p)} loading={busy}>
                            {t('common.confirm')}
                          </Button>
                          <Button size="xs" variant="default" onClick={() => setDeleting(null)} disabled={busy}>
                            {t('common.cancel')}
                          </Button>
                        </Group>
                      </Alert>
                    )}
                  </Table.Td>
                  {canManage && (
                    <Table.Td>
                      <Group gap={2} wrap="nowrap" justify="flex-end">
                        <Tooltip label={t('common.edit')}>
                          <ActionIcon variant="subtle" color="gray" size="sm" onClick={() => setEditing(p)} aria-label={t('common.edit')}>
                            <IconPencil size={15} />
                          </ActionIcon>
                        </Tooltip>
                        <Tooltip label={t('common.delete')}>
                          <ActionIcon variant="subtle" color="red" size="sm" onClick={() => setDeleting(p.id)} aria-label={t('common.delete')}>
                            <IconTrash size={15} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group justify="flex-end" gap="md" p="sm">
            <Text size="sm" c="dimmed">
              {t('payments.total_month')}
            </Text>
            {q.data.totals.map((s) => (
              <Text key={s.currency} fw={600} className={cx(classes.num, s.amount < 0 && classes.negative)}>
                {formatAmount(s.amount, s.currency, lang)}
              </Text>
            ))}
          </Group>
        </Paper>
      )}
    </Stack>
  );
}
