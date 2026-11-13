import { IconCheck, IconPencil, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { ExplanationStatus } from '@/api/explanations';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const TONE: Record<ExplanationStatus, StatusTone> = { PENDING: 'warn', SIGNED: 'good', CANCELLED: 'neutral' };
const ICON = { PENDING: IconPencil, SIGNED: IconCheck, CANCELLED: IconX } as const;

/** Waiting for the driver's signature, signed, or withdrawn by the office. */
export function ExplanationStatusChip({ status }: { status: ExplanationStatus }) {
  const { t } = useTranslation();
  const Icon = ICON[status];
  return (
    <StatusChip tone={TONE[status]} icon={<Icon stroke={2.2} />} hint={t(`hint.explanation.${status}`)}>
      {t(`explanation.status.${status}`)}
    </StatusChip>
  );
}
