import { Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/PageHeader';

/** Operator console: companies and the audit log across companies. Tabs are routes. */
export function OperatorLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const active = pathname.startsWith('/operator/audit') ? '/operator/audit' : '/operator';

  return (
    <>
      <PageHeader title={t('nav.group.operator')} />
      <Tabs value={active} onChange={(v) => v && void navigate(v)} mb="md">
        <Tabs.List>
          <Tabs.Tab value="/operator">{t('nav.operator')}</Tabs.Tab>
          <Tabs.Tab value="/operator/audit">{t('settings.tab.audit')}</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      <Outlet />
    </>
  );
}
