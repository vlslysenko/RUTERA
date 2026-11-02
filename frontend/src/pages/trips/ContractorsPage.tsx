import { useState } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Button, Group, Paper, SegmentedControl, Table, Text, TextInput } from '@mantine/core';
import { IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { CONTRACTORS_KEY, contractorsApi, type Contractor } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { StatusChip } from '@/components/StatusChip';
import { countryName } from '@/utils/countries';
import { cx } from '@/utils/cx';
import classes from './Trips.module.css';

type RoleFilter = 'all' | 'CUSTOMER' | 'CARRIER';

/** Contractors of the firma (customers and carriers); a row opens the card in the right panel. */
export function ContractorsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [q] = useDebouncedValue(search.trim(), 250);
  const [role, setRole] = useState<RoleFilter>('all');
  const canManage = hasPrivilege(me, PRIV.TRIP_CONTRACTOR_MANAGE);

  const list = useQuery({
    queryKey: [...CONTRACTORS_KEY, 'list', q, role],
    queryFn: () => contractorsApi.list(q || undefined, role === 'all' ? undefined : role),
    placeholderData: keepPreviousData,
  });
  const notReady = list.isError && list.error instanceof ApiError && list.error.status === 404;
  const open = (c: Contractor) => void navigate(`/trips/contractors/${c.id}`);

  if (notReady) return <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />;

  return (
    <>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <div className={classes.filters}>
          <TextInput
            className={classes.search}
            leftSection={<IconSearch size={16} />}
            placeholder={t('contractors.search')}
            aria-label={t('contractors.search')}
            value={search}
            onChange={(e) => setSearch(e.currentTarget.value)}
          />
          <SegmentedControl
            value={role}
            onChange={(v) => setRole(v as RoleFilter)}
            data={[
              { value: 'all', label: t('fleet.filter.all') },
              { value: 'CUSTOMER', label: t('contractors.customers') },
              { value: 'CARRIER', label: t('contractors.carriers') },
            ]}
          />
          {list.data && <Text c="dimmed">{list.data.length}</Text>}
        </div>
        {canManage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/trips/contractors/new')}>
            {t('contractors.add')}
          </Button>
        )}
      </Group>
      {list.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : list.isError ? (
        <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <EmptyState text={t(q || role !== 'all' ? 'fleet.nothing_found' : 'contractors.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('contractors.name')}</Table.Th>
                <Table.Th>{t('contractors.vat_id')}</Table.Th>
                <Table.Th>{t('trips.country')}</Table.Th>
                <Table.Th>{t('contractors.role')}</Table.Th>
                <Table.Th className={classes.num}>{t('contractors.payment_days')}</Table.Th>
                <Table.Th>{t('drivers.phone')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.data.map((c) => {
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
                    <Table.Td>
                      <Text span fw={500}>
                        {c.name}
                      </Text>
                    </Table.Td>
                    <Table.Td className={classes.mono}>{c.vatId ?? '—'}</Table.Td>
                    <Table.Td>{countryName(c.country, lang)}</Table.Td>
                    <Table.Td>
                      <Group gap={4} wrap="nowrap">
                        {c.isCustomer && <StatusChip tone="info">{t('contractors.customer')}</StatusChip>}
                        {c.isCarrier && <StatusChip tone="neutral">{t('contractors.carrier')}</StatusChip>}
                      </Group>
                    </Table.Td>
                    <Table.Td className={classes.num}>{c.paymentDays ?? '—'}</Table.Td>
                    <Table.Td className={classes.mono}>{c.phone ?? '—'}</Table.Td>
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
