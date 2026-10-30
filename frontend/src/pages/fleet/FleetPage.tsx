import { useMemo, useState } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Button, Group, Paper, SegmentedControl, Table, Text, TextInput } from '@mantine/core';
import { IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { fleetApi, isTrailer, makeModel, VEHICLES_KEY, type Vehicle } from '@/api/fleet';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { PageHeader } from '@/components/PageHeader';
import { Plate } from '@/components/Plate';
import { cx } from '@/utils/cx';
import { NextDeadline, VehicleStatusChip } from './VehicleStatusChip';
import classes from './FleetPage.module.css';

type KindFilter = 'all' | 'vehicles' | 'trailers';

function matches(v: Vehicle, kind: KindFilter, needle: string): boolean {
  if (kind === 'vehicles' && isTrailer(v.kind)) return false;
  if (kind === 'trailers' && !isTrailer(v.kind)) return false;
  if (!needle) return true;
  const plateNeedle = needle.replace(/\s+/g, '');
  return (
    v.plate.replace(/\s+/g, '').toLowerCase().includes(plateNeedle) ||
    [v.make, v.model, v.vin, v.defaultDriver?.name].some((s) => s?.toLowerCase().includes(needle))
  );
}

/** Fleet list (tractors, trucks, vans and trailers); a row opens the vehicle card in the right panel (/fleet/:id). */
export function FleetPage() {
  const { t, i18n } = useTranslation();
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: VEHICLES_KEY, queryFn: fleetApi.list });
  const canManage = hasPrivilege(me, PRIV.FLEET_VEHICLE_MANAGE);
  const [kind, setKind] = useState<KindFilter>('all');
  const [search, setSearch] = useState('');

  const km = new Intl.NumberFormat(i18n.resolvedLanguage ?? 'en');
  const open = (v: Vehicle) => void navigate(`/fleet/${v.id}`);
  const needle = search.trim().toLowerCase();
  const rows = useMemo(() => (q.data ?? []).filter((v) => matches(v, kind, needle)), [q.data, kind, needle]);
  const notReady = q.isError && q.error instanceof ApiError && q.error.status === 404;

  return (
    <>
      <PageHeader
        title={t('fleet.title')}
        subtitle={q.data?.length}
        actions={
          canManage &&
          !notReady && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/fleet/new')}>
              {t('fleet.add')}
            </Button>
          )
        }
      />
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : notReady ? (
        // The backend has no fleet module yet: show the placeholder instead of an error.
        <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState text={t('fleet.empty')} />
      ) : (
        <>
          <Group gap="sm" mb="sm" wrap="wrap">
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={16} />}
              placeholder={t('fleet.search')}
              aria-label={t('fleet.search')}
              value={search}
              onChange={(e) => setSearch(e.currentTarget.value)}
            />
            <SegmentedControl
              value={kind}
              onChange={(v) => setKind(v as KindFilter)}
              data={[
                { value: 'all', label: t('fleet.filter.all') },
                { value: 'vehicles', label: t('fleet.filter.vehicles') },
                { value: 'trailers', label: t('fleet.filter.trailers') },
              ]}
            />
          </Group>
          {rows.length === 0 ? (
            <EmptyState text={t('fleet.nothing_found')} />
          ) : (
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('fleet.plate')}</Table.Th>
                    <Table.Th>{t('fleet.kind')}</Table.Th>
                    <Table.Th>{t('fleet.make_model')}</Table.Th>
                    <Table.Th>{t('fleet.status')}</Table.Th>
                    <Table.Th>{t('fleet.driver')}</Table.Th>
                    <Table.Th>{t('fleet.next_deadline')}</Table.Th>
                    <Table.Th className={classes.num}>{t('fleet.odometer')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((v) => {
                    const selected = String(v.id) === id;
                    return (
                      <Table.Tr
                        key={v.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(v)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(v);
                          }
                        }}
                      >
                        <Table.Td>
                          <Plate>{v.plate}</Plate>
                        </Table.Td>
                        <Table.Td>{t(`vehicle.kind.${v.kind}`)}</Table.Td>
                        <Table.Td>{makeModel(v) ?? <Text span c="dimmed">—</Text>}</Table.Td>
                        <Table.Td>
                          <VehicleStatusChip status={v.status} />
                        </Table.Td>
                        <Table.Td>
                          {isTrailer(v.kind) ? (
                            <Text span c="dimmed">—</Text>
                          ) : (
                            (v.defaultDriver?.name ?? (
                              <Text span c="dimmed">
                                {t('fleet.no_driver')}
                              </Text>
                            ))
                          )}
                        </Table.Td>
                        <Table.Td>
                          {v.nextDocument ? <NextDeadline deadline={v.nextDocument} /> : <Text span c="dimmed">—</Text>}
                        </Table.Td>
                        <Table.Td className={cx(classes.num, classes.mono)}>
                          {v.odometerKm != null ? `${km.format(v.odometerKm)} km` : <Text span c="dimmed">—</Text>}
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
