import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Stack, Tabs, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DRIVER_DOCUMENT_KINDS, DRIVERS_KEY, driversApi, type Driver, type DriverDocumentInput } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { VEHICLES_KEY } from '@/api/fleet';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { CARD_DOWNLOAD_DAYS } from '@/api/tacho';
import { DeadlineChip, NextDownloadChip } from '@/components/DeadlineChip';
import { DeadlineDocuments } from '@/components/DeadlineDocuments';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { countryName } from '@/utils/countries';
import { formatDate } from '@/utils/deadline';
import { DriverStatusChip } from './DriverStatusChip';
import classes from './DriversPage.module.css';

const DASH = '—';

function Section({ title, items }: { title: string; items: KeyValueItem[] }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      <KeyValueList items={items} />
    </section>
  );
}

function Overview({ d }: { d: Driver }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const date = (iso: string | null) => (iso ? formatDate(iso, lang) : DASH);
  const instant = (iso: string | null) =>
    iso ? new Intl.DateTimeFormat(lang, { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso)) : DASH;

  const personal: KeyValueItem[] = [
    { label: t('drivers.birth_date'), value: date(d.birthDate) },
    { label: t('drivers.phone'), value: d.phone ? <Anchor href={`tel:${d.phone.replace(/\s+/g, '')}`}>{d.phone}</Anchor> : DASH },
    { label: t('drivers.email'), value: d.email ? <Anchor href={`mailto:${d.email}`}>{d.email}</Anchor> : DASH },
    { label: t('drivers.country'), value: d.country ? countryName(d.country, lang) : DASH },
  ];
  const work: KeyValueItem[] = [
    { label: t('drivers.employment_from'), value: date(d.employmentFrom) },
    { label: t('drivers.employment_to'), value: date(d.employmentTo) },
    {
      label: t('drivers.vehicle'),
      value: d.defaultVehicle ? (
        <Anchor component={Link} to={`/fleet/${d.defaultVehicle.id}`}>
          <Plate>{d.defaultVehicle.plate}</Plate>
        </Anchor>
      ) : (
        t('drivers.no_vehicle')
      ),
    },
  ];
  if (d.user) work.push({ label: t('drivers.app_login'), value: <span className={classes.mono}>{d.user.login}</span> });
  const card: KeyValueItem[] = d.card
    ? [
        { label: t('drivers.card_number'), value: <span className={classes.mono}>{d.card.cardNumber}</span> },
        { label: t('drivers.card_country'), value: countryName(d.card.issuingCountry, lang) },
        { label: t('drivers.card_valid_to'), value: d.card.validTo ? <DeadlineChip validTo={d.card.validTo} /> : DASH },
        { label: t('drivers.card_last_download'), value: instant(d.card.lastDownloadAt) },
        {
          label: t('tacho.next_download'),
          value: <NextDownloadChip lastDownloadAt={d.card.lastDownloadAt} everyDays={CARD_DOWNLOAD_DAYS} remindDays={7} />,
        },
      ]
    : [{ label: t('drivers.card'), value: t('drivers.no_card') }];

  return (
    <Stack gap="lg">
      <Section title={t('drivers.section.personal')} items={personal} />
      <Section title={t('drivers.section.work')} items={work} />
      <Section title={t('drivers.section.card')} items={card} />
      {d.notes && (
        <section className={classes.section}>
          <h3 className={classes.sectionTitle}>{t('drivers.notes')}</h3>
          <Text size="sm" className={classes.notes}>
            {d.notes}
          </Text>
        </section>
      )}
    </Stack>
  );
}

/** Driver card: the same component renders in the right panel (/drivers/:id) and as a page (/drivers/:id/page). */
export function DriverDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...DRIVERS_KEY, id], queryFn: () => driversApi.get(id), enabled: id !== '' });
  const d = q.data;
  const canManage = hasPrivilege(me, PRIV.DRIVER_MANAGE);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const remove = async () => {
    setBusy(true);
    try {
      await driversApi.remove(id);
      notifications.show({ color: 'green', message: t('drivers.deleted') });
      // Leave the card and refresh everything except the deleted driver: its own queries would answer 404 (the cache
      // entry goes away by itself once the card is unmounted).
      await navigate('/drivers', { replace: true });
      void qc.invalidateQueries({ queryKey: DRIVERS_KEY, predicate: (q) => q.queryKey[DRIVERS_KEY.length] !== id });
      // Vehicles lose him as their default driver.
      void qc.invalidateQueries({ queryKey: VEHICLES_KEY });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  const actions =
    d && canManage ? (
      <>
        <Button
          variant="default"
          size="xs"
          leftSection={<IconPencil size={14} />}
          onClick={() => void navigate(`/drivers/${id}/edit`)}
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
      listPath="/drivers"
      pagePath={`/drivers/${id}/page`}
      title={d?.name ?? '…'}
      subtitle={d && ([d.defaultVehicle?.plate, d.phone].filter(Boolean).join(' · ') || undefined)}
      status={d && <DriverStatusChip status={d.status} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : d ? (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('drivers.delete_confirm')}</Text>
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
          {d.nextDocument && (
            <Group gap={6} wrap="wrap">
              <Text size="sm" c="dimmed">
                {t('deadline.next')}:
              </Text>
              <Text size="sm">{t(`driver.document.${d.nextDocument.kind}`)}</Text>
              <DeadlineChip validTo={d.nextDocument.validTo} />
            </Group>
          )}
          <Tabs defaultValue="overview" keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="overview">{t('drivers.tab.overview')}</Tabs.Tab>
              <Tabs.Tab value="documents">{t('drivers.tab.documents')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="overview" pt="md">
              <Overview d={d} />
            </Tabs.Panel>
            <Tabs.Panel value="documents" pt="md">
              <DeadlineDocuments
                queryKey={[...DRIVERS_KEY, id, 'documents']}
                invalidateKey={DRIVERS_KEY}
                api={{
                  list: () => driversApi.documents(id),
                  create: (body) => driversApi.createDocument(id, body as DriverDocumentInput),
                  update: (docId, body) => driversApi.updateDocument(id, docId, body as DriverDocumentInput),
                  remove: (docId) => driversApi.removeDocument(id, docId),
                }}
                kinds={DRIVER_DOCUMENT_KINDS}
                kindLabelPrefix="driver.document"
                canManage={canManage}
              />
            </Tabs.Panel>
          </Tabs>
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
