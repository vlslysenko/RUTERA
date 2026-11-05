import { IconAlertTriangle, IconBan, IconCheck, IconCircleDashed, IconClock, IconHourglassLow, IconMailCheck, IconMailX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { InvoiceStatus, ReminderStatus } from '@/api/finance';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const INVOICE: Record<InvoiceStatus, [StatusTone, typeof IconCheck]> = {
  OPEN: ['info', IconCircleDashed],
  PARTIAL: ['warn', IconHourglassLow],
  PAID: ['good', IconCheck],
  OVERDUE: ['crit', IconAlertTriangle],
  CANCELLED: ['neutral', IconBan],
};

const REMINDER: Record<ReminderStatus, [StatusTone, typeof IconCheck]> = {
  QUEUED: ['neutral', IconClock],
  SENT: ['good', IconMailCheck],
  FAILED: ['crit', IconMailX],
};

/** Invoice status; overdue ones also say by how many days. */
export function InvoiceStatusChip({ status, daysOverdue = 0 }: { status: InvoiceStatus; daysOverdue?: number }) {
  const { t } = useTranslation();
  const [tone, Icon] = INVOICE[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.invoice.${status}`)}>
      {status === 'OVERDUE' && daysOverdue > 0 ? t('invoices.overdue_days', { days: daysOverdue }) : t(`invoice.status.${status}`)}
    </StatusChip>
  );
}

export function ReminderStatusChip({ status }: { status: ReminderStatus }) {
  const { t } = useTranslation();
  const [tone, Icon] = REMINDER[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.reminder.${status}`)}>
      {t(`reminder.status.${status}`)}
    </StatusChip>
  );
}
