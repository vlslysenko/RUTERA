import { IconCreditCard, IconHandFinger, IconId, IconQuestionMark } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { FuelMatch } from '@/api/fuel';
import { StatusChip, type StatusTone } from '@/components/StatusChip';

const MATCH: Record<FuelMatch, [StatusTone, typeof IconId]> = {
  CARD: ['good', IconCreditCard],
  PLATE: ['info', IconId],
  MANUAL: ['neutral', IconHandFinger],
  NONE: ['warn', IconQuestionMark],
};

/** How the vehicle of a transaction was found: by the card, by the plate column, by hand, or not at all. */
export function MatchChip({ match }: { match: FuelMatch }) {
  const { t } = useTranslation();
  const [tone, Icon] = MATCH[match];
  return (
    <StatusChip tone={tone} icon={<Icon stroke={2.2} />}>
      {t(`fuel.match.${match}`)}
    </StatusChip>
  );
}
