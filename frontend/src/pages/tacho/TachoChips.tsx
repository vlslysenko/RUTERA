import { Anchor, Text } from '@mantine/core';
import { Link } from 'react-router';
import { IconAlertTriangle, IconCheck, IconCopy, IconId, IconLink, IconLoader2, IconTruck, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { TachoFile, TachoImportStatus, TachoKind } from '@/api/tacho';
import { Plate } from '@/components/Plate';
import { StatusChip } from '@/components/StatusChip';

/** Driver card or vehicle unit, with an icon so the two are told apart at a glance. */
export function TachoKindLabel({ kind }: { kind: TachoKind }) {
  const { t } = useTranslation();
  const Icon = kind === 'DRIVER_CARD' ? IconId : IconTruck;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
      <Icon size={16} stroke={1.8} />
      {t(`tacho.kind.${kind}`)}
    </span>
  );
}

export function ImportStatusChip({ status }: { status: TachoImportStatus | 'UPLOADING' }) {
  const { t } = useTranslation();
  switch (status) {
    case 'IMPORTED':
      return (
        <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
          {t('tacho.status.IMPORTED')}
        </StatusChip>
      );
    case 'DUPLICATE':
      return (
        <StatusChip tone="neutral" icon={<IconCopy stroke={2.2} />}>
          {t('tacho.status.DUPLICATE')}
        </StatusChip>
      );
    case 'REJECTED':
      return (
        <StatusChip tone="crit" icon={<IconX stroke={2.2} />}>
          {t('tacho.status.REJECTED')}
        </StatusChip>
      );
    default:
      return (
        <StatusChip tone="info" icon={<IconLoader2 stroke={2.2} />}>
          {t('tacho.status.UPLOADING')}
        </StatusChip>
      );
  }
}

/** "Read partially": the file was parsed with warnings. */
export function WarningsChip({ count }: { count: number }) {
  const { t } = useTranslation();
  if (count <= 0) return null;
  return (
    <StatusChip tone="warn" icon={<IconAlertTriangle stroke={2.2} />}>
      {t('tacho.warnings')}
    </StatusChip>
  );
}

export function NotAssignedChip() {
  const { t } = useTranslation();
  return (
    <StatusChip tone="warn" icon={<IconLink stroke={2.2} />}>
      {t('tacho.match.NONE')}
    </StatusChip>
  );
}

/** Who the file belongs to: the driver, the vehicle (plate), or "not assigned". Links when `link` is set. */
export function TachoOwner({ file, link = false }: { file: Pick<TachoFile, 'driver' | 'vehicle'>; link?: boolean }) {
  if (file.driver) {
    return link ? (
      <Anchor component={Link} to={`/drivers/${file.driver.id}`}>
        {file.driver.name}
      </Anchor>
    ) : (
      <Text span fw={500}>
        {file.driver.name}
      </Text>
    );
  }
  if (file.vehicle) {
    return link ? (
      <Anchor component={Link} to={`/fleet/${file.vehicle.id}`}>
        <Plate>{file.vehicle.plate}</Plate>
      </Anchor>
    ) : (
      <Plate>{file.vehicle.plate}</Plate>
    );
  }
  return <NotAssignedChip />;
}
