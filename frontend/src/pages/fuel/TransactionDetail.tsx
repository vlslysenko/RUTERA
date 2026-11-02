import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Paper, Select, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconLink, IconPencil, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { FUEL_TX_KEY, fuelApi, type FuelTransaction } from '@/api/fuel';
import { TRIPS_KEY, tripsApi } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { countryName } from '@/utils/countries';
import { idOrNull, useCrewOptions } from '@/pages/trips/crew';
import { formatInstantShort, formatMoney } from '@/pages/trips/tripFormat';
import { MatchChip } from './FuelChips';
import { formatQty } from './TransactionsPage';
import classes from '@/pages/trips/Trips.module.css';

const LIST = '/fuel';
const DASH = '—';

/** Vehicle, driver and trip of a transaction set by hand (match MANUAL). */
function LinkEditor({ tx, onDone }: { tx: FuelTransaction; onDone: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [vehicleId, setVehicleId] = useState<string | null>(tx.vehicle ? String(tx.vehicle.id) : null);
  const [driverId, setDriverId] = useState<string | null>(tx.driver ? String(tx.driver.id) : null);
  const [tripId, setTripId] = useState<string | null>(tx.trip ? String(tx.trip.id) : null);
  const [notes, setNotes] = useState(tx.notes ?? '');
  const [busy, setBusy] = useState(false);
  const crew = useCrewOptions([vehicleId, driverId]);
  const trips = useQuery({
    queryKey: [...TRIPS_KEY, 'list', { vehicleId, size: 50 }],
    queryFn: () => tripsApi.list({ vehicleId: Number(vehicleId), size: 50 }),
    enabled: !!vehicleId,
  });
  const tripOptions = (trips.data?.items ?? []).map((x) => ({ value: String(x.id), label: `${x.number} · ${t(`trip.status.${x.status}`)}` }));
  if (tx.trip && !tripOptions.some((o) => o.value === String(tx.trip?.id))) tripOptions.push({ value: String(tx.trip.id), label: tx.trip.name });

  const save = async () => {
    setBusy(true);
    try {
      const saved = await fuelApi.assign(tx.id, { vehicleId: idOrNull(vehicleId), driverId: idOrNull(driverId), tripId: idOrNull(tripId), notes: notes.trim() || null });
      qc.setQueryData([...FUEL_TX_KEY, String(tx.id)], saved);
      void qc.invalidateQueries({ queryKey: [...FUEL_TX_KEY, 'list'] });
      notifications.show({ color: 'green', message: t('fuel.links_saved') });
      onDone();
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper withBorder p="sm">
      <Stack gap="sm">
        <Group grow align="flex-start">
          <Select
            label={t('trips.vehicle')}
            data={crew.vehicles}
            value={vehicleId}
            onChange={(v) => {
              setVehicleId(v);
              setTripId(null);
            }}
            clearable
            searchable
          />
          <Select label={t('trips.driver')} data={crew.drivers} value={driverId} onChange={setDriverId} clearable searchable />
        </Group>
        <Select label={t('orders.trip')} data={tripOptions} value={tripId} onChange={setTripId} clearable disabled={!vehicleId} />
        <TextInput label={t('drivers.notes')} value={notes} onChange={(e) => setNotes(e.currentTarget.value)} maxLength={500} />
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onDone} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button onClick={() => void save()} loading={busy}>
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </Paper>
  );
}

/** Transaction card: what the file said, what it was matched to; links set by hand; manual ones edited or deleted. */
export function TransactionDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...FUEL_TX_KEY, id], queryFn: () => fuelApi.transaction(id), enabled: id !== '' });
  const tx = q.data;
  const canManage = hasPrivilege(me, PRIV.FUEL_IMPORT_MANAGE);
  const tz = me.firma?.timezone;
  const [linking, setLinking] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setLinking(false);
    setConfirmDelete(false);
  }

  const remove = async () => {
    setBusy(true);
    try {
      await fuelApi.removeTransaction(id);
      notifications.show({ color: 'green', message: t('fuel.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: FUEL_TX_KEY, predicate: (x) => x.queryKey[FUEL_TX_KEY.length] !== id });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  const items: KeyValueItem[] = tx
    ? [
        { label: t('fuel.at'), value: formatInstantShort(tx.at, lang, tz) },
        {
          label: t('trips.vehicle'),
          value: tx.vehicle ? (
            <Anchor component={Link} to={`/fleet/${tx.vehicle.id}`}>
              <Plate>{tx.vehicle.name}</Plate>
            </Anchor>
          ) : (
            DASH
          ),
        },
        { label: t('trips.driver'), value: tx.driver ? <Anchor component={Link} to={`/drivers/${tx.driver.id}`}>{tx.driver.name}</Anchor> : DASH },
        {
          label: t('orders.trip'),
          value: tx.trip ? (
            <Anchor component={Link} to={`/trips/${tx.trip.id}`} className={classes.mono}>
              {tx.trip.name}
            </Anchor>
          ) : (
            DASH
          ),
        },
        { label: t('fuel.card'), value: tx.card ? <span className={classes.mono}>{tx.card.masked}</span> : DASH },
        { label: t('fuel.station'), value: [tx.station, tx.country ? countryName(tx.country, lang) : null].filter(Boolean).join(', ') || DASH },
        { label: t('fuel.product'), value: `${t(`fuel.product.${tx.product}`)}${tx.productRaw ? ` (${tx.productRaw})` : ''}` },
        { label: t('fuel.quantity'), value: formatQty(tx.quantity, tx.unit, lang, t) },
        { label: t('invoices.unit_price'), value: tx.unitPrice != null ? formatMoney(tx.unitPrice, tx.currency, lang) : DASH },
        { label: t('invoices.net'), value: tx.net != null ? formatMoney(tx.net, tx.currency, lang) : DASH },
        { label: t('invoices.vat'), value: tx.vat != null ? formatMoney(tx.vat, tx.currency, lang) : DASH },
        { label: t('invoices.gross'), value: <b>{formatMoney(tx.gross, tx.currency, lang)}</b> },
        { label: t('fuel.odometer'), value: tx.odometerKm != null ? `${new Intl.NumberFormat(lang).format(tx.odometerKm)} km` : DASH },
        { label: t('fuel.source'), value: `${t(`fuel.source.${tx.source}`)}${tx.provider ? ` · ${tx.provider}` : ''}${tx.externalId ? ` · ${tx.externalId}` : ''}` },
        ...(tx.plateRaw ? [{ label: t('fuel.plate_raw'), value: <span className={classes.mono}>{tx.plateRaw}</span> }] : []),
      ]
    : [];

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={`${LIST}/${id}/page`}
      title={tx ? `${t(`fuel.product.${tx.product}`)} · ${formatMoney(tx.gross, tx.currency, lang)}` : '…'}
      subtitle={tx && formatInstantShort(tx.at, lang, tz)}
      status={tx && <MatchChip match={tx.match} />}
      actions={
        tx && canManage ? (
          <>
            <Button size="xs" variant="default" leftSection={<IconLink size={14} />} onClick={() => setLinking(true)} disabled={linking || busy}>
              {t('fuel.set_links')}
            </Button>
            {tx.source === 'MANUAL' && (
              <>
                <Button size="xs" variant="default" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)} disabled={busy}>
                  {t('common.edit')}
                </Button>
                <Button size="xs" variant="default" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)} disabled={busy}>
                  {t('common.delete')}
                </Button>
              </>
            )}
          </>
        ) : undefined
      }
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : tx ? (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('fuel.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          {linking && <LinkEditor tx={tx} onDone={() => setLinking(false)} />}
          <KeyValueList items={items} />
          {tx.notes && (
            <Text size="sm" className={classes.notes}>
              {tx.notes}
            </Text>
          )}
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
