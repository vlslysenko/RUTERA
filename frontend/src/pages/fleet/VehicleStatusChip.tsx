import { IconBed, IconParking, IconRoute, IconTool } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { VehicleStatus } from '@/api/fleet';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const TONES: Record<VehicleStatus, StatusTone> = {
  ON_ROUTE: 'info',
  REST: 'warn',
  BASE: 'neutral',
  SERVICE: 'crit',
};

const ICONS: Record<VehicleStatus, typeof IconRoute> = {
  ON_ROUTE: IconRoute,
  REST: IconBed,
  BASE: IconParking,
  SERVICE: IconTool,
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
