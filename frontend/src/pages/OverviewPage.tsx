import { useMemo } from 'react';
import { IconLayoutDashboard } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/EmptyState';
import { PageHeader } from '@/components/PageHeader';

/** Day overview (owner / dispatcher). Content blocks arrive with the backend modules. */
export function OverviewPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';

  const today = useMemo(() => {
    const s = new Intl.DateTimeFormat(lang, { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());
    return s.charAt(0).toLocaleUpperCase(lang) + s.slice(1);
  }, [lang]);

  return (
    <>
      <PageHeader title={t('overview.title')} subtitle={today} />
      <EmptyState icon={<IconLayoutDashboard size={28} stroke={1.5} />} text={t('overview.empty')} />
    </>
  );
}
