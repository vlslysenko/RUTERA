import { useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { Group, Paper, Stack, Text } from '@mantine/core';
import { IconWifiOff } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import classes from './Offline.module.css';

/*
 * Connection state as TanStack Query sees it (navigator.onLine + online/offline events). Queries pause while
 * offline and refetch when the connection is back; these pieces tell the user why nothing new loads.
 */

function useOnline(): boolean {
  return useSyncExternalStore(
    (onChange) => onlineManager.subscribe(onChange),
    () => onlineManager.isOnline(),
  );
}

/** Slim bar under the header while offline: the screen shows saved data and refreshes by itself later. */
export function OfflineBanner() {
  const { t } = useTranslation();
  const online = useOnline();
  if (online) return null;
  return (
    <div className={classes.banner} role="status">
      <IconWifiOff size={16} stroke={2} />
      <span>{t('offline.banner')}</span>
    </div>
  );
}

/** In place of a loader when a query waits for the connection and nothing is saved on this device (or `text`). */
export function OfflineNote({ text }: { text?: string }) {
  const { t } = useTranslation();
  return (
    <Group gap={6} wrap="nowrap" align="flex-start" c="dimmed">
      <IconWifiOff size={16} stroke={1.8} style={{ flex: 'none', marginTop: 2 }} />
      <Text size="sm">{text ?? t('offline.no_data')}</Text>
    </Group>
  );
}

/** Full screen variant (session check before anything is saved). */
export function OfflineScreen() {
  const { t } = useTranslation();
  return (
    <div className={classes.screen}>
      <Paper p="xl" maw={420}>
        <Stack align="center" gap="sm" c="dimmed">
          <IconWifiOff size={32} stroke={1.5} />
          <Text ta="center">{t('offline.sign_in')}</Text>
        </Stack>
      </Paper>
    </div>
  );
}
