import { useLocation, useOutletContext, useParams } from 'react-router';
import { Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { AuditEntry } from '@/api/audit';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { KeyValueList } from '@/components/KeyValueList';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { ChannelChip } from './AuditChips';
import classes from './Audit.module.css';

/**
 * One entry of the audit log in the right panel. There is no endpoint for a single entry: the panel shows the entry
 * from the page of the list behind it (the list keeps its filters in the URL, so a reload finds it again).
 */
export function AuditEntryDetail() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const { id } = useParams();
  const { search } = useLocation();
  const entries = useOutletContext<AuditEntry[]>();
  const e = entries.find((x) => String(x.id) === id);
  const listPath = `/settings/audit${search}`;

  if (!e) {
    return (
      <DetailDrawer title={t('audit.entry')} listPath={listPath}>
        <Text size="sm" c="dimmed">
          {t('audit.not_on_page')}
        </Text>
      </DetailDrawer>
    );
  }

  return (
    <DetailDrawer title={e.summary} subtitle={formatInstantShort(e.at, lang, tz)} status={<ChannelChip channel={e.channel} />} listPath={listPath}>
      <Stack gap="md">
        <KeyValueList
          items={[
            { label: t('audit.col.user'), value: <span className={classes.mono}>{e.userLogin ?? t('audit.system')}</span> },
            { label: t('audit.col.module'), value: t(`audit.module.${e.module}`, { defaultValue: e.module }) },
            { label: t('audit.action'), value: <span className={classes.mono}>{e.action}</span> },
            { label: t('audit.entity'), value: e.entityType ? <span className={classes.mono}>{`${e.entityType}${e.entityId != null ? ` #${e.entityId}` : ''}`}</span> : '—' },
            { label: t('audit.ip'), value: <span className={classes.mono}>{e.ip ?? '—'}</span> },
            { label: t('audit.request'), value: <span className={classes.mono}>{e.requestId ?? '—'}</span> },
          ]}
        />
        {e.details && (
          <Stack gap={4}>
            <Text size="xs" fw={600} tt="uppercase" c="dimmed">
              {t('audit.details')}
            </Text>
            <pre className={classes.json}>{JSON.stringify(e.details, null, 2)}</pre>
          </Stack>
        )}
      </Stack>
    </DetailDrawer>
  );
}
