import { useMemo, useState } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Button, Group, Paper, SegmentedControl, Table, Text, TextInput } from '@mantine/core';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { FUEL_CARDS_KEY, fuelApi, type FuelCard } from '@/api/fuel';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DeadlineChip } from '@/components/DeadlineChip';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { Plate } from '@/components/Plate';
import { StatusChip } from '@/components/StatusChip';
import { cx } from '@/utils/cx';
import classes from '@/pages/trips/Trips.module.css';

type Filter = 'active' | 'all';

/** Fuel cards: provider, masked number, vehicle and driver; cards unknown so far are created by imports. */
export function CardsPage() {
  const { t } = useTranslation();
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const canManage = hasPrivilege(me, PRIV.FUEL_IMPORT_MANAGE);
  const [filter, setFilter] = useState<Filter>('active');
  const [search, setSearch] = useState('');
  const q = useQuery({ queryKey: FUEL_CARDS_KEY, queryFn: fuelApi.cards });
  const needle = search.trim().toLowerCase();
  const rows = useMemo(
    () =>
      (q.data ?? [])
        .filter((c) => filter === 'all' || c.active)
        .filter((c) => !needle || [c.cardNumberMasked, c.provider, c.vehicle?.name, c.driver?.name].some((s) => (s ?? '').toLowerCase().includes(needle))),
    [q.data, filter, needle],
  );
  const open = (c: FuelCard) => void navigate(`/fuel/cards/${c.id}`);

  return (
    <>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <div className={classes.filters}>
          <TextInput
            className={classes.search}
            leftSection={<IconSearch size={16} />}
            placeholder={t('fuel.cards.search')}
            aria-label={t('fuel.cards.search')}
            value={search}
            onChange={(e) => setSearch(e.currentTarget.value)}
          />
          <SegmentedControl
            value={filter}
            onChange={(v) => setFilter(v as Filter)}
            data={[
              { value: 'active', label: t('drivers.filter.active') },
              { value: 'all', label: t('drivers.filter.all') },
            ]}
          />
          {q.data && <Text c="dimmed">{rows.length}</Text>}
        </div>
        {canManage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/fuel/cards/new')}>
            {t('fuel.cards.add')}
          </Button>
        )}
      </Group>
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState text={t(q.data.length ? 'fleet.nothing_found' : 'fuel.cards.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('fuel.card')}</Table.Th>
                <Table.Th>{t('fuel.provider')}</Table.Th>
                <Table.Th>{t('trips.vehicle')}</Table.Th>
                <Table.Th>{t('trips.driver')}</Table.Th>
                <Table.Th>{t('fuel.cards.valid_to')}</Table.Th>
                <Table.Th>{t('users.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((c) => {
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
                    <Table.Td className={classes.mono}>{c.cardNumberMasked}</Table.Td>
                    <Table.Td>{t(`fuel.provider.${c.provider}`)}</Table.Td>
                    <Table.Td>{c.vehicle ? <Plate>{c.vehicle.name}</Plate> : '—'}</Table.Td>
                    <Table.Td>{c.driver?.name ?? '—'}</Table.Td>
                    <Table.Td>{c.validTo ? <DeadlineChip validTo={c.validTo} /> : '—'}</Table.Td>
                    <Table.Td>
                      <StatusChip tone={c.active ? 'good' : 'neutral'}>{t(c.active ? 'fuel.cards.active' : 'fuel.cards.inactive')}</StatusChip>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
      <Outlet />
    </>
  );
}
