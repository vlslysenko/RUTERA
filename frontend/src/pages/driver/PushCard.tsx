import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Stack, Switch, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBellRinging } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { useInstall } from '@/app/install';
import { currentSubscription, disablePush, enablePush, PUSH_KEY, pushApi, pushRegistration, pushSupported } from '@/app/push';
import { Card } from './DriverParts';

type View = 'loading' | 'hidden' | 'install' | 'denied' | 'off' | 'on';

/**
 * "Notifications on this phone": push about a new or changed trip and the decision on an absence request, also with
 * RUTERA closed. Hidden where push cannot work (no service worker, push off on the server); on iPhone it first asks
 * to install the app.
 */
export function PushCard() {
  const { t } = useTranslation();
  const { standalone, ios } = useInstall();
  // 404 push.disabled: the server has no VAPID keys, the card stays hidden.
  const key = useQuery({ queryKey: PUSH_KEY, queryFn: pushApi.key, retry: false, staleTime: Infinity, enabled: pushSupported() });
  const [view, setView] = useState<View>('loading');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    const decide = async (): Promise<View> => {
      if (!pushSupported()) return ios && !standalone ? 'install' : 'hidden';
      if (!(await pushRegistration())) return 'hidden';
      if (Notification.permission === 'denied') return 'denied';
      return Notification.permission === 'granted' && (await currentSubscription()) ? 'on' : 'off';
    };
    void decide()
      .catch((): View => 'hidden')
      .then((v) => alive && setView(v));
    return () => {
      alive = false;
    };
  }, [ios, standalone]);

  const toggle = async (on: boolean) => {
    if (!key.data) return;
    setBusy(true);
    try {
      if (on) setView(await enablePush(key.data.publicKey));
      else {
        await disablePush();
        setView('off');
      }
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    setBusy(true);
    try {
      const r = await pushApi.test();
      notifications.show({ color: r.sent > 0 ? 'green' : 'yellow', message: t('push.test_sent', { count: r.sent }) });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  if (view === 'hidden' || view === 'loading') return null;
  if (view === 'install') {
    return (
      <Card title={t('push.title')}>
        <Text size="sm" c="dimmed">
          {t('push.ios_install')}
        </Text>
      </Card>
    );
  }
  if (!key.data) return null;

  return (
    <Card title={t('push.title')}>
      <Stack gap="sm">
        <Text size="sm">{t('push.text')}</Text>
        {view === 'denied' ? (
          <Text size="sm" c="orange">
            {t('push.denied')}
          </Text>
        ) : (
          <Switch
            size="md"
            label={t('push.switch')}
            checked={view === 'on'}
            disabled={busy}
            onChange={(e) => void toggle(e.currentTarget.checked)}
            thumbIcon={view === 'on' ? <IconBellRinging size={12} /> : undefined}
          />
        )}
        {view === 'on' && (
          <Button size="md" variant="default" onClick={() => void test()} loading={busy}>
            {t('push.test')}
          </Button>
        )}
      </Stack>
    </Card>
  );
}
