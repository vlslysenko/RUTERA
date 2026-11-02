import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Alert, Anchor, Button, Group, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowBackUp, IconBan, IconCheck, IconFlag, IconPencil, IconPlayerPlay, IconPlayerSkipForward, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { ORDERS_KEY, TRIPS_KEY, tripsApi, tripToInput, type StopStatus, type Trip, type TripStatus, type TripStop } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { countryName } from '@/utils/countries';
import { cx } from '@/utils/cx';
import { CmrLinks } from './CmrLinks';
import { StopStatusChip, TripStatusChip } from './TripChips';
import { formatInstantShort, formatMoney } from './tripFormat';
import classes from './Trips.module.css';

const LIST = '/trips';
const DASH = '—';

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      {children}
    </section>
  );
}

/** One stop with its quick marks: done / skipped / back to planned (PATCH, no full form). */
function StopRow({ trip, stop, canMark }: { trip: Trip; stop: TripStop; canMark: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const order = stop.orderId ? trip.orders.find((o) => o.id === stop.orderId) : undefined;

  const mark = async (status: StopStatus) => {
    setBusy(true);
    try {
      const saved = await tripsApi.markStop(trip.id, stop.id, status);
      qc.setQueryData<Trip>([...TRIPS_KEY, String(trip.id)], (old) =>
        old ? { ...old, stops: old.stops.map((s) => (s.id === saved.id ? saved : s)) } : old,
      );
      void qc.invalidateQueries({ queryKey: [...TRIPS_KEY, 'list'] });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className={cx(classes.stop, stop.status !== 'PLANNED' && classes.stopDone)}>
      <span className={classes.seq}>{stop.seq}</span>
      <div>
        <div className={classes.stopTitle}>
          {t(`trip.stop.kind.${stop.kind}`)}
          {stop.name ? ` · ${stop.name}` : ''}
        </div>
        <div className={classes.stopSub}>
          {stop.address}, {countryName(stop.country, lang)}
        </div>
        <Group gap={6} mt={4} wrap="wrap">
          <StopStatusChip status={stop.status} />
          <span className={classes.stopSub}>
            {stop.actualAt
              ? `${t('trips.stop_actual')}: ${formatInstantShort(stop.actualAt, lang)}`
              : stop.plannedAt
                ? `${t('trips.stop_planned')}: ${formatInstantShort(stop.plannedAt, lang)}`
                : null}
            {order && <span className={classes.mono}> · {order.number}</span>}
          </span>
        </Group>
      </div>
      {canMark && (
        <Stack gap={4}>
          {stop.status === 'PLANNED' ? (
            <>
              <Tooltip label={t('trips.stop_done')}>
                <ActionIcon variant="light" color="green" loading={busy} onClick={() => void mark('DONE')} aria-label={t('trips.stop_done')}>
                  <IconCheck size={16} />
                </ActionIcon>
              </Tooltip>
              <Tooltip label={t('trips.stop_skip')}>
                <ActionIcon variant="subtle" color="gray" disabled={busy} onClick={() => void mark('SKIPPED')} aria-label={t('trips.stop_skip')}>
                  <IconPlayerSkipForward size={16} />
                </ActionIcon>
              </Tooltip>
            </>
          ) : (
            <Tooltip label={t('trips.stop_undo')}>
              <ActionIcon variant="subtle" color="gray" loading={busy} onClick={() => void mark('PLANNED')} aria-label={t('trips.stop_undo')}>
                <IconArrowBackUp size={16} />
              </ActionIcon>
            </Tooltip>
          )}
        </Stack>
      )}
    </li>
  );
}

function Overview({ trip, canMark }: { trip: Trip; canMark: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const canSeeOrders = hasPrivilege(me, PRIV.TRIP_ORDER_VIEW);
  const driver = (role: 'MAIN' | 'SECOND') => {
    const d = trip.drivers.find((x) => x.role === role);
    return d ? (
      <Anchor component={Link} to={`/drivers/${d.id}`}>
        {d.name}
      </Anchor>
    ) : (
      DASH
    );
  };
  const km = (v: number | null) => (v != null ? `${new Intl.NumberFormat(lang).format(v)} km` : DASH);

  const crew: KeyValueItem[] = [
    {
      label: t('trips.vehicle'),
      value: (
        <Anchor component={Link} to={`/fleet/${trip.vehicle.id}`}>
          <Plate>{trip.vehicle.plate}</Plate>
        </Anchor>
      ),
    },
    {
      label: t('trips.trailer'),
      value: trip.trailer ? (
        <Anchor component={Link} to={`/fleet/${trip.trailer.id}`}>
          <Plate>{trip.trailer.plate}</Plate>
        </Anchor>
      ) : (
        DASH
      ),
    },
    { label: t('trips.main_driver'), value: driver('MAIN') },
    { label: t('trips.second_driver'), value: driver('SECOND') },
  ];
  const schedule: KeyValueItem[] = [
    { label: t('trips.planned_start'), value: formatInstantShort(trip.plannedStart, lang) },
    { label: t('trips.planned_end'), value: formatInstantShort(trip.plannedEnd, lang) },
    { label: t('trips.actual_start'), value: formatInstantShort(trip.actualStart, lang) },
    { label: t('trips.actual_end'), value: formatInstantShort(trip.actualEnd, lang) },
    { label: t('trips.km_planned'), value: km(trip.kmPlanned) },
    { label: t('trips.km_actual'), value: km(trip.kmActual) },
  ];

  return (
    <Stack gap="lg">
      <Section title={t('trips.section.stops')}>
        {trip.stops.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('trips.no_stops')}
          </Text>
        ) : (
          <ol className={classes.stops}>
            {trip.stops.map((s) => (
              <StopRow key={s.id} trip={trip} stop={s} canMark={canMark} />
            ))}
          </ol>
        )}
      </Section>
      <Section title={t('trips.crew')}>
        <KeyValueList items={crew} />
      </Section>
      <Section title={t('trips.section.schedule')}>
        <KeyValueList items={schedule} />
      </Section>
      <Section title={t('trips.tab.orders')}>
        {trip.orders.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('trips.no_orders')}
          </Text>
        ) : (
          <KeyValueList
            items={trip.orders.map((o) => ({
              label: canSeeOrders ? (
                <Anchor component={Link} to={`/trips/orders/${o.id}`} className={classes.mono}>
                  {o.number}
                </Anchor>
              ) : (
                <span className={classes.mono}>{o.number}</span>
              ),
              value: `${o.customer.name} · ${formatMoney(o.price, o.currency, lang)}`,
            }))}
          />
        )}
      </Section>
      {trip.notes && (
        <Section title={t('drivers.notes')}>
          <Text size="sm" className={classes.notes}>
            {trip.notes}
          </Text>
        </Section>
      )}
    </Stack>
  );
}

/** Next statuses offered as buttons: start, finish, cancel. */
const NEXT: Record<TripStatus, TripStatus[]> = {
  PLANNED: ['RUNNING', 'CANCELLED'],
  RUNNING: ['DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: ['PLANNED'],
};
const STATUS_ACTION: Record<TripStatus, [string, typeof IconCheck]> = {
  PLANNED: ['trips.action.replan', IconArrowBackUp],
  RUNNING: ['trips.action.start', IconPlayerPlay],
  DONE: ['trips.action.finish', IconFlag],
  CANCELLED: ['trips.action.cancel', IconBan],
};

/** Trip card: stops with quick marks, crew, schedule and orders; in the right panel and as a page. */
export function TripDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...TRIPS_KEY, id], queryFn: () => tripsApi.get(id), enabled: id !== '' });
  const trip = q.data;
  const canManage = hasPrivilege(me, PRIV.TRIP_TRIP_MANAGE);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState<TripStatus | 'delete' | null>(null);
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setConfirmDelete(false);
  }

  const setStatus = async (status: TripStatus) => {
    if (!trip) return;
    setBusy(status);
    try {
      const saved = await tripsApi.update(trip.id, { ...tripToInput(trip), status });
      qc.setQueryData([...TRIPS_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...TRIPS_KEY, 'list'] });
      // Orders follow the trip status.
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
      notifications.show({ color: 'green', message: t('trips.status_changed', { status: t(`trip.status.${status}`) }) });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    setBusy('delete');
    try {
      await tripsApi.remove(id);
      notifications.show({ color: 'green', message: t('trips.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: TRIPS_KEY, predicate: (x) => x.queryKey[TRIPS_KEY.length] !== id });
      void qc.invalidateQueries({ queryKey: ORDERS_KEY });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(null);
    }
  };

  // DELETE answers 409 for running or finished trips; the button is offered only for the others.
  const deletable = trip && (trip.status === 'PLANNED' || trip.status === 'CANCELLED');
  const actions =
    trip && canManage ? (
      <>
        {NEXT[trip.status].map((s) => {
          const [label, Icon] = STATUS_ACTION[s];
          return (
            <Button
              key={s}
              size="xs"
              variant={s === 'CANCELLED' || s === 'PLANNED' ? 'default' : 'filled'}
              leftSection={<Icon size={14} />}
              onClick={() => void setStatus(s)}
              loading={busy === s}
              disabled={busy !== null && busy !== s}
            >
              {t(label)}
            </Button>
          );
        })}
        <Button variant="default" size="xs" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)} disabled={busy !== null}>
          {t('common.edit')}
        </Button>
        {deletable && (
          <Button variant="default" size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)} disabled={busy !== null}>
            {t('common.delete')}
          </Button>
        )}
      </>
    ) : undefined;

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={`${LIST}/${id}/page`}
      title={trip ? <span className={classes.mono}>{trip.number}</span> : '…'}
      subtitle={trip && [trip.vehicle.plate, trip.trailer?.plate, ...trip.drivers.map((d) => d.name)].filter(Boolean).join(' · ')}
      status={trip && <TripStatusChip status={trip.status} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : trip ? (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('trips.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy === 'delete'}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy !== null}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          <Overview trip={trip} canMark={canManage && trip.status !== 'CANCELLED'} />
          <CmrLinks tripId={trip.id} canCreate={trip.status !== 'CANCELLED'} />
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
