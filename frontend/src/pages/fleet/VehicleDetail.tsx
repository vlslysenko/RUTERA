import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Stack, Tabs, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import {
  fleetApi,
  isTrailer,
  makeModel,
  VEHICLE_DOCUMENT_KINDS,
  VEHICLES_KEY,
  type Vehicle,
  type VehicleDocumentInput,
} from '@/api/fleet';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { UNIT_DOWNLOAD_DAYS } from '@/api/tacho';
import { DeadlineChip, NextDownloadChip } from '@/components/DeadlineChip';
import { DeadlineDocuments } from '@/components/DeadlineDocuments';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { VehicleStatusChip } from './VehicleStatusChip';
import classes from './VehicleDetail.module.css';

const DASH = '—';

/** Number formatting in the UI language; null and undefined become a dash. */
function useFormat() {
  const { i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const num = (value: number | null, digits = 0, unit = '') =>
    value == null
      ? DASH
      : `${new Intl.NumberFormat(lang, { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value)}${unit && ` ${unit}`}`;
  const instant = (iso: string | null) =>
    iso ? new Intl.DateTimeFormat(lang, { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso)) : DASH;
  return { lang, num, instant };
}

function Section({ title, items }: { title: string; items: KeyValueItem[] }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      <KeyValueList items={items} />
    </section>
  );
}

function Overview({ v }: { v: Vehicle }) {
  const { t } = useTranslation();
  const { num, instant } = useFormat();
  const trailer = isTrailer(v.kind);

  const route: KeyValueItem[] = [
    { label: t('fleet.axles'), value: num(v.axles) },
    { label: t('fleet.gross_weight'), value: num(v.grossWeightKg == null ? null : v.grossWeightKg / 1000, 1, 't') },
    { label: t('fleet.height'), value: num(v.heightMm == null ? null : v.heightMm / 1000, 2, 'm') },
  ];
  if (!trailer) {
    route.push(
      { label: t('fleet.euro_class'), value: v.euroClass ? t(`vehicle.euro.${v.euroClass}`) : DASH },
      { label: t('fleet.fuel_type'), value: v.fuelType ? t(`vehicle.fuel.${v.fuelType}`) : DASH },
      { label: t('fleet.consumption'), value: num(v.avgL100km, 1, 'l/100 km') },
    );
  }

  const vehicle: KeyValueItem[] = [
    { label: t('fleet.kind'), value: t(`vehicle.kind.${v.kind}`) },
    { label: t('fleet.make'), value: v.make ?? DASH },
    { label: t('fleet.model'), value: v.model ?? DASH },
    { label: t('fleet.vin'), value: v.vin ? <span className={classes.mono}>{v.vin}</span> : DASH },
  ];
  if (!trailer) {
    vehicle.push(
      { label: t('fleet.odometer'), value: num(v.odometerKm, 0, 'km') },
      { label: t('fleet.tacho'), value: t(`vehicle.tacho.${v.tachoGen}`) },
    );
    if (v.tachoGen !== 'NONE') {
      vehicle.push(
        { label: t('fleet.last_vu_download'), value: instant(v.lastVuDownloadAt) },
        {
          label: t('tacho.next_download'),
          value: <NextDownloadChip lastDownloadAt={v.lastVuDownloadAt} everyDays={UNIT_DOWNLOAD_DAYS} remindDays={14} />,
        },
      );
    }
    vehicle.push(
      {
        label: t('fleet.default_driver'),
        value: v.defaultDriver ? (
          <Anchor component={Link} to={`/drivers/${v.defaultDriver.id}`}>
            {v.defaultDriver.name}
          </Anchor>
        ) : (
          t('fleet.no_driver')
        ),
      },
      {
        label: t('fleet.default_trailer'),
        value: v.defaultTrailer ? (
          <Anchor component={Link} to={`/fleet/${v.defaultTrailer.id}`}>
            <Plate>{v.defaultTrailer.plate}</Plate>
          </Anchor>
        ) : (
          DASH
        ),
      },
    );
  }

  return (
    <Stack gap="lg">
      <Section title={t('fleet.section.route')} items={route} />
      <Section title={t('fleet.section.vehicle')} items={vehicle} />
    </Stack>
  );
}

/** Vehicle card: the same component renders in the right panel (/fleet/:id) and as a page (/fleet/:id/page). */
export function VehicleDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...VEHICLES_KEY, id], queryFn: () => fleetApi.get(id), enabled: id !== '' });
  const v = q.data;
  const canManage = hasPrivilege(me, PRIV.FLEET_VEHICLE_MANAGE);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const remove = async () => {
    setBusy(true);
    try {
      await fleetApi.remove(id);
      notifications.show({ color: 'green', message: t('fleet.deleted') });
      // Leave the card and refresh everything except the deleted vehicle: its own queries would answer 404 (the cache
      // entry goes away by itself once the card is unmounted).
      await navigate('/fleet', { replace: true });
      void qc.invalidateQueries({ queryKey: VEHICLES_KEY, predicate: (q) => q.queryKey[VEHICLES_KEY.length] !== id });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  const actions =
    v && canManage ? (
      <>
        <Button
          variant="default"
          size="xs"
          leftSection={<IconPencil size={14} />}
          onClick={() => void navigate(`/fleet/${id}/edit`)}
          disabled={busy}
        >
          {t('common.edit')}
        </Button>
        <Button
          variant="default"
          size="xs"
          color="red"
          leftSection={<IconTrash size={14} />}
          onClick={() => setConfirmDelete(true)}
          disabled={busy}
        >
          {t('common.delete')}
        </Button>
      </>
    ) : undefined;

  return (
    <DetailDrawer
      mode={mode}
      listPath="/fleet"
      pagePath={`/fleet/${id}/page`}
      title={v ? <Plate size="lg">{v.plate}</Plate> : '…'}
      subtitle={v && [makeModel(v), t(`vehicle.kind.${v.kind}`)].filter(Boolean).join(' · ')}
      status={v && <VehicleStatusChip status={v.status} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : v ? (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('fleet.delete_confirm')}</Text>
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
          {v.nextDocument && (
            <Group gap={6} wrap="wrap">
              <Text size="sm" c="dimmed">
                {t('deadline.next')}:
              </Text>
              <Text size="sm">{t(`vehicle.document.${v.nextDocument.kind}`)}</Text>
              <DeadlineChip validTo={v.nextDocument.validTo} />
            </Group>
          )}
          <Tabs defaultValue="overview" keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="overview">{t('fleet.tab.overview')}</Tabs.Tab>
              <Tabs.Tab value="documents">{t('fleet.tab.documents')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="overview" pt="md">
              <Overview v={v} />
            </Tabs.Panel>
            <Tabs.Panel value="documents" pt="md">
              <DeadlineDocuments
                queryKey={[...VEHICLES_KEY, id, 'documents']}
                invalidateKey={VEHICLES_KEY}
                api={{
                  list: () => fleetApi.documents(id),
                  create: (body) => fleetApi.createDocument(id, body as VehicleDocumentInput),
                  update: (docId, body) => fleetApi.updateDocument(id, docId, body as VehicleDocumentInput),
                  remove: (docId) => fleetApi.removeDocument(id, docId),
                }}
                kinds={VEHICLE_DOCUMENT_KINDS}
                kindLabelPrefix="vehicle.document"
                withCost
                canManage={hasPrivilege(me, PRIV.FLEET_DOCUMENT_MANAGE)}
              />
            </Tabs.Panel>
          </Tabs>
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
