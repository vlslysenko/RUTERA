import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, CloseButton, CopyButton, Group, Paper, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconCopy, IconExternalLink, IconKey, IconLock } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { OPENAPI_URL, TOKENS_KEY, tokensApi, type ApiToken } from '@/api/tokens';
import { useUser } from '@/auth/useAuth';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { DateField } from '@/forms/DateField';
import { fromZonedInput } from '@/utils/zone';
import { TokenCalls, TokenGroups } from './TokenParts';
import classes from './Tokens.module.css';

const MY_TOKENS = [...TOKENS_KEY, 'mine'] as const;

/** Name and expiry of a new token; the token itself comes back once. */
function CreateToken({ onCreated }: { onCreated: (token: ApiToken) => void }) {
  const { t } = useTranslation();
  const tz = useUser().firma?.timezone;
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [expires, setExpires] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [addOn, setAddOn] = useState(false);
  const [busy, setBusy] = useState(false);
  const today = new Date().toISOString().slice(0, 10);

  const create = async () => {
    const local: Record<string, string> = {};
    if (!name.trim()) local.name = 'validation.required';
    else if (name.trim().length > 100) local.name = 'validation.too_long';
    if (expires && expires <= today) local.expiresAt = 'validation.invalid_value';
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      // A date means "valid through that day" in the firma zone.
      const token = await tokensApi.create({ name: name.trim(), expiresAt: expires ? fromZonedInput(`${expires}T23:59`, tz) : null });
      void qc.invalidateQueries({ queryKey: TOKENS_KEY });
      setName('');
      setExpires('');
      setAddOn(false);
      onCreated(token);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'api.ai_access_required') setAddOn(true);
      else if (e instanceof ApiError && e.details) setErrors(e.details);
      else notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper withBorder p="sm">
      <Stack gap="sm">
        <Group align="flex-start" wrap="wrap">
          <TextInput
            label={t('tokens.name')}
            placeholder={t('tokens.name_hint')}
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
            maxLength={100}
            error={errors.name && t(errors.name)}
            style={{ flex: '1 1 240px' }}
          />
          <DateField
            label={t('tokens.expires')}
            description={t('tokens.expires_hint')}
            value={expires}
            min={today}
            onChange={setExpires}
            error={errors.expiresAt && t(errors.expiresAt)}
            w={200}
          />
          <Button mt={24} leftSection={<IconKey size={16} />} onClick={() => void create()} loading={busy}>
            {t('tokens.create')}
          </Button>
        </Group>
        {addOn && (
          <Alert color="orange" variant="light" icon={<IconLock size={16} />} p="sm">
            {t('api.ai_access_required')}. {t('tokens.add_on_hint')}
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}

/** The token just created: shown once, with a copy button. */
function NewToken({ token, onClose }: { token: ApiToken; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Alert color="green" variant="light" p="sm" title={t('tokens.created_title', { name: token.name })} withCloseButton={false}>
      <Stack gap="xs">
        <Text size="sm">{t('tokens.shown_once')}</Text>
        <code className={classes.secret}>{token.token}</code>
        <Group gap="xs">
          <CopyButton value={token.token ?? ''}>
            {({ copied, copy }) => (
              <Button size="xs" variant={copied ? 'filled' : 'light'} color={copied ? 'teal' : 'blue'} leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />} onClick={copy}>
                {t(copied ? 'tokens.copied' : 'tokens.copy')}
              </Button>
            )}
          </CopyButton>
          <Button size="xs" variant="default" onClick={onClose}>
            {t('tokens.saved_it')}
          </Button>
        </Group>
      </Stack>
    </Alert>
  );
}

/** Settings -> API tokens: own personal tokens for the public API and the MCP server. */
export function TokensPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: MY_TOKENS, queryFn: tokensApi.mine });
  const [created, setCreated] = useState<ApiToken | null>(null);
  const [revoking, setRevoking] = useState<ApiToken | null>(null);
  const [calls, setCalls] = useState<ApiToken | null>(null);
  const [busy, setBusy] = useState(false);
  const mcpUrl = `${window.location.origin}/mcp`;

  const revoke = async (x: ApiToken) => {
    setBusy(true);
    try {
      await tokensApi.revoke(x.id);
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
    <Stack gap="md" maw={1100}>
      <Stack gap={6}>
        <Text size="sm">{t('tokens.intro')}</Text>
        <Group gap="lg">
          <Anchor href={OPENAPI_URL} target="_blank" rel="noopener noreferrer" size="sm">
            <Group gap={4} wrap="nowrap">
              {t('tokens.openapi')}
              <IconExternalLink size={14} />
            </Group>
          </Anchor>
        </Group>
        <Text size="xs" c="dimmed">
          {t('tokens.mcp_hint')}
        </Text>
        <pre className={classes.command}>{`claude mcp add --transport http rutera ${mcpUrl} --header "Authorization: Bearer rtr_..."`}</pre>
      </Stack>

      {created && <NewToken token={created} onClose={() => setCreated(null)} />}
      <CreateToken onCreated={setCreated} />

      {revoking && (
        <Alert color="red" variant="light" p="sm">
          <Group gap="sm">
            <Text size="sm">{t(revoking.kind === 'OAUTH' ? 'tokens.disconnect_confirm' : 'tokens.revoke_confirm', { name: revoking.name })}</Text>
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
          empty={t('tokens.empty')}
          appsHint={t('tokens.connected_apps_hint')}
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
