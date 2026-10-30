import { IconBan, IconCheck, IconTool } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { VehicleStatus } from '@/api/fleet';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

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
