import { useState } from 'react';
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Button, Group, Pagination, Paper, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconArrowRight, IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { CONTRACTORS_KEY, contractorsApi, ORDER_STATUSES, ORDERS_KEY, ordersApi, type Order, type OrderFilter, type OrderStatus } from '@/api/trips';
import { hasAnyPrivilege, hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { DateField } from '@/forms/DateField';
import { OrderStatusChip } from './TripChips';
import { formatMoney } from './tripFormat';
import classes from './Trips.module.css';

const PAGE_SIZE = 50;

/** Transport orders, newest order date first; filters in the URL; a row opens the order in the right panel. */
export function OrdersPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(search.trim(), 250);
  const canManage = hasPrivilege(me, PRIV.TRIP_ORDER_MANAGE);
  const customers = useQuery({
    queryKey: [...CONTRACTORS_KEY, 'list', '', 'CUSTOMER'],
    queryFn: () => contractorsApi.list(undefined, 'CUSTOMER'),
    enabled: hasAnyPrivilege(me, [PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CONTRACTOR_MANAGE]),
  });

  const page = Number(params.get('page') ?? '1');
  const filter: OrderFilter = {
    status: (params.get('status') as OrderStatus | null) ?? undefined,
    customerId: params.get('customer') ? Number(params.get('customer')) : undefined,
    q: q || undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...ORDERS_KEY, 'list', filter], queryFn: () => ordersApi.list(filter), placeholderData: keepPreviousData });

  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (o: Order) => void navigate({ pathname: `/trips/orders/${o.id}`, search: params.toString() });
  const notReady = list.isError && list.error instanceof ApiError && list.error.status === 404;
  const pages = list.data ? Math.max(1, Math.ceil(list.data.total / PAGE_SIZE)) : 1;

  if (notReady) return <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />;

  return (
    <>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap" align="flex-end">
          <div className={classes.filters}>
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={16} />}
              placeholder={t('orders.search')}
              aria-label={t('orders.search')}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                set('page', null);
              }}
            />
            <Select
              placeholder={t('users.status')}
              aria-label={t('users.status')}
              data={ORDER_STATUSES.map((s) => ({ value: s, label: t(`order.status.${s}`) }))}
              value={params.get('status')}
              onChange={(v) => set('status', v)}
              clearable
              w={170}
            />
            {customers.data && (
              <Select
                placeholder={t('orders.customer')}
                aria-label={t('orders.customer')}
                data={customers.data.map((c) => ({ value: String(c.id), label: c.name }))}
                value={params.get('customer')}
                onChange={(v) => set('customer', v)}
                clearable
                searchable
                w={200}
              />
            )}
            <DateField aria-label={t('orders.date_from')} title={t('orders.date_from')} value={params.get('from') ?? ''} onChange={(v) => set('from', v || null)} w={150} />
            <DateField aria-label={t('orders.date_to')} title={t('orders.date_to')} value={params.get('to') ?? ''} onChange={(v) => set('to', v || null)} w={150} />
            {list.data && (
              <Text c="dimmed" mb={6}>
                {list.data.total}
              </Text>
            )}
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/trips/orders/new')}>
              {t('orders.add')}
            </Button>
          )}
        </Group>

        {list.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : list.isError ? (
          <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
        ) : list.data.items.length === 0 ? (
          <EmptyState text={t('orders.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('orders.number')}</Table.Th>
                    <Table.Th>{t('orders.date')}</Table.Th>
                    <Table.Th>{t('orders.customer')}</Table.Th>
                    <Table.Th>{t('orders.route')}</Table.Th>
                    <Table.Th>{t('orders.loading_date')}</Table.Th>
                    <Table.Th className={classes.num}>{t('orders.price')}</Table.Th>
                    <Table.Th>{t('users.status')}</Table.Th>
                    <Table.Th>{t('orders.trip')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.data.items.map((o) => {
                    const selected = String(o.id) === id;
                    return (
                      <Table.Tr
                        key={o.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(o)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(o);
                          }
                        }}
                      >
                        <Table.Td className={classes.mono}>{o.number}</Table.Td>
                        <Table.Td className={classes.mono}>{formatDate(o.orderDate, lang)}</Table.Td>
                        <Table.Td>
                          <Text span fw={500}>
                            {o.customer.name}
                          </Text>
                        </Table.Td>
                        <Table.Td>
                          <span className={classes.route}>
                            {o.loading.place}, {o.loading.country}
                            <IconArrowRight size={14} />
                            {o.unloading.place}, {o.unloading.country}
                          </span>
                        </Table.Td>
                        <Table.Td className={classes.mono}>{o.loading.date ? formatDate(o.loading.date, lang) : '—'}</Table.Td>
                        <Table.Td className={classes.num}>{formatMoney(o.price, o.currency, lang)}</Table.Td>
                        <Table.Td>
                          <OrderStatusChip status={o.status} />
                        </Table.Td>
                        <Table.Td className={classes.mono}>{o.trip?.number ?? '—'}</Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
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
