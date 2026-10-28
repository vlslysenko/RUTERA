import { IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { SCREENS } from '@/app/navigation';
import type { ScreenKey } from '@/app/types';
import { EmptyState } from '@/components/EmptyState';
import { PageHeader } from '@/components/PageHeader';

/** Stand-in for modules that are not built yet; keeps navigation and the start screen working. */
export function PlaceholderPage({ screen }: { screen: ScreenKey }) {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t(SCREENS[screen].labelKey)} />
      <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
    </>
  );
}
