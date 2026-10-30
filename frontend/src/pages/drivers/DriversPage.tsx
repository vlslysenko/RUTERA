import { useMemo, useState } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Button, Group, Paper, SegmentedControl, Table, Text, TextInput } from '@mantine/core';
import { IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { DRIVERS_KEY, driversApi, type Driver } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { NextDeadline } from '@/components/DeadlineChip';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { PageHeader } from '@/components/PageHeader';
import { Plate } from '@/components/Plate';
import { cx } from '@/utils/cx';
import { DriverStatusChip } from './DriverStatusChip';
import classes from './DriversPage.module.css';

type StatusFilter = 'active' | 'all';

const DASH = '—';

function matches(d: Driver, status: StatusFilter, needle: string): boolean {
  if (status === 'active' && d.status !== 'ACTIVE') return false;
  if (!needle) return true;
  const compact = needle.replace(/[\s-]+/g, '');
  return (
    d.name.toLowerCase().includes(needle) ||
    [d.phone, d.card?.cardNumber, d.defaultVehicle?.plate].some((s) =>
      s?.replace(/[\s-]+/g, '').toLowerCase().includes(compact),
    )
  );
}

/** Drivers of the firma; a row opens the driver card in the right panel (/drivers/:id). */
export function DriversPage() {
  const { t } = useTranslation();
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list });
  const canManage = hasPrivilege(me, PRIV.DRIVER_MANAGE);
  const [status, setStatus] = useState<StatusFilter>('active');
  const [search, setSearch] = useState('');

  const open = (d: Driver) => void navigate(`/drivers/${d.id}`);
  const needle = search.trim().toLowerCase();
  const rows = useMemo(() => (q.data ?? []).filter((d) => matches(d, status, needle)), [q.data, status, needle]);
  const notReady = q.isError && q.error instanceof ApiError && q.error.status === 404;
  const deadlineLabel = (kind: string) => t(`driver.document.${kind}`);

  return (
    <>
      <PageHeader
        title={t('drivers.title')}
        subtitle={q.data?.length}
        actions={
          canManage &&
          !notReady && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/drivers/new')}>
              {t('drivers.add')}
            </Button>
          )
        }
      />
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : notReady ? (
        // An older backend without the drivers module.
        <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState text={t('drivers.empty')} />
      ) : (
        <>
          <Group gap="sm" mb="sm" wrap="wrap">
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={16} />}
              placeholder={t('drivers.search')}
              aria-label={t('drivers.search')}
              value={search}
              onChange={(e) => setSearch(e.currentTarget.value)}
            />
            <SegmentedControl
              value={status}
              onChange={(v) => setStatus(v as StatusFilter)}
              data={[
                { value: 'active', label: t('drivers.filter.active') },
                { value: 'all', label: t('drivers.filter.all') },
              ]}
            />
          </Group>
          {rows.length === 0 ? (
            <EmptyState text={t('drivers.nothing_found')} />
          ) : (
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('drivers.name')}</Table.Th>
                    <Table.Th>{t('drivers.phone')}</Table.Th>
                    <Table.Th>{t('drivers.vehicle')}</Table.Th>
                    <Table.Th>{t('drivers.card')}</Table.Th>
                    <Table.Th>{t('drivers.status')}</Table.Th>
                    <Table.Th>{t('deadline.next')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((d) => {
                    const selected = String(d.id) === id;
                    return (
                      <Table.Tr
                        key={d.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(d)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(d);
                          }
                        }}
                      >
                        <Table.Td>
                          <Text span fw={500}>
                            {d.name}
                          </Text>
                        </Table.Td>
                        <Table.Td className={classes.mono}>{d.phone ?? <Text span c="dimmed">{DASH}</Text>}</Table.Td>
                        <Table.Td>
                          {d.defaultVehicle ? (
                            <Plate>{d.defaultVehicle.plate}</Plate>
                          ) : (
                            <Text span c="dimmed">
                              {t('drivers.no_vehicle')}
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td className={classes.mono}>
                          {d.card?.cardNumber ?? (
                            <Text span c="dimmed">
                              {t('drivers.no_card')}
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>
                          <DriverStatusChip status={d.status} />
                        </Table.Td>
                        <Table.Td>
                          {d.nextDocument ? (
                            <NextDeadline label={deadlineLabel(d.nextDocument.kind)} validTo={d.nextDocument.validTo} />
                          ) : (
                            <Text span c="dimmed">
                              {DASH}
                            </Text>
                          )}
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </Paper>
          )}
        </>
      )}
      <Outlet />
    </>
  );
}
