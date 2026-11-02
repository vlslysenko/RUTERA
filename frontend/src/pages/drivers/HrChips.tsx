import { IconBabyCarriage, IconBeach, IconCash, IconCircleDot, IconCoin, IconFirstAidKit, IconGift, IconMinus, IconReceipt, IconWallet } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { AbsenceKind, PaymentKind } from '@/api/hr';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const ABSENCE: Record<AbsenceKind, [StatusTone, typeof IconBeach]> = {
  SICK: ['crit', IconFirstAidKit],
  VACATION: ['info', IconBeach],
  UNPAID: ['neutral', IconWallet],
  PARENTAL: ['good', IconBabyCarriage],
  OTHER: ['neutral', IconCircleDot],
};

const PAYMENT: Record<PaymentKind, [StatusTone, typeof IconBeach]> = {
  SALARY: ['good', IconCash],
  PER_DIEM: ['info', IconReceipt],
  BONUS: ['good', IconGift],
  ADVANCE: ['warn', IconCoin],
  DEDUCTION: ['crit', IconMinus],
};

export function AbsenceKindChip({ kind }: { kind: AbsenceKind }) {
  const { t } = useTranslation();
  const [tone, Icon] = ABSENCE[kind];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />}>
      {t(`absence.kind.${kind}`)}
    </StatusChip>
  );
}

export function PaymentKindChip({ kind }: { kind: PaymentKind }) {
  const { t } = useTranslation();
  const [tone, Icon] = PAYMENT[kind];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />}>
      {t(`payment.kind.${kind}`)}
    </StatusChip>
  );
}
