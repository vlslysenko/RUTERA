import { useState } from 'react';
import { Outlet, useNavigate, useParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Group, Pagination, Paper, SegmentedControl, Select, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core';
import { IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { fleetApi, isTrailer, VEHICLES_KEY } from '@/api/fleet';
import { TACHO_FILES_KEY, tachoApi, type TachoFile, type TachoFileFilter } from '@/api/tacho';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { PageHeader } from '@/components/PageHeader';
import { cx } from '@/utils/cx';
import { formatInstant, formatPeriod } from './format';
import { TachoKindLabel, TachoOwner, WarningsChip } from './TachoChips';
import { TachoUpload } from './TachoUpload';
import classes from './TachoFilesPage.module.css';

const PAGE_SIZE = 50;

type KindFilter = 'all' | 'DRIVER_CARD' | 'VEHICLE_UNIT';

function identification(f: TachoFile): string {
  return f.kind === 'DRIVER_CARD' ? (f.cardNumber ?? '—') : ([f.plate, f.vin].filter(Boolean).join(' · ') || '—');
}

/** Tachograph → Files: upload card and vehicle unit downloads, then the journal of all uploaded files. */
export function TachoFilesPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const canUpload = hasPrivilege(me, PRIV.TACHO_FILE_UPLOAD);

  const [kind, setKind] = useState<KindFilter>('all');
  const [unassigned, setUnassigned] = useState(false);
  const [driverId, setDriverId] = useState<string | null>(null);
  const [vehicleId, setVehicleId] = useState<string | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);

  const filter: TachoFileFilter = {
    kind: kind === 'all' ? undefined : kind,
    unassigned: unassigned || undefined,
    driverId: driverId ? Number(driverId) : undefined,
    vehicleId: vehicleId ? Number(vehicleId) : undefined,
    from: from || undefined,
    to: to || undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const q = useQuery({
    queryKey: [...TACHO_FILES_KEY, 'list', filter],
    queryFn: () => tachoApi.list(filter),
    placeholderData: keepPreviousData,
  });
  const drivers = useQuery({
    queryKey: DRIVERS_KEY,
    queryFn: driversApi.list,
    enabled: hasPrivilege(me, PRIV.DRIVER_VIEW),
  });
  const vehicles = useQuery({
    queryKey: VEHICLES_KEY,
    queryFn: fleetApi.list,
    enabled: hasPrivilege(me, PRIV.FLEET_VEHICLE_VIEW),
  });

  // Any filter change starts again from the first page.
  const change =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v);
      setPage(1);
    };

  const notReady = q.isError && q.error instanceof ApiError && q.error.status === 404;
  const open = (f: TachoFile) => void navigate(`/tacho/${f.id}`);
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <PageHeader title={t('tacho.title')} />
      {notReady ? (
        // An older backend without the import module.
        <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
      ) : (
        <Stack gap="lg">
          {canUpload && <TachoUpload />}

          <Stack gap="sm">
            <Group gap="sm" align="baseline">
              <Title order={3}>{t('tacho.files.title')}</Title>
              {q.data && <Text c="dimmed">{q.data.total}</Text>}
            </Group>
            <div className={classes.filters}>
              <SegmentedControl
                value={kind}
                onChange={(v) => change(setKind)(v as KindFilter)}
                data={[
                  { value: 'all', label: t('tacho.filter.all') },
                  { value: 'DRIVER_CARD', label: t('tacho.filter.cards') },
                  { value: 'VEHICLE_UNIT', label: t('tacho.filter.units') },
                ]}
              />
              {drivers.data && kind !== 'VEHICLE_UNIT' && (
                <Select
                  placeholder={t('tacho.filter.driver')}
                  aria-label={t('tacho.filter.driver')}
                  data={drivers.data.map((d) => ({ value: String(d.id), label: d.name }))}
                  value={driverId}
                  onChange={change(setDriverId)}
                  clearable
                  searchable
                  w={200}
                />
              )}
              {vehicles.data && kind !== 'DRIVER_CARD' && (
                <Select
                  placeholder={t('tacho.filter.vehicle')}
                  aria-label={t('tacho.filter.vehicle')}
                  data={vehicles.data.filter((v) => !isTrailer(v.kind)).map((v) => ({ value: String(v.id), label: v.plate }))}
                  value={vehicleId}
                  onChange={change(setVehicleId)}
                  clearable
                  searchable
                  w={160}
                />
              )}
              <TextInput
                type="date"
                aria-label={t('tacho.filter.from')}
                title={t('tacho.filter.from')}
                value={from}
                onChange={(e) => change(setFrom)(e.currentTarget.value)}
                w={150}
              />
              <TextInput
                type="date"
                aria-label={t('tacho.filter.to')}
                title={t('tacho.filter.to')}
                value={to}
                onChange={(e) => change(setTo)(e.currentTarget.value)}
                w={150}
              />
              <Switch
                label={t('tacho.filter.unassigned')}
                checked={unassigned}
                onChange={(e) => change(setUnassigned)(e.currentTarget.checked)}
                mb={6}
              />
            </div>

            {q.isPending ? (
              <FullScreenLoader fullScreen={false} />
            ) : q.isError ? (
              <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
            ) : q.data.items.length === 0 ? (
              <EmptyState text={t('tacho.files.empty')} />
            ) : (
              <>
                <Paper className={classes.tableWrap}>
                  <Table className={classes.table}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t('tacho.col.uploaded')}</Table.Th>
                        <Table.Th>{t('tacho.col.kind')}</Table.Th>
                        <Table.Th>{t('tacho.col.who')}</Table.Th>
                        <Table.Th>{t('tacho.col.identification')}</Table.Th>
                        <Table.Th>{t('tacho.col.period')}</Table.Th>
                        <Table.Th>{t('tacho.col.downloaded')}</Table.Th>
                        <Table.Th>{t('tacho.col.generation')}</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {q.data.items.map((f) => {
                        const selected = String(f.id) === id;
                        return (
                          <Table.Tr
                            key={f.id}
                            tabIndex={0}
                            aria-current={selected ? 'true' : undefined}
                            className={cx(classes.row, classes.clickable, selected && classes.selected)}
                            onClick={() => open(f)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault();
                                open(f);
                              }
                            }}
                          >
                            <Table.Td className={classes.mono}>{formatInstant(f.uploadedAt, lang)}</Table.Td>
                            <Table.Td>
                              <TachoKindLabel kind={f.kind} />
                            </Table.Td>
                            <Table.Td>
                              <TachoOwner file={f} />
                            </Table.Td>
                            <Table.Td className={classes.mono}>{identification(f)}</Table.Td>
                            <Table.Td>{formatPeriod(f.periodFrom, f.periodTo, lang)}</Table.Td>
                            <Table.Td className={classes.mono}>{formatInstant(f.downloadedAt, lang)}</Table.Td>
                            <Table.Td>{t(`tacho.generation.${f.generation}`)}</Table.Td>
                            <Table.Td>
                              <WarningsChip count={f.warningsCount} />
                            </Table.Td>
                          </Table.Tr>
                        );
                      })}
                    </Table.Tbody>
                  </Table>
                </Paper>
                {pages > 1 && <Pagination total={pages} value={page} onChange={setPage} size="sm" />}
              </>
            )}
          </Stack>
        </Stack>
      )}
      <Outlet />
    </>
  );
}
