import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { PageHeader } from '@/components/PageHeader';

/** Tachograph: uploaded files, driver activity and violations (561/2006). Tabs are routes. */
export function TachoLayout() {
  const { t } = useTranslation();
  const user = useUser();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const hos = hasPrivilege(user, PRIV.TACHO_HOS_VIEW);

  const tabs = [
    { value: '/tacho/files', label: t('tacho.tab.files') },
    ...(hos
      ? [
          { value: '/tacho/activity', label: t('tacho.tab.activity') },
          { value: '/tacho/violations', label: t('tacho.tab.violations') },
        ]
      : []),
  ];
  const active = tabs.find((tab) => pathname === tab.value || pathname.startsWith(`${tab.value}/`))?.value ?? null;

  return (
    <>
      <PageHeader title={t('nav.tacho')} />
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

/** /tacho opens the files; the menu item points here. */
export function TachoIndex() {
  return <Navigate to="/tacho/files" replace />;
}
