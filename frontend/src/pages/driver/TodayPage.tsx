import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Alert, Anchor, Button, Group, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowRight, IconCheck, IconMapPin, IconSignature } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { EXPLANATIONS_KEY, explanationsApi } from '@/api/explanations';
import { HOS_KEY, hosApi } from '@/api/hos';
import { driverHrKey, hrApi } from '@/api/hr';
import { TRIPS_KEY, tripsApi, type Trip, type TripStop } from '@/api/trips';
import { useUser } from '@/auth/useAuth';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { OfflineNote } from '@/components/Offline';
import { formatMinutes, shiftDays, ymdIn } from '@/pages/tacho/hosFormat';
import { TripStatusChip } from '@/pages/trips/TripChips';
import { TripFiles } from '@/pages/trips/TripFiles';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { countryName } from '@/utils/countries';
import { cx } from '@/utils/cx';
import { formatDate } from '@/utils/deadline';
import { Card, PartError, Stat, Waiting } from './DriverParts';
import classes from './DriverApp.module.css';

/** Only the first letter: month names stay lower case where the language writes them so. */
const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** Map search for a stop: opens the phone's maps app or the browser. */
const mapsUrl = (s: TripStop) => `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent([s.name, s.address, s.country].filter(Boolean).join(', '))}`;

function TripCard({ trip, title }: { trip: Trip; title: string }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const tz = user.firma?.timezone;
  const qc = useQueryClient();
  const next = trip.status === 'RUNNING' ? trip.stops.find((s) => s.status === 'PLANNED') : undefined;
  const coDriver = trip.drivers.find((d) => d.id !== user.driver?.id);
  // The time of the tap is kept: offline the mark waits and is sent when the connection is back.
  const mark = useMutation({
    mutationFn: ({ stop, at }: { stop: TripStop; at: string }) => tripsApi.markStop(trip.id, stop.id, 'DONE', at),
    onSuccess: () => void qc.invalidateQueries({ queryKey: TRIPS_KEY }),
    onError: (e) => notifications.show({ color: 'red', message: t(errorCode(e)) }),
  });

  return (
    <Card title={title} right={<TripStatusChip status={trip.status} />}>
      <Stack gap={8}>
        <div>
          <Text fw={700} className={classes.mono}>
            {trip.number}
          </Text>
          <Text size="sm" className={classes.mono}>
            {[trip.vehicle.plate, trip.trailer?.plate].filter(Boolean).join(' / ')}
          </Text>
          {coDriver && (
            <Text size="sm" c="dimmed">
              {t('driver_app.co_driver', { name: coDriver.name })}
            </Text>
          )}
          {trip.plannedStart && (
            <Text size="sm" c="dimmed">
              {formatInstantShort(trip.plannedStart, lang, tz)}
              {trip.plannedEnd ? ` – ${formatInstantShort(trip.plannedEnd, lang, tz)}` : ''}
            </Text>
          )}
        </div>
        <ol className={classes.stops}>
          {trip.stops.map((s) => (
            <li key={s.id} className={cx(classes.stop, s === next && classes.stopNext, s.status !== 'PLANNED' && classes.stopDone)}>
              <span className={classes.seq}>{s.status === 'DONE' ? <IconCheck size={14} /> : s.seq}</span>
              <div style={{ minWidth: 0 }}>
                <Text size="sm" fw={600}>
                  {t(`trip.stop.kind.${s.kind}`)}
                  {s.status === 'SKIPPED' ? ` · ${t('trip.stop.status.SKIPPED')}` : ''}
                </Text>
                <Text size="sm" truncate>
                  {[s.name, s.address].filter(Boolean).join(', ')}, {countryName(s.country, lang)}
                </Text>
                {(s.actualAt ?? s.plannedAt) && (
                  <Text size="xs" c="dimmed" className={classes.mono}>
                    {formatInstantShort((s.actualAt ?? s.plannedAt)!, lang, tz)}
                  </Text>
                )}
              </div>
              <Group gap={4} wrap="nowrap">
                {s.status === 'PLANNED' && (
                  <Tooltip label={t('driver_app.navigate')}>
                    <ActionIcon component="a" href={mapsUrl(s)} target="_blank" rel="noopener noreferrer" variant="light" size={44} aria-label={t('driver_app.navigate')}>
                      <IconMapPin size={20} />
                    </ActionIcon>
                  </Tooltip>
                )}
                {s === next && (
                  <Tooltip label={t('driver_app.stop_done')}>
                    <Button
                      size="md"
                      onClick={() => mark.mutate({ stop: s, at: new Date().toISOString() })}
                      loading={mark.isPending && !mark.isPaused}
                      disabled={mark.isPaused}
                      aria-label={t('driver_app.stop_done')}
                    >
                      <IconCheck size={18} />
                    </Button>
                  </Tooltip>
                )}
              </Group>
            </li>
          ))}
        </ol>
        {mark.isPaused && <OfflineNote text={t('offline.queued')} />}
        <h3 className={classes.cardTitle} style={{ marginTop: 8 }}>
          {t('trip_files.title')}
        </h3>
        <TripFiles trip={trip} canUpload canDeleteAll={false} driver />
      </Stack>
    </Card>
  );
}

/** "Today": the absence of the day, driving time left, the current or next trip with its stops, later trips. */
export function TodayPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const driverId = user.driver!.id;
  const tz = user.firma?.timezone ?? 'UTC';
  const today = ymdIn(new Date().toISOString(), tz);

  const trips = useQuery({ queryKey: [...TRIPS_KEY, 'list', { driverId, size: 50 }], queryFn: () => tripsApi.list({ driverId, size: 50 }) });
  const hos = useQuery({
    queryKey: [...HOS_KEY, driverId, shiftDays(today, -6), today],
    queryFn: () => hosApi.activity(driverId, shiftDays(today, -6), today),
    retry: false,
  });
  // Explanations waiting for the driver's signature (also asked for by a push).
  const pendingFilter = { driverId, status: 'PENDING' as const, size: 20 };
  const pending = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'list', pendingFilter], queryFn: () => explanationsApi.list(pendingFilter), retry: false });
  const absence = useQuery({
    queryKey: [...driverHrKey(driverId, 'absences'), today, today],
    queryFn: () => hrApi.absences(driverId, today, today),
    retry: false,
  });

  const items = trips.data?.items ?? [];
  const running = items.find((x) => x.status === 'RUNNING');
  const planned = items.filter((x) => x.status === 'PLANNED').sort((a, b) => (a.plannedStart ?? '').localeCompare(b.plannedStart ?? ''));
  const focus = running ?? planned[0];
  const later = planned.filter((x) => x !== focus).slice(0, 3);
  const day = hos.data?.days.at(-1);
  const away = absence.data?.[0];

  return (
    <Stack gap="sm">
      <Text fw={600}>{capitalize(new Intl.DateTimeFormat(lang, { weekday: 'long', day: 'numeric', month: 'long', timeZone: tz }).format(new Date()))}</Text>
      {(pending.data?.total ?? 0) > 0 && (
        <Alert variant="light" color="orange" icon={<IconSignature size={18} />} p="sm">
          <Stack gap={6}>
            <Text size="sm">{t('explanation.pending_today', { count: pending.data!.total })}</Text>
            <Button component={Link} to={pending.data!.total === 1 ? `/driver/explanations/${pending.data!.items[0].id}` : '/driver/explanations'} size="md" color="orange" w="fit-content">
              {t('explanation.open')}
            </Button>
          </Stack>
        </Alert>
      )}
      {away && (
        <Alert variant="light" color="teal">
          {t('driver_app.absent_today', { kind: t(`absence.kind.${away.kind}`), to: formatDate(away.toDate, lang) })}
        </Alert>
      )}
      <Card title={t('driver_app.hours_now')} right={<Anchor component={Link} to="/driver/hours" size="sm">{t('driver_app.more')}</Anchor>}>
        {hos.isPending ? (
          <Waiting query={hos} />
        ) : (hos.isError && hos.data === undefined) || !day ? (
          <PartError error={hos.error} />
        ) : (
          <div className={classes.stats}>
            <Stat label={t('hos.summary.left_today')} hint={t('hint.hos.left_today')} value={formatMinutes(t, day.remainingDailyMin)} />
            <Stat label={t('hos.summary.left_week')} hint={t('hint.hos.left_week')} value={formatMinutes(t, day.remainingWeeklyMin)} />
            <Stat label={t('hos.summary.weekly_rest_by')} hint={t('hint.hos.weekly_rest_by')} value={day.nextWeeklyRestBy ? formatInstantShort(day.nextWeeklyRestBy, lang, tz) : '—'} />
            <Stat label={t('driver_app.violations_week')} value={String(hos.data.days.reduce((n, d) => n + d.violations, 0))} />
          </div>
        )}
      </Card>
      {trips.isPending ? (
        trips.fetchStatus === 'paused' ? (
          <Card>
            <OfflineNote />
          </Card>
        ) : (
          <FullScreenLoader fullScreen={false} />
        )
      ) : trips.isError && trips.data === undefined ? (
        <Card>
          <PartError error={trips.error} />
        </Card>
      ) : focus ? (
        <TripCard trip={focus} title={t(running ? 'driver_app.trip_now' : 'driver_app.trip_next')} />
      ) : (
        <Card>
          <Text size="sm" c="dimmed">
            {t('driver_app.no_trips')}
          </Text>
        </Card>
      )}
      {later.length > 0 && (
        <Card title={t('driver_app.trips_later')}>
          {later.map((x) => {
            const from = x.stops.find((s) => s.kind === 'LOADING') ?? x.stops[0];
            const to = [...x.stops].reverse().find((s) => s.kind === 'UNLOADING') ?? x.stops.at(-1);
            return (
              <div key={x.id} className={classes.row}>
                <div style={{ minWidth: 0 }}>
                  <Text size="sm" fw={600} className={classes.mono}>
                    {x.number}
                  </Text>
                  <Text size="sm" truncate style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    {from?.address ?? '—'} <IconArrowRight size={12} /> {to?.address ?? '—'}
                  </Text>
                </div>
                <Text size="sm" c="dimmed" className={classes.mono}>
                  {x.plannedStart ? formatInstantShort(x.plannedStart, lang, tz) : '—'}
                </Text>
              </div>
            );
          })}
        </Card>
      )}
    </Stack>
  );
}
