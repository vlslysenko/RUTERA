import { Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { PageHeader } from '@/components/PageHeader';

/** Settings: profile, own password, and (with admin.user/view) the users of the firma. Tabs are routes. */
export function SettingsLayout() {
  const { t } = useTranslation();
  const user = useUser();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const tabs = [
    { value: '/settings', label: t('settings.tab.profile') },
    { value: '/settings/password', label: t('settings.tab.password') },
    ...(hasPrivilege(user, PRIV.ADMIN_USER_VIEW) ? [{ value: '/settings/users', label: t('settings.tab.users') }] : []),
  ];
  const active =
    tabs
      .filter((tab) => pathname === tab.value || pathname.startsWith(`${tab.value}/`))
      .sort((a, b) => b.value.length - a.value.length)[0]?.value ?? '/settings';

  return (
    <>
      <PageHeader title={t('settings.title')} />
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
