import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, CloseButton, Group, Paper, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { TOKENS_KEY, tokensApi, type ApiToken } from '@/api/tokens';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { TokenCalls, TokenGroups } from './TokenParts';

/** Settings -> all API tokens of the firma (admin.api_token/manage): who has which token, its calls, revoke. */
export function AllTokensPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: [...TOKENS_KEY, 'all'], queryFn: tokensApi.all });
  const [revoking, setRevoking] = useState<ApiToken | null>(null);
  const [calls, setCalls] = useState<ApiToken | null>(null);
  const [busy, setBusy] = useState(false);

  const revoke = async (x: ApiToken) => {
    setBusy(true);
    try {
      await tokensApi.revokeAny(x.id);
      void qc.invalidateQueries({ queryKey: TOKENS_KEY });
      notifications.show({ color: 'green', message: t(x.kind === 'OAUTH' ? 'tokens.disconnected' : 'tokens.revoked', { name: x.name }) });
      setRevoking(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="md" maw={1200}>
      <Text size="sm">{t('tokens.all_intro')}</Text>
      {revoking && (
        <Alert color="red" variant="light" p="sm">
          <Group gap="sm">
            <Text size="sm">
              {t(revoking.kind === 'OAUTH' ? 'tokens.disconnect_confirm_user' : 'tokens.revoke_confirm_user', {
                name: revoking.name,
                user: revoking.user.name,
              })}
            </Text>
            <Button size="xs" color="red" onClick={() => void revoke(revoking)} loading={busy}>
              {t('common.confirm')}
            </Button>
            <Button size="xs" variant="default" onClick={() => setRevoking(null)} disabled={busy}>
              {t('common.cancel')}
            </Button>
          </Group>
        </Alert>
      )}
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <TokenGroups
          tokens={q.data}
          showUser
          empty={t('tokens.all_empty')}
          appsHint={t('tokens.connected_apps_all_hint')}
          selectedId={calls?.id ?? null}
          onCalls={setCalls}
          onRevoke={setRevoking}
        />
      )}
      {calls && (
        <Paper withBorder p="sm">
          <Group justify="flex-end" mb={-28}>
            <CloseButton onClick={() => setCalls(null)} aria-label={t('common.close')} />
          </Group>
          <TokenCalls key={calls.id} token={calls} />
        </Paper>
      )}
    </Stack>
  );
}
