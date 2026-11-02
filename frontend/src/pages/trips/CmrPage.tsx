import { useState } from 'react';
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Badge, Button, CloseButton, Group, Pagination, Paper, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconArrowRight, IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { CMR_KEY, CMR_STATUSES, cmrApi, type Cmr, type CmrFilter, type CmrStatus } from '@/api/cmr';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import { CmrStatusChip } from './TripChips';
import { placeLine } from './tripFormat';
import classes from './Trips.module.css';
import { DateField } from '@/forms/DateField';

const PAGE_SIZE = 50;
const DASH = '—';

/** CMR consignment notes, newest first; filters in the URL (also ?order= / ?trip= from their cards); a row opens the note. */
export function CmrPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(search.trim(), 250);
  const canManage = hasPrivilege(me, PRIV.TRIP_CMR_MANAGE);

  const page = Number(params.get('page') ?? '1');
  const filter: CmrFilter = {
    status: (params.get('status') as CmrStatus | null) ?? undefined,
    orderId: params.get('order') ? Number(params.get('order')) : undefined,
    tripId: params.get('trip') ? Number(params.get('trip')) : undefined,
    q: q || undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...CMR_KEY, 'list', filter], queryFn: () => cmrApi.list(filter), placeholderData: keepPreviousData });

  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (c: Cmr) => void navigate({ pathname: `/trips/cmr/${c.id}`, search: params.toString() });
  const notReady = list.isError && list.error instanceof ApiError && list.error.status === 404;
  const pages = list.data ? Math.max(1, Math.ceil(list.data.total / PAGE_SIZE)) : 1;
  const first = list.data?.items[0];

  if (notReady) return <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />;

  // The order or trip the list is narrowed to (from its card); the number comes with the notes.
  const scope = filter.orderId
    ? { key: 'order', label: t('cmr.of_order', { number: first?.order?.number ?? `#${filter.orderId}` }) }
    : filter.tripId
      ? { key: 'trip', label: t('cmr.of_trip', { number: first?.trip?.number ?? `#${filter.tripId}` }) }
      : null;

  return (
    <>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap" align="flex-end">
          <div className={classes.filters}>
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={16} />}
              placeholder={t('cmr.search')}
              aria-label={t('cmr.search')}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                set('page', null);
              }}
            />
            <Select
              placeholder={t('users.status')}
              aria-label={t('users.status')}
              data={CMR_STATUSES.map((s) => ({ value: s, label: t(`cmr.status.${s}`) }))}
              value={params.get('status')}
              onChange={(v) => set('status', v)}
              clearable
              w={160}
            />
            <DateField aria-label={t('cmr.loading_from')} title={t('cmr.loading_from')} value={params.get('from') ?? ''} onChange={(v) => set('from', v || null)} w={150} />
            <DateField aria-label={t('cmr.loading_to')} title={t('cmr.loading_to')} value={params.get('to') ?? ''} onChange={(v) => set('to', v || null)} w={150} />
            {scope && (
              <Badge variant="light" size="lg" mb={4} rightSection={<CloseButton size="xs" onClick={() => set(scope.key, null)} aria-label={t('common.close')} />}>
                {scope.label}
              </Badge>
            )}
            {list.data && (
              <Text c="dimmed" mb={6}>
                {list.data.total}
              </Text>
            )}
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/trips/cmr/new')}>
              {t('cmr.add')}
            </Button>
          )}
        </Group>

        {list.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : list.isError ? (
          <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
        ) : list.data.items.length === 0 ? (
          <EmptyState text={t('cmr.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('cmr.number')}</Table.Th>
                    <Table.Th>{t('users.status')}</Table.Th>
                    <Table.Th>{t('cmr.loading_date')}</Table.Th>
                    <Table.Th>{t('cmr.sender')}</Table.Th>
                    <Table.Th>{t('cmr.consignee')}</Table.Th>
                    <Table.Th>{t('orders.route')}</Table.Th>
                    <Table.Th>{t('cmr.order')}</Table.Th>
                    <Table.Th>{t('orders.trip')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.data.items.map((c) => {
                    const selected = String(c.id) === id;
                    return (
                      <Table.Tr
                        key={c.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(c)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(c);
                          }
                        }}
                      >
                        <Table.Td className={classes.mono}>{c.number}</Table.Td>
                        <Table.Td>
                          <CmrStatusChip status={c.status} />
                        </Table.Td>
                        <Table.Td className={classes.mono}>{c.loadingDate ? formatDate(c.loadingDate, lang) : DASH}</Table.Td>
                        <Table.Td>
                          <Text span fw={500}>
                            {c.sender?.name ?? DASH}
                          </Text>
                        </Table.Td>
                        <Table.Td>{c.consignee?.name ?? c.consignee?.address?.split('\n')[0] ?? DASH}</Table.Td>
                        <Table.Td>
                          <span className={classes.route}>
                            {placeLine(c.loadingPlace, c.loadingCountry)}
                            <IconArrowRight size={14} />
                            {placeLine(c.deliveryPlace, c.deliveryCountry)}
                          </span>
                        </Table.Td>
                        <Table.Td className={classes.mono}>{c.order?.number ?? DASH}</Table.Td>
                        <Table.Td className={classes.mono}>{c.trip?.number ?? DASH}</Table.Td>
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
