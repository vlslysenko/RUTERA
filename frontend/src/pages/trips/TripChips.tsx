import { IconBan, IconCheck, IconCircleDashed, IconClock, IconFileInvoice, IconPencil, IconPlayerPlay, IconPlayerSkipForward, IconRoute } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { CmrStatus } from '@/api/cmr';
import type { OrderStatus, StopStatus, TripStatus } from '@/api/trips';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const ORDER: Record<OrderStatus, [StatusTone, typeof IconCheck]> = {
  NEW: ['warn', IconCircleDashed],
  PLANNED: ['info', IconClock],
  IN_PROGRESS: ['info', IconRoute],
  DONE: ['good', IconCheck],
  INVOICED: ['good', IconFileInvoice],
  CANCELLED: ['neutral', IconBan],
};

const TRIP: Record<TripStatus, [StatusTone, typeof IconCheck]> = {
  PLANNED: ['neutral', IconClock],
  RUNNING: ['info', IconPlayerPlay],
  DONE: ['good', IconCheck],
  CANCELLED: ['neutral', IconBan],
};

const STOP: Record<StopStatus, [StatusTone, typeof IconCheck]> = {
  PLANNED: ['neutral', IconClock],
  DONE: ['good', IconCheck],
  SKIPPED: ['warn', IconPlayerSkipForward],
};

const CMR: Record<CmrStatus, [StatusTone, typeof IconCheck]> = {
  DRAFT: ['warn', IconPencil],
  ISSUED: ['good', IconCheck],
  CANCELLED: ['neutral', IconBan],
};

export function OrderStatusChip({ status }: { status: OrderStatus }) {
  const { t } = useTranslation();
  const [tone, Icon] = ORDER[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.order.${status}`)}>
      {t(`order.status.${status}`)}
    </StatusChip>
  );
}

export function TripStatusChip({ status }: { status: TripStatus }) {
  const { t } = useTranslation();
  const [tone, Icon] = TRIP[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.trip.${status}`)}>
      {t(`trip.status.${status}`)}
    </StatusChip>
  );
}

export function StopStatusChip({ status }: { status: StopStatus }) {
  const { t } = useTranslation();
  const [tone, Icon] = STOP[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.stop.${status}`)}>
      {t(`trip.stop.status.${status}`)}
    </StatusChip>
  );
}

export function CmrStatusChip({ status }: { status: CmrStatus }) {
  const { t } = useTranslation();
  const [tone, Icon] = CMR[status];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />} hint={t(`hint.cmr.${status}`)}>
      {t(`cmr.status.${status}`)}
    </StatusChip>
  );
}
