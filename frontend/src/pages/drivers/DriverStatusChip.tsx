import { IconBan, IconCheck } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { DriverStatus } from '@/api/drivers';
import { StatusChip } from '@/components/StatusChip';

export function DriverStatusChip({ status }: { status: DriverStatus }) {
  const { t } = useTranslation();
  return status === 'ACTIVE' ? (
    <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
      {t('driver.status.ACTIVE')}
    </StatusChip>
  ) : (
    <StatusChip tone="neutral" icon={<IconBan stroke={2.2} />}>
      {t('driver.status.INACTIVE')}
    </StatusChip>
  );
}
