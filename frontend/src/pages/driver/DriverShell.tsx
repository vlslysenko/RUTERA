import { NavLink, Navigate, Outlet, useNavigate } from 'react-router';
import { Button, Stack } from '@mantine/core';
import { IconCalendarEvent, IconClockHour4, IconFileCertificate, IconLogout, IconUser } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useLogout, useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { OfflineBanner } from '@/components/Offline';
import { OutboxBar } from './OutboxBar';
import classes from './DriverApp.module.css';

const TABS = [
  { to: '/driver', end: true, icon: IconCalendarEvent, label: 'driver_app.tab.today' },
  { to: '/driver/hours', end: false, icon: IconClockHour4, label: 'driver_app.tab.hours' },
  { to: '/driver/documents', end: false, icon: IconFileCertificate, label: 'driver_app.tab.documents' },
  { to: '/driver/me', end: false, icon: IconUser, label: 'driver_app.tab.me' },
] as const;

/** A login without a driver gets the hint and a way out. */
function NotLinked() {
  const { t } = useTranslation();
  const logout = useLogout();
  const navigate = useNavigate();
  return (
    <Stack align="center">
      <EmptyState text={t('driver_app.not_linked')} />
      <Button variant="default" size="md" leftSection={<IconLogout size={18} />} onClick={() => logout.mutate(undefined, { onSettled: () => void navigate('/login', { replace: true }) })}>
        {t('user.logout')}
      </Button>
    </Stack>
  );
}

/**
 * Driver cabinet (PWA): its own shell without the side menu, four tabs at the bottom, one task per screen.
 * Everything is read for the driver linked to the login (User.driver).
 */
export function DriverShell() {
  const { t } = useTranslation();
  const user = useUser();
  if (!hasPrivilege(user, PRIV.DRIVER_APP_SELF_USE)) return <Navigate to="/" replace />;

  return (
    <div className={classes.shell}>
      <header className={classes.head}>
        <span className={classes.brand}>RUTERA</span>
        <span className={classes.who}>{user.driver?.name ?? user.name}</span>
      </header>
      <OfflineBanner />
      {user.driver && <OutboxBar />}
      <main className={classes.main}>{user.driver ? <Outlet /> : <NotLinked />}</main>
      <nav className={classes.tabs} aria-label={t('driver_app.title')}>
        {TABS.map(({ to, end, icon: Icon, label }) => (
          <NavLink key={to} to={to} end={end} className={classes.tab}>
            <Icon size={22} stroke={1.8} />
            <span>{t(label)}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
