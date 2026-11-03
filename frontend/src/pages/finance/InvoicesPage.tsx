import { useState } from 'react';
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Button, Group, Pagination, Paper, SegmentedControl, Select, Stack, Switch, Table, Text, TextInput, Tooltip } from '@mantine/core';
import { IconBell, IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { financeApi, INVOICE_STATUSES, INVOICES_KEY, type Invoice, type InvoiceDirection, type InvoiceFilter, type InvoiceStatus } from '@/api/finance';
import { CONTRACTORS_KEY, contractorsApi } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatMoney } from '@/pages/trips/tripFormat';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { DateField } from '@/forms/DateField';
import { InvoiceStatusChip } from './FinanceChips';
import classes from '@/pages/trips/Trips.module.css';

const PAGE_SIZE = 50;

/** Invoices, newest issue date first: sales or purchase, filters in the URL, totals per currency for the filter. */
export function InvoicesPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(search.trim(), 250);
  const canManage = hasPrivilege(me, PRIV.FINANCE_INVOICE_MANAGE);
  const contractors = useQuery({ queryKey: [...CONTRACTORS_KEY, 'list', '', 'all'], queryFn: () => contractorsApi.list() });

  const direction = (params.get('direction') as InvoiceDirection | null) ?? 'SALES';
  const page = Number(params.get('page') ?? '1');
  const filter: InvoiceFilter = {
    direction,
    status: (params.get('status') as InvoiceStatus | null) ?? undefined,
    contractorId: params.get('contractor') ? Number(params.get('contractor')) : undefined,
    q: q || undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    overdue: params.get('overdue') === 'true' ? true : undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...INVOICES_KEY, 'list', filter], queryFn: () => financeApi.list(filter), placeholderData: keepPreviousData });

  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (inv: Invoice) => void navigate({ pathname: `/finance/${inv.id}`, search: params.toString() });
  const notReady = list.isError && list.error instanceof ApiError && list.error.status === 404;
  const pages = list.data ? Math.max(1, Math.ceil(list.data.total / PAGE_SIZE)) : 1;

  if (notReady) return <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />;

  return (
    <>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap" align="flex-end">
          <div className={classes.filters}>
            <SegmentedControl
              value={direction}
              onChange={(v) => set('direction', v === 'SALES' ? null : v)}
              data={[
                { value: 'SALES', label: t('invoice.direction.SALES') },
                { value: 'PURCHASE', label: t('invoice.direction.PURCHASE') },
              ]}
            />
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={16} />}
              placeholder={t('invoices.search')}
              aria-label={t('invoices.search')}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                set('page', null);
              }}
            />
            <Select
              placeholder={t('users.status')}
              aria-label={t('users.status')}
              data={INVOICE_STATUSES.map((s) => ({ value: s, label: t(`invoice.status.${s}`) }))}
              value={params.get('status')}
              onChange={(v) => set('status', v)}
              clearable
              w={160}
            />
            <Select
              placeholder={t('invoices.contractor')}
              aria-label={t('invoices.contractor')}
              data={(contractors.data ?? []).map((c) => ({ value: String(c.id), label: c.name }))}
              value={params.get('contractor')}
              onChange={(v) => set('contractor', v)}
              clearable
              searchable
              w={200}
            />
            <DateField aria-label={t('orders.date_from')} title={t('orders.date_from')} value={params.get('from') ?? ''} onChange={(v) => set('from', v || null)} w={150} />
            <DateField aria-label={t('orders.date_to')} title={t('orders.date_to')} value={params.get('to') ?? ''} onChange={(v) => set('to', v || null)} w={150} />
            <Switch label={t('invoices.only_overdue')} checked={params.get('overdue') === 'true'} onChange={(e) => set('overdue', e.currentTarget.checked ? 'true' : null)} mb={8} />
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate(`/finance/new?direction=${direction}`)}>
              {t('invoices.add')}
            </Button>
          )}
        </Group>

        {list.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : list.isError ? (
          <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
        ) : list.data.items.length === 0 ? (
          <EmptyState text={t('invoices.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('orders.number')}</Table.Th>
                    <Table.Th>{t('invoices.contractor')}</Table.Th>
                    <Table.Th>{t('invoices.issue_date')}</Table.Th>
                    <Table.Th>{t('invoices.due_date')}</Table.Th>
                    <Table.Th className={classes.num}>{t('invoices.gross')}</Table.Th>
                    <Table.Th className={classes.num}>{t('invoices.open')}</Table.Th>
                    <Table.Th>{t('users.status')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.data.items.map((inv) => {
                    const selected = String(inv.id) === id;
                    return (
                      <Table.Tr
                        key={inv.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(inv)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(inv);
                          }
                        }}
                      >
                        <Table.Td className={classes.mono}>{inv.number}</Table.Td>
                        <Table.Td>
                          <Text span fw={500}>
                            {inv.contractor.name}
                          </Text>
                        </Table.Td>
                        <Table.Td className={classes.mono}>{formatDate(inv.issueDate, lang)}</Table.Td>
                        <Table.Td className={classes.mono}>{formatDate(inv.dueDate, lang)}</Table.Td>
                        <Table.Td className={classes.num}>{formatMoney(inv.gross, inv.currency, lang)}</Table.Td>
                        <Table.Td className={classes.num}>{inv.status === 'CANCELLED' ? '—' : formatMoney(inv.openAmount, inv.currency, lang)}</Table.Td>
                        <Table.Td>
                          <Group gap={6} wrap="nowrap">
                            <InvoiceStatusChip status={inv.status} daysOverdue={inv.daysOverdue} />
                            {inv.lastReminderAt && (
                              <Tooltip label={t('invoices.last_reminder', { date: formatDate(inv.lastReminderAt.slice(0, 10), lang) })}>
                                <IconBell size={15} aria-label={t('invoices.reminded')} />
                              </Tooltip>
                            )}
                          </Group>
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
              <Group justify="flex-end" gap="lg" p="sm">
                <Text size="sm" c="dimmed">
                  {t('invoices.totals', { n: list.data.total })}
                </Text>
                {list.data.totals.map((s) => (
                  <Text key={s.currency} size="sm" className={classes.num}>
                    {formatMoney(s.gross, s.currency, lang)} · <b>{t('invoices.open')}: {formatMoney(s.open, s.currency, lang)}</b>
                  </Text>
                ))}
              </Group>
            </Paper>
            {pages > 1 && (
              <Pagination
                total={pages}
                value={page}
                onChange={(p) =>
                  setParams((sp) => {
                    sp.set('page', String(p));
                    return sp;
                  })
                }
                size="sm"
              />
            )}
          </>
        )}
      </Stack>
      <Outlet />
    </>
  );
}
