import { IconAlertTriangle, IconCheck, IconClock } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { daysUntil, deadlineTone, DEFAULT_REMIND_DAYS, formatDate } from '@/utils/deadline';
import { StatusChip } from './StatusChip';

/** "6 d left" / "3 d overdue" / "until 15.03.2027": the same look as "needs attention" on the overview. */
export function DeadlineChip({ validTo, remindDays = DEFAULT_REMIND_DAYS }: { validTo: string; remindDays?: number }) {
  const { t, i18n } = useTranslation();
  const days = daysUntil(validTo);
  const tone = deadlineTone(days, remindDays);
  if (tone === 'good') {
    return (
      <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
        {t('deadline.until', { date: formatDate(validTo, i18n.resolvedLanguage ?? 'en') })}
      </StatusChip>
    );
  }
  return days < 0 ? (
    <StatusChip tone="crit" icon={<IconAlertTriangle stroke={2.2} />}>
      {t('deadline.overdue', { days: -days })}
    </StatusChip>
  ) : (
    <StatusChip tone="warn" icon={<IconClock stroke={2.2} />}>
      {t('deadline.days_left', { days })}
    </StatusChip>
  );
}

/** Nearest deadline in a list row: what expires + chip. */
export function NextDeadline({ label, validTo }: { label: string; validTo: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <span>{label}</span>
      <DeadlineChip validTo={validTo} />
    </span>
  );
}
