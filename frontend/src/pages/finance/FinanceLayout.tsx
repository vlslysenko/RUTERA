import { Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/PageHeader';

/** Finance section: invoices (with cards), debts by contractor and the payment-reminder schedule. */
export function FinanceLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const tabs = [
    { value: '/finance', label: t('finance.tab.invoices') },
    { value: '/finance/debts', label: t('finance.tab.debts') },
    { value: '/finance/reminders', label: t('finance.tab.reminders') },
  ];
  // The invoices tab owns /finance and the cards /finance/:id; the others own their prefix.
  const active =
    tabs.find((tab) => tab.value !== '/finance' && (pathname === tab.value || pathname.startsWith(`${tab.value}/`)))?.value ?? '/finance';

  return (
    <>
      <PageHeader title={t('nav.finance')} />
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
