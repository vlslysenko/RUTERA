import { Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { PageHeader } from '@/components/PageHeader';

/** Drivers section: the list (with cards), the firm-wide absence calendar and, for payroll roles, payments. */
export function DriversLayout() {
  const { t } = useTranslation();
  const user = useUser();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const tabs = [
    { value: '/drivers', label: t('drivers.tab.list') },
    { value: '/drivers/absences', label: t('drivers.tab.absences') },
    ...(hasPrivilege(user, PRIV.DRIVER_PAYMENT_VIEW) ? [{ value: '/drivers/payments', label: t('drivers.tab.payments') }] : []),
  ];
  // The list tab owns /drivers and the cards /drivers/:id; the others own their prefix.
  const active =
    tabs.find((tab) => tab.value !== '/drivers' && (pathname === tab.value || pathname.startsWith(`${tab.value}/`)))?.value ?? '/drivers';

  return (
    <>
      <PageHeader title={t('drivers.title')} />
      <Tabs value={active} onChange={(v) => v && void navigate(v)} mb="md">
        <Tabs.List>
          {tabs.map((tab) => (
            <Tabs.Tab key={tab.value} value={tab.value}>
              {tab.label}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      <Outlet />
    </>
  );
}
