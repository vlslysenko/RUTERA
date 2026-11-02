import { Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/PageHeader';

/** Fuel section: transactions (with cards in the right panel), imports of provider files and fuel cards. */
export function FuelLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const tabs = [
    { value: '/fuel', label: t('fuel.tab.transactions') },
    { value: '/fuel/imports', label: t('fuel.tab.imports') },
    { value: '/fuel/cards', label: t('fuel.tab.cards') },
  ];
  // The transactions tab owns /fuel and /fuel/:id; the others own their prefix.
  const active = tabs.find((tab) => tab.value !== '/fuel' && (pathname === tab.value || pathname.startsWith(`${tab.value}/`)))?.value ?? '/fuel';

  return (
    <>
      <PageHeader title={t('nav.fuel')} />
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
