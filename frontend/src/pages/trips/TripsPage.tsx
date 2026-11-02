import { useState } from 'react';
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@mantine/hooks';
import { Button, Group, Pagination, Paper, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconArrowRight, IconPlus, IconSearch, IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { TRIP_STATUSES, TRIPS_KEY, tripsApi, type Trip, type TripFilter, type TripStatus } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { Plate } from '@/components/Plate';
import { cx } from '@/utils/cx';
import { useCrewOptions } from './crew';
import { TripStatusChip } from './TripChips';
import { formatInstantShort } from './tripFormat';
import classes from './Trips.module.css';

const PAGE_SIZE = 50;

/** First loading and last unloading (or first and last stop) of a trip. */
function tripRoute(t: Trip): [string, string] | null {
  if (t.stops.length === 0) return null;
  const from = t.stops.find((s) => s.kind === 'LOADING') ?? t.stops[0];
  const to = [...t.stops].reverse().find((s) => s.kind === 'UNLOADING') ?? t.stops[t.stops.length - 1];
  const label = (s: Trip['stops'][number]) => `${s.name || s.address}, ${s.country}`;
  return [label(from), label(to)];
}

/** Trips, newest planned start first; filters in the URL; a row opens the trip in the right panel. */
export function TripsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [q] = useDebouncedValue(search.trim(), 250);
  const canManage = hasPrivilege(me, PRIV.TRIP_TRIP_MANAGE);
  const crew = useCrewOptions([params.get('vehicle'), params.get('driver')]);

  const page = Number(params.get('page') ?? '1');
  const filter: TripFilter = {
    status: (params.get('status') as TripStatus | null) ?? undefined,
    vehicleId: params.get('vehicle') ? Number(params.get('vehicle')) : undefined,
    driverId: params.get('driver') ? Number(params.get('driver')) : undefined,
    q: q || undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...TRIPS_KEY, 'list', filter], queryFn: () => tripsApi.list(filter), placeholderData: keepPreviousData });

  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (trip: Trip) => void navigate({ pathname: `/trips/${trip.id}`, search: params.toString() });
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
              placeholder={t('trips.search')}
              aria-label={t('trips.search')}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                set('page', null);
              }}
            />
            <Select
              placeholder={t('users.status')}
              aria-label={t('users.status')}
              data={TRIP_STATUSES.map((s) => ({ value: s, label: t(`trip.status.${s}`) }))}
              value={params.get('status')}
              onChange={(v) => set('status', v)}
              clearable
              w={160}
            />
            {!crew.error && (
              <>
                <Select
                  placeholder={t('trips.vehicle')}
                  aria-label={t('trips.vehicle')}
                  data={crew.vehicles}
                  value={params.get('vehicle')}
                  onChange={(v) => set('vehicle', v)}
                  clearable
                  searchable
                  w={190}
                />
                <Select
                  placeholder={t('trips.driver')}
                  aria-label={t('trips.driver')}
                  data={crew.drivers}
                  value={params.get('driver')}
                  onChange={(v) => set('driver', v)}
                  clearable
                  searchable
                  w={190}
                />
              </>
            )}
            <TextInput type="date" aria-label={t('orders.date_from')} title={t('orders.date_from')} value={params.get('from') ?? ''} onChange={(e) => set('from', e.currentTarget.value || null)} w={150} />
            <TextInput type="date" aria-label={t('orders.date_to')} title={t('orders.date_to')} value={params.get('to') ?? ''} onChange={(e) => set('to', e.currentTarget.value || null)} w={150} />
            {list.data && (
              <Text c="dimmed" mb={6}>
                {list.data.total}
              </Text>
            )}
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/trips/new')}>
              {t('trips.add')}
            </Button>
          )}
        </Group>

        {list.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : list.isError ? (
          <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
        ) : list.data.items.length === 0 ? (
          <EmptyState text={t('trips.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('trips.number')}</Table.Th>
                    <Table.Th>{t('users.status')}</Table.Th>
                    <Table.Th>{t('trips.vehicle')}</Table.Th>
                    <Table.Th className={classes.hideSm}>{t('trips.crew')}</Table.Th>
                    <Table.Th>{t('orders.route')}</Table.Th>
                    <Table.Th>{t('trips.planned_start')}</Table.Th>
                    <Table.Th className={classes.hideSm}>{t('trips.planned_end')}</Table.Th>
                    <Table.Th className={cx(classes.num, classes.hideSm)}>{t('trips.stops')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {list.data.items.map((trip) => {
                    const selected = String(trip.id) === id;
                    const route = tripRoute(trip);
                    const done = trip.stops.filter((s) => s.status !== 'PLANNED').length;
                    return (
                      <Table.Tr
                        key={trip.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(trip)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(trip);
                          }
                        }}
                      >
                        <Table.Td className={classes.mono}>{trip.number}</Table.Td>
                        <Table.Td>
                          <TripStatusChip status={trip.status} />
                        </Table.Td>
                        <Table.Td>
                          <Group gap={4} wrap="nowrap">
                            <Plate>{trip.vehicle.plate}</Plate>
                            {trip.trailer && <Plate>{trip.trailer.plate}</Plate>}
                          </Group>
                        </Table.Td>
                        <Table.Td className={classes.hideSm}>{trip.drivers.map((d) => d.name).join(', ') || '—'}</Table.Td>
                        <Table.Td>
                          {route ? (
                            <span className={classes.route}>
                              {route[0]}
                              <IconArrowRight size={14} />
                              {route[1]}
                            </span>
                          ) : (
                            '—'
                          )}
                        </Table.Td>
                        <Table.Td className={classes.mono}>{formatInstantShort(trip.plannedStart, lang)}</Table.Td>
                        <Table.Td className={cx(classes.mono, classes.hideSm)}>{formatInstantShort(trip.plannedEnd, lang)}</Table.Td>
                        <Table.Td className={cx(classes.num, classes.hideSm)}>{trip.stops.length ? `${done}/${trip.stops.length}` : '—'}</Table.Td>
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
