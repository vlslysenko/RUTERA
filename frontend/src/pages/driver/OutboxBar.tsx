import { useCallback, useEffect, useState } from 'react';
import { onlineManager, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Button, Group, Stack, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconChevronDown, IconChevronUp, IconClockHour4, IconRefresh, IconSend, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { driverHrKey } from '@/api/hr';
import { TRIPS_KEY } from '@/api/trips';
import { onOutboxSentElsewhere, removeFromOutbox, replayOutbox, retryOutboxItem, shownState, useOutbox, type OutboxItem } from '@/app/outbox';
import { useUser } from '@/auth/useAuth';
import { StatusChip } from '@/components/StatusChip';
import { formatDate } from '@/utils/deadline';
import classes from './DriverApp.module.css';

/** What a waiting item is, in one line. */
export function OutboxLabel({ item }: { item: OutboxItem }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  return item.type === 'trip_file'
    ? t('outbox.type.trip_file', { trip: item.tripNumber, name: item.fileName })
    : t('outbox.type.absence', { from: formatDate(item.body.fromDate, lang), to: formatDate(item.body.toDate, lang) });
}

/** Waiting / sending / not sent (with the reason). */
export function OutboxStateChip({ item }: { item: OutboxItem }) {
  const { t } = useTranslation();
  const state = shownState(item);
  if (state === 'failed') {
    return (
      <StatusChip tone="crit" icon={<IconAlertTriangle stroke={2.2} />}>
        {t('outbox.state.failed')}
      </StatusChip>
    );
  }
  return (
    <StatusChip tone={state === 'sending' ? 'info' : 'warn'} icon={<IconClockHour4 stroke={2.2} />}>
      {t(state === 'sending' ? 'outbox.state.sending' : 'outbox.state.queued')}
    </StatusChip>
  );
}

/** Retry (refused items) and remove, for one item. */
export function OutboxActions({ item }: { item: OutboxItem }) {
  const { t } = useTranslation();
  return (
    <Group gap={4} wrap="nowrap">
      {item.state === 'failed' && (
        <Tooltip label={t('outbox.retry')}>
          <ActionIcon variant="light" size={36} aria-label={t('outbox.retry')} onClick={() => void retryOutboxItem(item)}>
            <IconRefresh size={18} />
          </ActionIcon>
        </Tooltip>
      )}
      {shownState(item) !== 'sending' && (
        <Tooltip label={t('outbox.remove')}>
          <ActionIcon variant="subtle" color="red" size={36} aria-label={t('outbox.remove')} onClick={() => void removeFromOutbox(item.id)}>
            <IconTrash size={18} />
          </ActionIcon>
        </Tooltip>
      )}
    </Group>
  );
}

/**
 * The cabinet's outbox under the header: what waits to be sent and what was refused. Sends on start, on reconnect,
 * on return to the app and on "Send now"; screens refresh for whatever got through (also when the service worker or
 * another tab sent it).
 */
export function OutboxBar() {
  const { t } = useTranslation();
  const user = useUser();
  const qc = useQueryClient();
  const items = useOutbox(user.id);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(
    (item: { type: OutboxItem['type']; tripId?: number; driverId?: number }) => {
      if (item.type === 'trip_file' && item.tripId != null) void qc.invalidateQueries({ queryKey: [...TRIPS_KEY, String(item.tripId), 'files'] });
      if (item.type === 'absence' && item.driverId != null) void qc.invalidateQueries({ queryKey: driverHrKey(item.driverId, 'absences') });
    },
    [qc],
  );

  const send = useCallback(async () => {
    if (!onlineManager.isOnline()) return;
    setBusy(true);
    try {
      const sent = await replayOutbox(user.id);
      sent.forEach(refresh);
      if (sent.length) notifications.show({ color: 'green', message: t('outbox.sent', { count: sent.length }) });
    } finally {
      setBusy(false);
    }
  }, [user.id, refresh, t]);

  useEffect(() => {
    onOutboxSentElsewhere(refresh);
    // First try right after the cabinet opens (not inside the effect itself).
    const first = setTimeout(() => void send(), 0);
    const visible = () => document.visibilityState === 'visible' && void send();
    const unsubscribe = onlineManager.subscribe((online) => online && void send());
    document.addEventListener('visibilitychange', visible);
    return () => {
      clearTimeout(first);
      onOutboxSentElsewhere(null);
      unsubscribe();
      document.removeEventListener('visibilitychange', visible);
    };
  }, [send, refresh]);

  if (items.length === 0) return null;
  const failed = items.filter((i) => i.state === 'failed').length;
  const waiting = items.length - failed;

  return (
    <div className={classes.outbox} role="status">
      <Group justify="space-between" wrap="nowrap" gap="xs">
        <UnstyledButton onClick={() => setOpen((o) => !o)} className={classes.outboxToggle} aria-expanded={open}>
          <Group gap={6} wrap="nowrap">
            {open ? <IconChevronUp size={16} /> : <IconChevronDown size={16} />}
            <Text size="sm" fw={600}>
              {[waiting ? t('outbox.waiting', { count: waiting }) : null, failed ? t('outbox.failed', { count: failed }) : null].filter(Boolean).join(' · ')}
            </Text>
          </Group>
        </UnstyledButton>
        {waiting > 0 && (
          <Button size="xs" variant="light" leftSection={<IconSend size={14} />} onClick={() => void send()} loading={busy}>
            {t('outbox.send_now')}
          </Button>
        )}
      </Group>
      {open && (
        <Stack gap={6} mt={6}>
          {items.map((item) => (
            <div key={item.id} className={classes.outboxItem}>
              <div style={{ minWidth: 0, flex: 1 }}>
                <Text size="sm">
                  <OutboxLabel item={item} />
                </Text>
                <Group gap={6} mt={2}>
                  <OutboxStateChip item={item} />
                  {item.error && item.state !== 'sending' && (
                    <Text size="xs" c={item.state === 'failed' ? 'red' : 'dimmed'}>
                      {t(item.error)}
                    </Text>
                  )}
                </Group>
              </div>
              <OutboxActions item={item} />
            </div>
          ))}
        </Stack>
      )}
    </div>
  );
}
