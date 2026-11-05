import { useEffect, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Paper, Stack, Switch, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBellRinging } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { useInstall } from '@/app/install';
import { currentSubscription, disablePush, enablePush, PUSH_KEY, pushApi, pushRegistration, pushSupported } from '@/app/push';
import { LabelHint } from '@/components/LabelHint';
import { Card } from './DriverParts';

type View = 'loading' | 'hidden' | 'install' | 'denied' | 'off' | 'on';

/** The driver cabinet's card, or a panel of the office settings. */
function Frame({ office, title, children }: { office: boolean; title: string; children: ReactNode }) {
  if (!office) return <Card title={title}>{children}</Card>;
  return (
    <Paper p="lg" maw={520}>
      <Stack gap="md">
        <Title order={3}>{title}</Title>
        {children}
      </Stack>
    </Paper>
  );
}

/**
 * "Notifications on this phone": push about a new or changed trip, the decision on an absence request and an expiring
 * document or driver card, also with RUTERA closed. With `office`, the same switch in the settings of owners and
 * dispatchers, who get the reminders of vehicle documents, on any device. Hidden where push cannot work (no service
 * worker, push off on the server); on iPhone it first asks to install the app.
 */
export function PushCard({ office = false }: { office?: boolean }) {
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
      <Frame office={office} title={t(office ? 'push.title_device' : 'push.title')}>
        <Text size="sm" c="dimmed">
          {t('push.ios_install')}
        </Text>
      </Frame>
    );
  }
  if (!key.data) return null;

  return (
    <Frame office={office} title={t(office ? 'push.title_device' : 'push.title')}>
      <Stack gap="sm">
        <Text size="sm">{t(office ? 'push.text_office' : 'push.text')}</Text>
        {view === 'denied' ? (
          <Text size="sm" c="orange">
            {t(office ? 'push.denied_device' : 'push.denied')}
          </Text>
        ) : (
          <Switch
            size={office ? 'sm' : 'md'}
            label={<LabelHint label={t('push.switch')} hint={t('hint.field.push_device')} />}
            checked={view === 'on'}
            disabled={busy}
            onChange={(e) => void toggle(e.currentTarget.checked)}
            thumbIcon={view === 'on' ? <IconBellRinging size={12} /> : undefined}
          />
        )}
        {view === 'on' && (
          <Button size={office ? 'sm' : 'md'} w={office ? 'fit-content' : undefined} variant="default" onClick={() => void test()} loading={busy}>
            {t('push.test')}
          </Button>
        )}
      </Stack>
    </Frame>
  );
}
