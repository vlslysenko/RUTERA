import { IconAlertOctagon, IconAlertTriangle, IconCheck, IconExclamationCircle, IconInfoCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { Severity, Violation } from '@/api/hos';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const TONE: Record<Severity, StatusTone> = {
  MINOR: 'info',
  SERIOUS: 'warn',
  VERY_SERIOUS: 'crit',
  MOST_SERIOUS: 'crit',
};

const ICON: Record<Severity, typeof IconInfoCircle> = {
  MINOR: IconInfoCircle,
  SERIOUS: IconAlertTriangle,
  VERY_SERIOUS: IconExclamationCircle,
  MOST_SERIOUS: IconAlertOctagon,
};

/** Severity by Regulation (EU) 2016/403. */
export function SeverityChip({ severity }: { severity: Severity }) {
  const { t } = useTranslation();
  const Icon = ICON[severity];
  return (
    <StatusChip tone={TONE[severity]} icon={<Icon stroke={2.2} />}>
      {t(`hos.severity.${severity}`)}
    </StatusChip>
  );
}

/** Reviewed by a dispatcher, or still waiting for it. */
export function ReviewChip({ v }: { v: Pick<Violation, 'ack'> }) {
  const { t } = useTranslation();
  return v.ack ? (
    <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
      {t('hos.v.reviewed')}
    </StatusChip>
  ) : (
    <StatusChip tone="neutral">{t('hos.v.new')}</StatusChip>
  );
}
