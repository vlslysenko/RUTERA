import { Link, useNavigate } from 'react-router';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Group, Stack, Text } from '@mantine/core';
import { IconKey, IconLogout } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { driverHrKey, hrApi } from '@/api/hr';
import { clearOutbox, useOutbox } from '@/app/outbox';
import { disablePush } from '@/app/push';
import { useLogout, useUser } from '@/auth/useAuth';
import { LanguageMenu } from '@/layout/LanguageMenu';
import { PaymentKindChip } from '@/pages/drivers/HrChips';
import { formatAmount, formatMonth, formatPayment, monthOf, shiftMonth } from '@/pages/drivers/hrFormat';
import { formatMinutes, ymdIn } from '@/pages/tacho/hosFormat';
import { Card, PartError, Stat, Waiting } from './DriverParts';
import { MyAbsences } from './MyAbsences';
import { InstallCard } from './InstallCard';
import { PushCard } from './PushCard';
import classes from './DriverApp.module.css';

/** "Me": absences and requests, work days of the month, own payments of three months, language and sign-out. */
export function MePage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const logout = useLogout();
  const unsent = useOutbox(user.id).length;
  const [confirmLogout, setConfirmLogout] = useState(false);
  // Waiting items would stay on a shared phone: signing out drops them, after a warning.
  const signOut = async () => {
    if (unsent > 0 && !confirmLogout) {
      setConfirmLogout(true);
      return;
    }
    await clearOutbox(user.id).catch(() => undefined);
    // A shared phone must not keep receiving this driver's notifications.
    await disablePush();
    logout.mutate(undefined, { onSettled: () => void navigate('/login', { replace: true }) });
  };
  const navigate = useNavigate();
  const driverId = user.driver!.id;
  const today = ymdIn(new Date().toISOString(), user.firma?.timezone ?? 'UTC');
  const month = monthOf(new Date(`${today}T12:00:00`));
  const firstMonth = shiftMonth(month, -2);

  const days = useQuery({ queryKey: [...driverHrKey(driverId, 'days'), month], queryFn: () => hrApi.month(driverId, month), retry: false });
  // The firma decides whether drivers see their pay (firma settings).
  const seePay = user.firma?.driversSeePay === true;
  const payments = useQuery({
    queryKey: [...driverHrKey(driverId, 'payments'), firstMonth, month],
    queryFn: () => hrApi.payments(driverId, firstMonth, month),
    retry: false,
    enabled: seePay,
  });

  const totals = new Map<string, number>();
  for (const p of payments.data ?? []) {
    const signed = p.kind === 'ADVANCE' || p.kind === 'DEDUCTION' ? -p.amount : p.amount;
    totals.set(p.currency, (totals.get(p.currency) ?? 0) + signed);
  }
  const s = days.data?.summary;
  // Pay is shown only when the backend lets this login read it.
  const payHidden = !seePay || (payments.error instanceof ApiError && payments.error.status === 403);

  return (
    <Stack gap="sm">
      <MyAbsences driverId={driverId} today={today} />

      <Card title={`${t('drivers.tab.days')} · ${formatMonth(month, lang)}`}>
        {days.isPending ? (
          <Waiting query={days} />
        ) : (days.isError && days.data === undefined) || !s ? (
          <PartError error={days.error} />
        ) : (
          <div className={classes.stats}>
            <Stat label={t('days.kind.WORK')} value={String(s.workDays)} />
            <Stat label={t('days.kind.REST')} value={String(s.restDays)} />
            <Stat label={t('days.kind.ABSENCE')} value={String(s.absenceDays)} />
            <Stat label={t('hos.activity.DRIVING')} value={formatMinutes(t, s.drivingMin)} />
          </div>
        )}
      </Card>

      {!payHidden && (
      <Card title={t('drivers.tab.pay')}>
        {payments.isPending ? (
          <Waiting query={payments} />
        ) : payments.isError && payments.data === undefined ? (
          <PartError error={payments.error} />
        ) : payments.data.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('driver_app.no_payments')}
          </Text>
        ) : (
          <>
            {[...payments.data]
              .sort((a, b) => b.period.localeCompare(a.period))
              .map((p) => (
                <div key={p.id} className={classes.row}>
                  <div>
                    <PaymentKindChip kind={p.kind} />
                    <Text size="xs" c="dimmed">
                      {formatMonth(p.period, lang)}
                    </Text>
                  </div>
                  <Text size="sm" fw={600} className={classes.mono}>
                    {formatPayment(p.amount, p.currency, p.kind, lang)}
                  </Text>
                </div>
              ))}
            <Group justify="flex-end" gap="md" mt={6}>
              <Text size="sm" c="dimmed">
                {t('calc.total')}
              </Text>
              {[...totals].map(([currency, amount]) => (
                <Text key={currency} fw={700} className={classes.mono}>
                  {formatAmount(amount, currency, lang)}
                </Text>
              ))}
            </Group>
          </>
        )}
      </Card>
      )}

      <PushCard />
      <InstallCard />

      <Card title={user.name}>
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            {user.login}
          </Text>
          <LanguageMenu saveToProfile />
          <Button component={Link} to="/settings/password" variant="default" size="md" leftSection={<IconKey size={18} />}>
            {t('settings.tab.password')}
          </Button>
          {confirmLogout && unsent > 0 && (
            <Alert color="orange" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('outbox.logout_warning', { count: unsent })}</Text>
                <Group grow>
                  <Button size="md" variant="default" onClick={() => setConfirmLogout(false)}>
                    {t('common.cancel')}
                  </Button>
                  <Button size="md" color="red" onClick={() => void signOut()}>
                    {t('user.logout')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          <Button variant="default" color="red" size="md" leftSection={<IconLogout size={18} />} onClick={() => void signOut()} loading={logout.isPending}>
            {t('user.logout')}
          </Button>
        </Stack>
      </Card>
    </Stack>
  );
}
