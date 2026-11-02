import { useState } from 'react';
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Button, Group, Pagination, Paper, Select, Stack, Switch, Table, Text, TextInput } from '@mantine/core';
import { IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { FUEL_PRODUCTS, FUEL_TX_KEY, fuelApi, type FuelFilter, type FuelProduct, type FuelTransaction } from '@/api/fuel';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { Plate } from '@/components/Plate';
import { useCrewOptions } from '@/pages/trips/crew';
import { formatInstantShort, formatMoney } from '@/pages/trips/tripFormat';
import { cx } from '@/utils/cx';
import { MatchChip } from './FuelChips';
import { formatQty } from './fuelFormat';
import classes from '@/pages/trips/Trips.module.css';
import { DateField } from '@/forms/DateField';

const PAGE_SIZE = 50;

/** Fuel and toll transactions, newest first; filters in the URL; sums per currency and quantities per product. */
export function TransactionsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(search.trim(), 250);
  const canManage = hasPrivilege(me, PRIV.FUEL_IMPORT_MANAGE);
  // Receipt times are station times read in the firma zone.
  const tz = me.firma?.timezone;
  const crew = useCrewOptions([params.get('vehicle'), params.get('driver')]);

  const page = Number(params.get('page') ?? '1');
  const filter: FuelFilter = {
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    vehicleId: params.get('vehicle') ? Number(params.get('vehicle')) : undefined,
    driverId: params.get('driver') ? Number(params.get('driver')) : undefined,
    product: (params.get('product') as FuelProduct | null) ?? undefined,
    unmatched: params.get('unmatched') === 'true' ? true : undefined,
    q: q || undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...FUEL_TX_KEY, 'list', filter], queryFn: () => fuelApi.transactions(filter), placeholderData: keepPreviousData });

  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (tx: FuelTransaction) => void navigate({ pathname: `/fuel/${tx.id}`, search: params.toString() });
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
              placeholder={t('fuel.search')}
              aria-label={t('fuel.search')}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                set('page', null);
              }}
            />
            <DateField aria-label={t('orders.date_from')} title={t('orders.date_from')} value={params.get('from') ?? ''} onChange={(v) => set('from', v || null)} w={150} />
            <DateField aria-label={t('orders.date_to')} title={t('orders.date_to')} value={params.get('to') ?? ''} onChange={(v) => set('to', v || null)} w={150} />
            {!crew.error && (
              <>
                <Select placeholder={t('trips.vehicle')} aria-label={t('trips.vehicle')} data={crew.vehicles} value={params.get('vehicle')} onChange={(v) => set('vehicle', v)} clearable searchable w={170} />
                <Select placeholder={t('trips.driver')} aria-label={t('trips.driver')} data={crew.drivers} value={params.get('driver')} onChange={(v) => set('driver', v)} clearable searchable w={170} />
              </>
            )}
            <Select
              placeholder={t('fuel.product')}
              aria-label={t('fuel.product')}
              data={FUEL_PRODUCTS.map((p) => ({ value: p, label: t(`fuel.product.${p}`) }))}
              value={params.get('product')}
              onChange={(v) => set('product', v)}
              clearable
              w={150}
            />
            <Switch label={t('fuel.only_unmatched')} checked={params.get('unmatched') === 'true'} onChange={(e) => set('unmatched', e.currentTarget.checked ? 'true' : null)} mb={8} />
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/fuel/new')}>
              {t('fuel.add')}
            </Button>
          )}
        </Group>

        {list.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : list.isError ? (
          <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
        ) : list.data.items.length === 0 ? (
          <EmptyState text={t('fuel.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('fuel.at')}</Table.Th>
                    <Table.Th>{t('trips.vehicle')}</Table.Th>
                    <Table.Th>{t('trips.driver')}</Table.Th>
                    <Table.Th>{t('fuel.station')}</Table.Th>
                    <Table.Th>{t('fuel.product')}</Table.Th>
                    <Table.Th className={classes.num}>{t('fuel.quantity')}</Table.Th>
                    <Table.Th className={classes.num}>{t('invoices.gross')}</Table.Th>
                    <Table.Th>{t('fuel.match')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.data.items.map((tx) => {
                    const selected = String(tx.id) === id;
                    return (
                      <Table.Tr
                        key={tx.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(tx)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(tx);
                          }
                        }}
                      >
                        <Table.Td className={classes.mono}>{formatInstantShort(tx.at, lang, tz)}</Table.Td>
                        <Table.Td>{tx.vehicle ? <Plate>{tx.vehicle.name}</Plate> : <Text span c="dimmed">{tx.plateRaw ?? '—'}</Text>}</Table.Td>
                        <Table.Td>{tx.driver?.name ?? '—'}</Table.Td>
                        <Table.Td>
                          {tx.station ?? '—'}
                          {tx.country ? ` · ${tx.country}` : ''}
                        </Table.Td>
                        <Table.Td>{t(`fuel.product.${tx.product}`)}</Table.Td>
                        <Table.Td className={classes.num}>{formatQty(tx.quantity, tx.unit, lang, t)}</Table.Td>
                        <Table.Td className={classes.num}>{formatMoney(tx.gross, tx.currency, lang)}</Table.Td>
                        <Table.Td>
                          <MatchChip match={tx.match} />
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
              <Group justify="flex-end" gap="lg" p="sm" wrap="wrap">
                {list.data.quantities.map((s) => (
                  <Text key={`${s.product}-${s.unit}`} size="sm" c="dimmed">
                    {t(`fuel.product.${s.product}`)}: <b>{formatQty(s.quantity, s.unit, lang, t)}</b>
                  </Text>
                ))}
                {list.data.totals.map((s) => (
                  <Text key={s.currency} size="sm" className={classes.num}>
                    {t('invoices.net')} {formatMoney(s.net, s.currency, lang)} · <b>{formatMoney(s.gross, s.currency, lang)}</b>
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
