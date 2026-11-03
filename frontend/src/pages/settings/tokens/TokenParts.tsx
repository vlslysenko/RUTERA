import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ActionIcon, Group, Pagination, Paper, Stack, Table, Text, Tooltip } from '@mantine/core';
import { IconBan, IconCircleCheck, IconClockX, IconList, IconPlugConnectedX, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { splitTokens, TOKENS_KEY, tokensApi, type ApiToken } from '@/api/tokens';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { StatusChip } from '@/components/StatusChip';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { cx } from '@/utils/cx';
import classes from './Tokens.module.css';

const CALLS_PAGE = 50;

function TokenStatus({ token }: { token: ApiToken }) {
  const { t } = useTranslation();
  if (token.active) {
    return (
      <StatusChip tone="good" icon={<IconCircleCheck stroke={2.2} />}>
        {t('tokens.status.ACTIVE')}
      </StatusChip>
    );
  }
  return token.revokedAt ? (
    <StatusChip tone="neutral" icon={<IconBan stroke={2.2} />}>
      {t('tokens.status.REVOKED')}
    </StatusChip>
  ) : (
    <StatusChip tone="warn" icon={<IconClockX stroke={2.2} />}>
      {t('tokens.status.EXPIRED')}
    </StatusChip>
  );
}

/**
 * Tokens as a table: name, prefix, dates, status; the call log and revoke per row. With `apps` the rows are apps
 * connected over OAuth: app name, no token prefix, access until (end of the refresh token), disconnect.
 */
export function TokenTable({
  tokens,
  apps = false,
  showUser = false,
  selectedId,
  onCalls,
  onRevoke,
}: {
  tokens: ApiToken[];
  apps?: boolean;
  showUser?: boolean;
  selectedId: number | null;
  onCalls: (token: ApiToken) => void;
  onRevoke: (token: ApiToken) => void;
}) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const when = (iso: string | null) => (iso ? formatInstantShort(iso, lang, tz) : '—');
  const revokeLabel = t(apps ? 'tokens.disconnect' : 'tokens.revoke');

  return (
    <Paper className={classes.tableWrap}>
      <Table className={classes.table} verticalSpacing={6}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t(apps ? 'tokens.app' : 'tokens.name')}</Table.Th>
            {showUser && <Table.Th>{t('tokens.user')}</Table.Th>}
            {!apps && <Table.Th>{t('tokens.prefix')}</Table.Th>}
            <Table.Th>{t('tokens.created')}</Table.Th>
            <Table.Th>{t(apps ? 'tokens.access_until' : 'tokens.expires')}</Table.Th>
            <Table.Th>{t('tokens.last_used')}</Table.Th>
            <Table.Th>{t('users.status')}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {tokens.map((x) => (
            <Table.Tr key={x.id} className={cx(x.id === selectedId && classes.selected)}>
              <Table.Td>
                <Text size="sm" fw={500}>
                  {x.name}
                </Text>
              </Table.Td>
              {showUser && <Table.Td>{x.user.name}</Table.Td>}
              {!apps && <Table.Td className={classes.mono}>{x.prefix}…</Table.Td>}
              <Table.Td className={classes.mono}>{when(x.createdAt)}</Table.Td>
              <Table.Td className={classes.mono}>{x.expiresAt ? when(x.expiresAt) : t('tokens.never')}</Table.Td>
              <Table.Td className={classes.mono}>{when(x.lastUsedAt)}</Table.Td>
              <Table.Td>
                <TokenStatus token={x} />
              </Table.Td>
              <Table.Td>
                <Group gap={2} wrap="nowrap" justify="flex-end">
                  <Tooltip label={t('tokens.calls')}>
                    <ActionIcon variant="subtle" color="gray" size="sm" onClick={() => onCalls(x)} aria-label={t('tokens.calls')}>
                      <IconList size={15} />
                    </ActionIcon>
                  </Tooltip>
                  {x.active && (
                    <Tooltip label={revokeLabel}>
                      <ActionIcon variant="subtle" color="red" size="sm" onClick={() => onRevoke(x)} aria-label={revokeLabel}>
                        {apps ? <IconPlugConnectedX size={15} /> : <IconTrash size={15} />}
                      </ActionIcon>
                    </Tooltip>
                  )}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Paper>
  );
}

interface GroupsProps {
  tokens: ApiToken[];
  showUser?: boolean;
  /** Text when there are no personal tokens. */
  empty: string;
  /** Explanation above the connected apps. */
  appsHint: string;
  selectedId: number | null;
  onCalls: (token: ApiToken) => void;
  onRevoke: (token: ApiToken) => void;
}

/** Personal tokens, then the apps connected over OAuth (that group only when there are any). */
export function TokenGroups({ tokens, showUser, empty, appsHint, selectedId, onCalls, onRevoke }: GroupsProps) {
  const { t } = useTranslation();
  const { personal, apps } = splitTokens(tokens);
  const rows = { showUser, selectedId, onCalls, onRevoke };
  return (
    <>
      {apps.length > 0 && (
        <Text size="sm" fw={600}>
          {t('tokens.personal')}
        </Text>
      )}
      {personal.length === 0 ? <EmptyState text={empty} /> : <TokenTable tokens={personal} {...rows} />}
      {apps.length > 0 && (
        <Stack gap={6}>
          <Text size="sm" fw={600}>
            {t('tokens.connected_apps')}
          </Text>
          <Text size="xs" c="dimmed">
            {appsHint}
          </Text>
          <TokenTable apps tokens={apps} {...rows} />
        </Stack>
      )}
    </>
  );
}

/** The call log of one token, newest first. */
export function TokenCalls({ token }: { token: ApiToken }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const [page, setPage] = useState(1);
  const q = useQuery({
    queryKey: [...TOKENS_KEY, token.id, 'calls', page],
    queryFn: () => tokensApi.calls(token.id, page - 1, CALLS_PAGE),
    placeholderData: keepPreviousData,
  });
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / CALLS_PAGE)) : 1;

  return (
    <Stack gap="xs">
      <Text size="sm" fw={600}>
        {t('tokens.calls_of', { name: token.name })}
        {q.data ? ` · ${q.data.total}` : ''}
      </Text>
      {q.isPending ? (
        <Text size="sm" c="dimmed">…</Text>
      ) : q.isError ? (
        <Text size="sm" c="red">
          {t(errorCode(q.error))}
        </Text>
      ) : q.data.items.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('tokens.no_calls')}
        </Text>
      ) : (
        <>
          <Paper className={classes.tableWrap}>
            <Table className={classes.table} verticalSpacing={4} fz="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('tokens.call.at')}</Table.Th>
                  <Table.Th>{t('tokens.call.request')}</Table.Th>
                  <Table.Th className={classes.num}>{t('tokens.call.status')}</Table.Th>
                  <Table.Th className={classes.num}>{t('tokens.call.duration')}</Table.Th>
                  <Table.Th>IP</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {q.data.items.map((c, i) => (
                  <Table.Tr key={i}>
                    <Table.Td className={classes.mono}>{formatInstantShort(c.at, lang, tz)}</Table.Td>
                    <Table.Td className={classes.mono}>
                      {c.method} {c.path}
                    </Table.Td>
                    <Table.Td className={classes.num}>
                      <Text span c={c.status >= 400 ? 'red' : undefined} inherit>
                        {c.status}
                      </Text>
                    </Table.Td>
                    <Table.Td className={classes.num}>{c.durationMs} ms</Table.Td>
                    <Table.Td className={classes.mono}>{c.ip ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Paper>
          {pages > 1 && <Pagination total={pages} value={page} onChange={setPage} size="sm" />}
        </>
      )}
    </Stack>
  );
}
