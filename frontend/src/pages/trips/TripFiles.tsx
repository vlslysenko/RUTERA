import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Button, Group, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCamera, IconExternalLink, IconFileUpload, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError, saveBlob } from '@/api/client';
import { errorCode } from '@/api/errors';
import { TRIP_FILE_ACCEPT, TRIP_FILE_KINDS, TRIP_FILE_MAX_BYTES, TRIPS_KEY, tripsApi, type Trip, type TripFile, type TripFileKind } from '@/api/trips';
import { useUser } from '@/auth/useAuth';
import { formatInstantShort } from './tripFormat';

const size = (bytes: number) => (bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} kB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`);

/**
 * Files of a trip (CMR photos, delivery notes, receipts): list, open, upload from the phone camera or a file, delete.
 * `driver`: the cabinet variant (big buttons, own uploads deletable while the trip is open); otherwise the office.
 */
export function TripFiles({ trip, canUpload, canDeleteAll, driver = false }: { trip: Trip; canUpload: boolean; canDeleteAll: boolean; driver?: boolean }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const tz = user.firma?.timezone;
  const qc = useQueryClient();
  const key = [...TRIPS_KEY, String(trip.id), 'files'];
  const q = useQuery({ queryKey: key, queryFn: () => tripsApi.files(trip.id), retry: false });
  const [kind, setKind] = useState<TripFileKind>('CMR_PHOTO');
  const [stopId, setStopId] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const camera = useRef<HTMLInputElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const open = trip.status !== 'DONE' && trip.status !== 'CANCELLED';
  const uploadAllowed = canUpload && (open || !driver);

  const upload = async (files: FileList | null) => {
    const file = files?.[0];
    if (!file) return;
    if (file.size > TRIP_FILE_MAX_BYTES) {
      notifications.show({ color: 'red', message: t('trip.file.too_large') });
      return;
    }
    setBusy(true);
    try {
      await tripsApi.uploadFile(trip.id, file, kind, note.trim() || null, stopId ? Number(stopId) : null);
      void qc.invalidateQueries({ queryKey: key });
      setNote('');
      notifications.show({ color: 'green', message: t('trip_files.uploaded', { name: file.name }) });
    } catch (e) {
      const code = e instanceof ApiError && e.details ? Object.values(e.details)[0] : errorCode(e);
      notifications.show({ color: 'red', message: t(code) });
    } finally {
      setBusy(false);
      if (camera.current) camera.current.value = '';
      if (picker.current) picker.current.value = '';
    }
  };

  const show = async (f: TripFile) => {
    // The tab opens before the download so that pop-up blockers let it through.
    const tab = window.open('', '_blank');
    try {
      const { blob } = await tripsApi.fileContent(trip.id, f.id);
      if (tab) {
        const url = URL.createObjectURL(blob);
        tab.location.href = url;
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } else saveBlob(blob, f.fileName);
    } catch (e) {
      tab?.close();
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  };

  const remove = async (f: TripFile) => {
    try {
      await tripsApi.removeFile(trip.id, f.id);
      void qc.invalidateQueries({ queryKey: key });
      notifications.show({ color: 'green', message: t('trip_files.deleted') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  };

  const deletable = (f: TripFile) => canDeleteAll || (driver && open && f.uploadedBy.id === user.id);
  const size_ = driver ? 'md' : 'xs';

  return (
    <Stack gap="xs">
      {uploadAllowed && (
        <Stack gap={6}>
          <Group grow align="flex-start">
            <Select
              size={size_}
              label={t('trip_files.kind')}
              data={TRIP_FILE_KINDS.map((k) => ({ value: k, label: t(`trip_files.kind_${k}`) }))}
              value={kind}
              onChange={(v) => v && setKind(v as TripFileKind)}
              allowDeselect={false}
            />
            <Select
              size={size_}
              label={t('trip_files.stop')}
              placeholder={t('trip_files.whole_trip')}
              data={trip.stops.map((s) => ({ value: String(s.id), label: `${s.seq}. ${t(`trip.stop.kind.${s.kind}`)} · ${s.address}` }))}
              value={stopId}
              onChange={setStopId}
              clearable
            />
          </Group>
          <TextInput size={size_} label={t('drivers.notes')} value={note} onChange={(e) => setNote(e.currentTarget.value)} maxLength={500} />
          <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={(e) => void upload(e.currentTarget.files)} />
          <input ref={picker} type="file" accept={TRIP_FILE_ACCEPT} hidden onChange={(e) => void upload(e.currentTarget.files)} />
          <Group grow={driver} gap="xs">
            {driver && (
              <Button size="md" leftSection={<IconCamera size={18} />} onClick={() => camera.current?.click()} loading={busy}>
                {t('trip_files.take_photo')}
              </Button>
            )}
            <Button size={size_} variant={driver ? 'default' : 'light'} leftSection={<IconFileUpload size={driver ? 18 : 14} />} onClick={() => picker.current?.click()} loading={busy}>
              {t('trip_files.choose_file')}
            </Button>
          </Group>
        </Stack>
      )}
      {driver && !open && canUpload && (
        <Text size="xs" c="dimmed">
          {t('trip.file.trip_closed')}
        </Text>
      )}
      {q.isPending ? (
        <Text size="sm" c="dimmed">…</Text>
      ) : q.isError ? (
        <Text size="sm" c="red">
          {t(errorCode(q.error))}
        </Text>
      ) : q.data.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('trip_files.empty')}
        </Text>
      ) : (
        q.data.map((f) => (
          <Group key={f.id} justify="space-between" wrap="nowrap" gap="xs">
            <div style={{ minWidth: 0 }}>
              <Text size="sm" fw={500} truncate>
                {t(`trip_files.kind_${f.kind}`)}
                {f.stop ? ` · ${t('trip_files.at_stop', { n: f.stop.seq })}` : ''}
              </Text>
              <Text size="xs" c="dimmed" truncate>
                {[f.fileName, size(f.size), f.uploadedBy.name, formatInstantShort(f.createdAt, lang, tz), f.note].filter(Boolean).join(' · ')}
              </Text>
            </div>
            <Group gap={2} wrap="nowrap">
              <Tooltip label={t('trip_files.open')}>
                <ActionIcon variant="subtle" color="gray" size={driver ? 'lg' : 'sm'} onClick={() => void show(f)} aria-label={t('trip_files.open')}>
                  <IconExternalLink size={driver ? 18 : 15} />
                </ActionIcon>
              </Tooltip>
              {deletable(f) && (
                <Tooltip label={t('common.delete')}>
                  <ActionIcon variant="subtle" color="red" size={driver ? 'lg' : 'sm'} onClick={() => void remove(f)} aria-label={t('common.delete')}>
                    <IconTrash size={driver ? 18 : 15} />
                  </ActionIcon>
                </Tooltip>
              )}
            </Group>
          </Group>
        ))
      )}
    </Stack>
  );
}
