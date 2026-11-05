import { IconClockCog, IconPointer, IconSparkles } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { viaAi } from '@/api/audit';
import { StatusChip } from '@/components/StatusChip';

/** Where an action came from: the RUTERA interface, a token "via AI" (API, MCP) or the system itself. */
export function ChannelChip({ channel }: { channel: string }) {
  const { t } = useTranslation();
  if (viaAi(channel)) {
    return (
      <StatusChip tone="info" icon={<IconSparkles stroke={2.2} />} hint={t(`hint.audit.${channel}`)}>
        {t('audit.via_ai')} · {t(`audit.channel.${channel}`)}
      </StatusChip>
    );
  }
  return channel === 'SYSTEM' ? (
    <StatusChip tone="neutral" icon={<IconClockCog stroke={2.2} />} hint={t('hint.audit.SYSTEM')}>
      {t('audit.channel.SYSTEM')}
    </StatusChip>
  ) : (
    <StatusChip tone="neutral" icon={<IconPointer stroke={2.2} />} hint={t('hint.audit.UI')}>
      {t(`audit.channel.${channel}`)}
    </StatusChip>
  );
}
