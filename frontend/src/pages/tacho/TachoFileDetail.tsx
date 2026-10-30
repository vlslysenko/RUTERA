import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Paper, Select, Stack, Table, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconLink, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { saveBlob } from '@/api/client';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { fleetApi, isTrailer, VEHICLES_KEY } from '@/api/fleet';
import { isCardSuggestion, TACHO_FILES_KEY, tachoApi, type TachoFileDetail as Detail } from '@/api/tacho';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DeadlineChip } from '@/components/DeadlineChip';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { countryName } from '@/utils/countries';
import { formatInstant, formatPeriod, formatSize } from './format';
import { TachoKindLabel, TachoOwner, WarningsChip } from './TachoChips';
import classes from './TachoFilesPage.module.css';

const DASH = '—';

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      {children}
    </section>
  );
}

/**
 * Assignment of the file: pick a driver (card) or a vehicle (unit) by hand; when nothing matched, also create the
 * driver or vehicle from the data in the file.
 */
function Assignment({ f }: { f: Detail }) {
  const { t } = useTranslation();
  const me = useUser();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const card = f.kind === 'DRIVER_CARD';
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list, enabled: card && hasPrivilege(me, PRIV.DRIVER_VIEW) });
  const vehicles = useQuery({
    queryKey: VEHICLES_KEY,
    queryFn: fleetApi.list,
    enabled: !card && hasPrivilege(me, PRIV.FLEET_VEHICLE_VIEW),
  });
  const current = card ? f.driver?.id : f.vehicle?.id;
  const [target, setTarget] = useState<string | null>(current != null ? String(current) : null);
  const [busy, setBusy] = useState(false);
  const canCreate = hasPrivilege(me, card ? PRIV.DRIVER_MANAGE : PRIV.FLEET_VEHICLE_MANAGE);

  const options = card
    ? (drivers.data ?? []).filter((d) => d.status === 'ACTIVE' || d.id === current).map((d) => ({ value: String(d.id), label: d.name }))
    : (vehicles.data ?? []).filter((v) => !isTrailer(v.kind)).map((v) => ({ value: String(v.id), label: v.plate }));

  const refresh = async () => {
    await qc.invalidateQueries({ queryKey: TACHO_FILES_KEY });
    void qc.invalidateQueries({ queryKey: DRIVERS_KEY });
    void qc.invalidateQueries({ queryKey: VEHICLES_KEY });
  };

  const assign = async () => {
    if (!target) return;
    setBusy(true);
    try {
      await tachoApi.assign(f.id, card ? { driverId: Number(target) } : { vehicleId: Number(target) });
      await refresh();
      notifications.show({ color: 'green', message: t('tacho.assigned') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    setBusy(true);
    try {
      if (card) {
        const res = await tachoApi.createDriver(f.id);
        await refresh();
        notifications.show({ color: 'green', message: t('tacho.created.driver') });
        await navigate(`/drivers/${res.driver.id}`);
      } else {
        const res = await tachoApi.createVehicle(f.id);
        await refresh();
        notifications.show({ color: 'green', message: t('tacho.created.vehicle') });
        await navigate(`/fleet/${res.vehicle.id}`);
      }
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const s = f.suggestion;
  const suggestionText = s
    ? isCardSuggestion(s)
      ? [`${s.firstName} ${s.lastName}`, s.cardNumber].join(' · ')
      : [s.plate, s.vin].filter(Boolean).join(' · ')
    : null;

  return (
    <Paper withBorder p="sm">
      <Stack gap="xs">
        {f.match === 'NONE' && (
          <Text size="sm">{t(card ? 'tacho.assign.hint_card' : 'tacho.assign.hint_unit')}</Text>
        )}
        <Group align="flex-end" gap="xs" wrap="nowrap">
          <Select
            label={t(card ? 'tacho.assign.driver' : 'tacho.assign.vehicle')}
            data={options}
            value={target}
            onChange={setTarget}
            searchable
            style={{ flex: 1 }}
          />
          <Button
            leftSection={<IconLink size={14} />}
            onClick={() => void assign()}
            loading={busy}
            disabled={!target || target === String(current ?? '')}
          >
            {t('tacho.assign.save')}
          </Button>
        </Group>
        {f.match === 'NONE' && s && canCreate && (
          <Group gap="xs" justify="space-between" wrap="nowrap">
            <Text size="sm" c="dimmed" className={classes.mono}>
              {suggestionText}
            </Text>
            <Button variant="default" leftSection={<IconPlus size={14} />} onClick={() => void create()} disabled={busy}>
              {t(card ? 'tacho.create.driver' : 'tacho.create.vehicle')}
            </Button>
          </Group>
        )}
      </Stack>
    </Paper>
  );
}

/** Uploaded tachograph file: in the right panel (/tacho/:id) and as a page (/tacho/:id/page). */
export function TachoFileDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...TACHO_FILES_KEY, id], queryFn: () => tachoApi.get(id), enabled: id !== '' });
  const f = q.data;
  const canUpload = hasPrivilege(me, PRIV.TACHO_FILE_UPLOAD);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const download = async () => {
    if (!f) return;
    setBusy(true);
    try {
      const { blob, fileName } = await tachoApi.download(f.id);
      saveBlob(blob, fileName ?? f.originalName);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await tachoApi.remove(id);
      qc.removeQueries({ queryKey: [...TACHO_FILES_KEY, id] });
      await qc.invalidateQueries({ queryKey: TACHO_FILES_KEY });
      void qc.invalidateQueries({ queryKey: DRIVERS_KEY });
      void qc.invalidateQueries({ queryKey: VEHICLES_KEY });
      notifications.show({ color: 'green', message: t('tacho.deleted') });
      await navigate('/tacho', { replace: true });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  const actions =
    f && canUpload ? (
      <>
        <Button variant="default" size="xs" leftSection={<IconDownload size={14} />} onClick={() => void download()} disabled={busy}>
          {t('tacho.download')}
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

  const ident: KeyValueItem[] = [];
  if (f?.kind === 'DRIVER_CARD') {
    ident.push(
      { label: t('drivers.card_number'), value: f.cardNumber ? <span className={classes.mono}>{f.cardNumber}</span> : DASH },
      { label: t('drivers.card_country'), value: f.cardIssuingCountry ? countryName(f.cardIssuingCountry, lang) : DASH },
      { label: t('drivers.card_valid_to'), value: f.cardValidTo ? <DeadlineChip validTo={f.cardValidTo} /> : DASH },
    );
  } else if (f) {
    ident.push(
      {
        label: t('fleet.plate'),
        value: f.plate ? (
          <Group gap={6}>
            <Plate>{f.plate}</Plate>
            {f.plateCountry && <Text span c="dimmed">{f.plateCountry}</Text>}
          </Group>
        ) : (
          DASH
        ),
      },
      { label: t('fleet.vin'), value: f.vin ? <span className={classes.mono}>{f.vin}</span> : DASH },
    );
  }

  return (
    <DetailDrawer
      mode={mode}
      listPath="/tacho"
      pagePath={`/tacho/${id}/page`}
      title={f ? <TachoKindLabel kind={f.kind} /> : '…'}
      subtitle={f && <span className={classes.mono}>{f.originalName}</span>}
      status={
        f && (
          <Group gap={6}>
            <TachoOwner file={f} link />
            <WarningsChip count={f.warningsCount} />
          </Group>
        )
      }
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : f ? (
        <Stack gap="lg">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('tacho.delete_confirm')}</Text>
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
          {canUpload && <Assignment key={`${f.id}-${f.match}-${f.driver?.id ?? ''}-${f.vehicle?.id ?? ''}`} f={f} />}

          <Section title={t('tacho.detail.identification')}>
            <KeyValueList items={ident} />
          </Section>

          <Section title={t('tacho.detail.data')}>
            <KeyValueList
              items={[
                { label: t('tacho.col.period'), value: formatPeriod(f.periodFrom, f.periodTo, lang) },
                { label: t('tacho.col.downloaded'), value: formatInstant(f.downloadedAt, lang) },
                { label: t('tacho.col.generation'), value: t(`tacho.generation.${f.generation}`) },
                { label: t('tacho.detail.match'), value: t(`tacho.match.${f.match}`) },
                { label: t('tacho.count.activityDays'), value: f.counts.activityDays },
                ...(f.kind === 'DRIVER_CARD'
                  ? [{ label: t('tacho.count.vehiclesUsed'), value: f.counts.vehiclesUsed }]
                  : [
                      { label: t('tacho.count.cardInsertions'), value: f.counts.cardInsertions },
                      { label: t('tacho.count.calibrations'), value: f.counts.calibrations },
                    ]),
                { label: t('tacho.count.places'), value: f.counts.places },
                { label: t('tacho.count.events'), value: f.counts.events },
                { label: t('tacho.count.faults'), value: f.counts.faults },
              ]}
            />
          </Section>

          {f.kind === 'VEHICLE_UNIT' && f.drivers.length > 0 && (
            <Section title={t('tacho.detail.drivers')}>
              <Table>
                <Table.Tbody>
                  {f.drivers.map((d) => (
                    <Table.Tr key={d.cardNumber}>
                      <Table.Td>
                        {d.driverId ? (
                          <Anchor component={Link} to={`/drivers/${d.driverId}`}>
                            {d.name}
                          </Anchor>
                        ) : (
                          d.name
                        )}
                        <Text size="xs" c="dimmed" className={classes.mono}>
                          {d.cardNumber}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{formatPeriod(d.firstInsertion, d.lastWithdrawal, lang)}</Text>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Section>
          )}

          <Section title={t('tacho.detail.file')}>
            <KeyValueList
              items={[
                { label: t('tacho.col.file'), value: <span className={classes.mono}>{f.originalName}</span> },
                { label: t('tacho.detail.size'), value: formatSize(f.sizeBytes, lang) },
                { label: t('tacho.col.uploaded'), value: `${formatInstant(f.uploadedAt, lang)} · ${f.uploadedBy.name}` },
                { label: 'SHA-256', value: <span className={classes.hash}>{f.sha256}</span> },
              ]}
            />
          </Section>

          {f.warnings.length > 0 && (
            <Section title={t('tacho.detail.warnings')}>
              <ul className={classes.warnings}>
                {f.warnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            </Section>
          )}
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
