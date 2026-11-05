import { Group } from '@mantine/core';
import { IconBan, IconCircleCheck, IconClockX, IconFlask } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { firmaState, type OperatorFirma } from '@/api/operator';
import { StatusChip } from '@/components/StatusChip';

/** State of a firma (shape + color + word): active, blocked by the operator, or past its access end; plus "test". */
export function FirmaStateChips({ firma }: { firma: Pick<OperatorFirma, 'active' | 'expiresAt' | 'test'> }) {
  const { t } = useTranslation();
  const state = firmaState(firma);
  return (
    <Group gap={6} wrap="nowrap">
      {state === 'ACTIVE' ? (
        <StatusChip tone="good" icon={<IconCircleCheck stroke={2.2} />} hint={t('hint.operator.ACTIVE')}>
          {t('operator.state.ACTIVE')}
        </StatusChip>
      ) : state === 'BLOCKED' ? (
        <StatusChip tone="crit" icon={<IconBan stroke={2.2} />} hint={t('hint.operator.BLOCKED')}>
          {t('operator.state.BLOCKED')}
        </StatusChip>
      ) : (
        <StatusChip tone="warn" icon={<IconClockX stroke={2.2} />} hint={t('hint.operator.EXPIRED')}>
          {t('operator.state.EXPIRED')}
        </StatusChip>
      )}
      {firma.test && (
        <StatusChip tone="info" icon={<IconFlask stroke={2.2} />} hint={t('hint.operator.test')}>
          {t('operator.test')}
        </StatusChip>
      )}
    </Group>
  );
}
