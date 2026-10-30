import { IconAlertTriangle, IconBan, IconCheck, IconClock, IconTool } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { VehicleDeadline, VehicleStatus } from '@/api/fleet';
import { StatusChip, type StatusTone } from '@/components/StatusChip';
import { daysUntil, deadlineTone, DEFAULT_REMIND_DAYS, formatDate } from '@/utils/deadline';

const TONES: Record<VehicleStatus, StatusTone> = {
  ACTIVE: 'good',
  SERVICE: 'warn',
  INACTIVE: 'neutral',
};

const ICONS: Record<VehicleStatus, typeof IconCheck> = {
  ACTIVE: IconCheck,
  SERVICE: IconTool,
  INACTIVE: IconBan,
};

export function VehicleStatusChip({ status }: { status: VehicleStatus }) {
  const { t } = useTranslation();
  const Icon = ICONS[status];
  return (
    <StatusChip tone={TONES[status]} icon={<Icon stroke={2.2} />}>
      {t(`vehicle.status.${status}`)}
    </StatusChip>
  );
}

/** "6 d left" / "3 d overdue" / "until 15.03.2027": the same look as "needs attention" on the overview. */
export function DeadlineChip({ validTo, remindDays = DEFAULT_REMIND_DAYS }: { validTo: string; remindDays?: number }) {
  const { t, i18n } = useTranslation();
  const days = daysUntil(validTo);
  const tone = deadlineTone(days, remindDays);
  if (tone === 'good') {
    return (
      <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
        {t('fleet.deadline.until', { date: formatDate(validTo, i18n.resolvedLanguage ?? 'en') })}
      </StatusChip>
    );
  }
  return days < 0 ? (
    <StatusChip tone="crit" icon={<IconAlertTriangle stroke={2.2} />}>
      {t('fleet.deadline.overdue', { days: -days })}
    </StatusChip>
  ) : (
    <StatusChip tone="warn" icon={<IconClock stroke={2.2} />}>
      {t('fleet.deadline.days_left', { days })}
    </StatusChip>
  );
}

/** Nearest deadline in the list: document kind + chip. */
export function NextDeadline({ deadline }: { deadline: VehicleDeadline }) {
  const { t } = useTranslation();
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <span>{t(`vehicle.document.${deadline.kind}`)}</span>
      <DeadlineChip validTo={deadline.validTo} />
    </span>
  );
}
