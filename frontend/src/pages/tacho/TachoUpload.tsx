import { useRef, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Group, Paper, Stack, Table, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFileUpload } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DRIVERS_KEY } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { VEHICLES_KEY } from '@/api/fleet';
import {
  TACHO_FILES_KEY,
  TACHO_MAX_BYTES,
  TACHO_MAX_FILES,
  tachoApi,
  type TachoFile,
  type TachoImportStatus,
} from '@/api/tacho';
import { cx } from '@/utils/cx';
import { formatPeriod } from './format';
import { ImportStatusChip, TachoKindLabel, TachoOwner, WarningsChip } from './TachoChips';
import classes from './TachoFilesPage.module.css';

interface ResultRow {
  key: string;
  fileName: string;
  status: TachoImportStatus | 'UPLOADING';
  file: TachoFile | null;
  errorCode: string | null;
}

let rowSeq = 0;

/**
 * Drop zone for many card and vehicle unit files at once, then one result row per file (imported, already
 * uploaded, rejected with the reason). The rows of earlier batches stay until "Clear".
 */
export function TachoUpload() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const busy = rows.some((r) => r.status === 'UPLOADING');

  const upload = async (list: File[]) => {
    if (list.length === 0) return;
    if (list.length > TACHO_MAX_FILES) {
      notifications.show({ color: 'red', message: t('tacho.file.too_many') });
      return;
    }
    // Files over the limit are not sent at all; the backend would reject them anyway.
    const batch: ResultRow[] = list.map((f) => ({
      key: `r${++rowSeq}`,
      fileName: f.name,
      status: f.size > TACHO_MAX_BYTES ? 'REJECTED' : 'UPLOADING',
      file: null,
      errorCode: f.size > TACHO_MAX_BYTES ? 'tacho.file.too_large' : null,
    }));
    setRows((prev) => [...batch, ...prev]);
    const toSend = list.filter((f) => f.size <= TACHO_MAX_BYTES);
    const sentKeys = batch.filter((r) => r.status === 'UPLOADING').map((r) => r.key);
    if (toSend.length === 0) return;
    try {
      const results = await tachoApi.upload(toSend);
      const byKey = new Map(sentKeys.map((k, i) => [k, results[i]]));
      setRows((prev) =>
        prev.map((r) => {
          const res = byKey.get(r.key);
          return res ? { ...r, status: res.status, file: res.file, errorCode: res.error?.code ?? null } : r;
        }),
      );
      await qc.invalidateQueries({ queryKey: TACHO_FILES_KEY });
      // Imports move lastDownloadAt of driver cards and vehicles.
      void qc.invalidateQueries({ queryKey: DRIVERS_KEY });
      void qc.invalidateQueries({ queryKey: VEHICLES_KEY });
    } catch (e) {
      const code = errorCode(e);
      setRows((prev) => prev.map((r) => (sentKeys.includes(r.key) ? { ...r, status: 'REJECTED', errorCode: code } : r)));
      notifications.show({ color: 'red', message: t(code) });
    }
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(false);
    if (!busy) void upload([...e.dataTransfer.files]);
  };

  return (
    <Stack gap="sm">
      <div
        className={cx(classes.dropzone, dragOver && classes.dropzoneActive)}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        <IconFileUpload size={34} stroke={1.4} className={classes.dropIcon} />
        <div className={classes.dropText}>
          <Text fw={600}>{t('tacho.upload.title')}</Text>
          <Text size="sm" c="dimmed">
            {t('tacho.upload.hint')}
          </Text>
        </div>
        <Button onClick={() => inputRef.current?.click()} loading={busy}>
          {t('tacho.upload.choose')}
        </Button>
        <input
          ref={inputRef}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            const files = [...(e.currentTarget.files ?? [])];
            e.currentTarget.value = '';
            void upload(files);
          }}
        />
      </div>

      {rows.length > 0 && (
        <Paper className={classes.tableWrap}>
          <Group justify="space-between" px="sm" pt="xs">
            <Title order={4}>{t('tacho.upload.results')}</Title>
            <Button variant="subtle" size="xs" onClick={() => setRows([])} disabled={busy}>
              {t('tacho.upload.clear')}
            </Button>
          </Group>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('tacho.col.file')}</Table.Th>
                <Table.Th>{t('tacho.col.kind')}</Table.Th>
                <Table.Th>{t('tacho.col.who')}</Table.Th>
                <Table.Th>{t('tacho.col.period')}</Table.Th>
                <Table.Th>{t('tacho.col.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => {
                const f = r.file;
                const open = f ? () => void navigate(`/tacho/${f.id}`) : undefined;
                return (
                  <Table.Tr
                    key={r.key}
                    className={cx(classes.row, open && classes.clickable)}
                    onClick={open}
                    tabIndex={open ? 0 : undefined}
                    onKeyDown={(e) => {
                      if (open && (e.key === 'Enter' || e.key === ' ')) {
                        e.preventDefault();
                        open();
                      }
                    }}
                  >
                    <Table.Td>
                      <Text span className={cx(classes.mono, classes.fileName)} title={r.fileName} display="inline-block">
                        {r.fileName}
                      </Text>
                    </Table.Td>
                    <Table.Td>{f ? <TachoKindLabel kind={f.kind} /> : '—'}</Table.Td>
                    <Table.Td>{f ? <TachoOwner file={f} /> : '—'}</Table.Td>
                    <Table.Td>{f ? formatPeriod(f.periodFrom, f.periodTo, lang) : '—'}</Table.Td>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <ImportStatusChip status={r.status} />
                        {f && <WarningsChip count={f.warningsCount} />}
                        {r.errorCode && (
                          <Text span size="sm" c="dimmed">
                            {t(r.errorCode)}
                          </Text>
                        )}
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
}
