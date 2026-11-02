import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Group, Paper, Stack, Table, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowBackUp, IconUpload } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { FUEL_IMPORTS_KEY, FUEL_TX_KEY, fuelApi, type FuelImport } from '@/api/fuel';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { StatusChip } from '@/components/StatusChip';
import { formatPeriod } from '@/pages/tacho/format';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { ImportWizard } from './ImportWizard';
import classes from '@/pages/trips/Trips.module.css';

/** Imports of provider files, newest first; the wizard on top; an import can be undone with its transactions. */
export function ImportsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const qc = useQueryClient();
  const canManage = hasPrivilege(me, PRIV.FUEL_IMPORT_MANAGE);
  const [wizard, setWizard] = useState(false);
  const [undoing, setUndoing] = useState<FuelImport | null>(null);
  const [busy, setBusy] = useState(false);
  const q = useQuery({ queryKey: [...FUEL_IMPORTS_KEY, 'list'], queryFn: () => fuelApi.imports(0, 100) });

  const undo = async (imp: FuelImport) => {
    setBusy(true);
    try {
      await fuelApi.removeImport(imp.id);
      void qc.invalidateQueries({ queryKey: FUEL_IMPORTS_KEY });
      void qc.invalidateQueries({ queryKey: FUEL_TX_KEY });
      notifications.show({ color: 'green', message: t('fuel.imports.undone', { name: imp.fileName }) });
      setUndoing(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="sm">
      {canManage && !wizard && (
        <Group justify="flex-end">
          <Button leftSection={<IconUpload size={16} />} onClick={() => setWizard(true)}>
            {t('fuel.imports.new')}
          </Button>
        </Group>
      )}
      {wizard && <ImportWizard onClose={() => setWizard(false)} />}
      {undoing && (
        <Alert color="red" variant="light">
          <Group gap="sm" wrap="wrap">
            <Text size="sm">{t('fuel.imports.undo_confirm', { name: undoing.fileName, n: undoing.rowsNew })}</Text>
            <Button size="xs" color="red" onClick={() => void undo(undoing)} loading={busy}>
              {t('common.confirm')}
            </Button>
            <Button size="xs" variant="default" onClick={() => setUndoing(null)} disabled={busy}>
              {t('common.cancel')}
            </Button>
          </Group>
        </Alert>
      )}
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.items.length === 0 ? (
        <EmptyState text={t('fuel.imports.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('fuel.imports.when')}</Table.Th>
                <Table.Th>{t('fuel.imports.file')}</Table.Th>
                <Table.Th>{t('fuel.provider')}</Table.Th>
                <Table.Th>{t('fuel.imports.period')}</Table.Th>
                <Table.Th className={classes.num}>{t('fuel.import.rows_new')}</Table.Th>
                <Table.Th className={classes.num}>{t('fuel.import.rows_dup')}</Table.Th>
                <Table.Th className={classes.num}>{t('fuel.import.rows_error')}</Table.Th>
                <Table.Th>{t('users.status')}</Table.Th>
                <Table.Th>{t('fuel.imports.by')}</Table.Th>
                {canManage && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.items.map((imp) => (
                <Table.Tr key={imp.id}>
                  <Table.Td className={classes.mono}>{formatInstantShort(imp.createdAt, lang)}</Table.Td>
                  <Table.Td>
                    <Text span size="sm" fw={500}>
                      {imp.fileName}
                    </Text>
                  </Table.Td>
                  <Table.Td>{t(`fuel.provider.${imp.provider}`)}</Table.Td>
                  <Table.Td className={classes.mono}>{formatPeriod(imp.periodFrom, imp.periodTo, lang)}</Table.Td>
                  <Table.Td className={classes.num}>{imp.rowsNew}</Table.Td>
                  <Table.Td className={classes.num}>{imp.rowsDup}</Table.Td>
                  <Table.Td className={classes.num}>{imp.rowsError ? <Text span c="red">{imp.rowsError}</Text> : 0}</Table.Td>
                  <Table.Td>
                    <StatusChip tone={imp.status === 'DONE' ? 'good' : 'crit'}>{t(`fuel.imports.status.${imp.status}`)}</StatusChip>
                  </Table.Td>
                  <Table.Td>{imp.importedBy.name}</Table.Td>
                  {canManage && (
                    <Table.Td>
                      <Button size="xs" variant="subtle" color="red" leftSection={<IconArrowBackUp size={14} />} onClick={() => setUndoing(imp)} disabled={busy}>
                        {t('fuel.imports.undo')}
                      </Button>
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
}
