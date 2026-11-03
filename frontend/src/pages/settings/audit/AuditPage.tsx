import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Group, Pagination, Paper, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ADMIN_USERS_KEY, adminUsersApi } from '@/api/admin';
import { AUDIT_CHANNELS, AUDIT_KEY, AUDIT_MODULES, auditApi, viaAi, type AuditEntry } from '@/api/audit';
import { errorCode } from '@/api/errors';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { DateField } from '@/forms/DateField';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { cx } from '@/utils/cx';
import { ChannelChip } from './AuditChips';
import classes from './Audit.module.css';

const PAGE_SIZE = 50;

/** What the entry panel (AuditEntryDetail) gets from the list behind it. */
export interface AuditOutlet {
  entries: AuditEntry[];
  basePath: string;
  firmaName: (id: number | null) => string | null;
}

interface Props {
  /** Path of the list; a row opens `${basePath}/:id`. */
  basePath: string;
  /** Operator console: all firmas, a firma column and filter (?firma=), no user filter, intro for the operator. */
  operator?: { firmas: { id: number; name: string }[] };
}

/**
 * Audit log: who did what, newest first, with the channel of every entry so actions "via AI" (API, MCP) stand out.
 * Settings -> Audit log of the own firma (admin.audit/view), and the operator console across firmas. Filters live in
 * the URL; a row opens the entry in the right panel.
 */
export function AuditLog({ basePath, operator }: Props) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const tz = me.firma?.timezone;
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const get = (k: string) => params.get(k) ?? '';
  const page = Number(params.get('page') ?? '1') || 1;
  const [q] = useDebouncedValue(get('q').trim(), 300);
  const canListUsers = !operator && hasPrivilege(me, PRIV.ADMIN_USER_VIEW);
  const firmaName = (fid: number | null) => (fid == null ? null : (operator?.firmas.find((f) => f.id === fid)?.name ?? `#${fid}`));

  const filter = {
    from: get('from') || undefined,
    to: get('to') || undefined,
    userId: get('user') || undefined,
    module: get('module') || undefined,
    channel: get('channel') || undefined,
    q: q || undefined,
    firmaId: operator ? get('firma') || undefined : undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const list = useQuery({ queryKey: [...AUDIT_KEY, filter], queryFn: () => auditApi.list(filter), placeholderData: keepPreviousData });
  const users = useQuery({ queryKey: ADMIN_USERS_KEY, queryFn: adminUsersApi.list, enabled: canListUsers });
  const pages = list.data ? Math.max(1, Math.ceil(list.data.total / PAGE_SIZE)) : 1;

  const set = (key: string, value: string | null) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (value) next.set(key, value);
        else next.delete(key);
        if (key !== 'page') next.delete('page');
        return next;
      },
      { replace: true },
    );
  const open = (e: AuditEntry) => void navigate({ pathname: `${basePath}/${e.id}`, search: params.toString() });

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        {t('audit.intro')}
        {operator && ` ${t('audit.operator_hint')}`}
      </Text>
      <div className={classes.filters}>
        <TextInput
          className={classes.search}
          leftSection={<IconSearch size={16} />}
          placeholder={t('audit.search')}
          aria-label={t('audit.search')}
          value={get('q')}
          onChange={(e) => set('q', e.currentTarget.value || null)}
        />
        {operator && (
          <Select
            placeholder={t('operator.col.company')}
            aria-label={t('operator.col.company')}
            data={operator.firmas.map((f) => ({ value: String(f.id), label: f.name }))}
            value={get('firma') || null}
            onChange={(v) => set('firma', v)}
            clearable
            searchable
            w={240}
          />
        )}
        <DateField aria-label={t('orders.date_from')} value={get('from')} onChange={(v) => set('from', v || null)} w={160} />
        <DateField aria-label={t('orders.date_to')} value={get('to')} min={get('from') || undefined} onChange={(v) => set('to', v || null)} w={160} />
        {canListUsers && (
          <Select
            placeholder={t('audit.col.user')}
            aria-label={t('audit.col.user')}
            data={(users.data ?? []).map((u) => ({ value: String(u.id), label: `${u.name} (${u.login})` }))}
            value={get('user') || null}
            onChange={(v) => set('user', v)}
            clearable
            searchable
            w={220}
          />
        )}
        <Select
          placeholder={t('audit.col.module')}
          aria-label={t('audit.col.module')}
          data={AUDIT_MODULES.map((m) => ({ value: m, label: t(`audit.module.${m}`) }))}
          value={get('module') || null}
          onChange={(v) => set('module', v)}
          clearable
          w={180}
        />
        <Select
          placeholder={t('audit.col.channel')}
          aria-label={t('audit.col.channel')}
          data={AUDIT_CHANNELS.map((c) => ({ value: c, label: (c === 'API' || c === 'MCP' ? `${t('audit.via_ai')} · ` : '') + t(`audit.channel.${c}`) }))}
          value={get('channel') || null}
          onChange={(v) => set('channel', v)}
          clearable
          w={180}
        />
      </div>

      {list.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : list.isError ? (
        <ErrorState message={t(errorCode(list.error))} onRetry={() => void list.refetch()} />
      ) : list.data.items.length === 0 ? (
        <EmptyState text={t('audit.empty')} />
      ) : (
        <>
          <Text size="xs" c="dimmed">
            {list.data.total}
          </Text>
          <Paper className={classes.tableWrap}>
            <Table className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('audit.col.at')}</Table.Th>
                  {operator && <Table.Th>{t('operator.col.company')}</Table.Th>}
                  <Table.Th>{t('audit.col.user')}</Table.Th>
                  <Table.Th>{t('audit.col.channel')}</Table.Th>
                  <Table.Th>{t('audit.col.module')}</Table.Th>
                  <Table.Th>{t('audit.col.what')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {list.data.items.map((e) => {
                  const selected = String(e.id) === id;
                  return (
                    <Table.Tr
                      key={e.id}
                      tabIndex={0}
                      aria-current={selected ? 'true' : undefined}
                      className={cx(classes.row, selected && classes.selected, viaAi(e.channel) && classes.ai)}
                      onClick={() => open(e)}
                      onKeyDown={(ev) => {
                        if (ev.key === 'Enter' || ev.key === ' ') {
                          ev.preventDefault();
                          open(e);
                        }
                      }}
                    >
                      <Table.Td className={classes.mono}>{formatInstantShort(e.at, lang, tz)}</Table.Td>
                      {operator && <Table.Td>{firmaName(e.firmaId) ?? '—'}</Table.Td>}
                      <Table.Td className={classes.mono}>{e.userLogin ?? t('audit.system')}</Table.Td>
                      <Table.Td>
                        <ChannelChip channel={e.channel} />
                      </Table.Td>
                      <Table.Td>{t(`audit.module.${e.module}`, { defaultValue: e.module })}</Table.Td>
                      <Table.Td className={classes.what}>
                        <Text size="sm">{e.summary}</Text>
                        <span className={classes.code}>{e.action}</span>
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </Paper>
          {pages > 1 && (
            <Group justify="flex-end">
              <Pagination total={pages} value={page} onChange={(p) => set('page', p > 1 ? String(p) : null)} size="sm" />
            </Group>
          )}
        </>
      )}
      <Outlet context={{ entries: list.data?.items ?? [], basePath, firmaName } satisfies AuditOutlet} />
    </Stack>
  );
}

/** Settings -> Audit log of the own firma. */
export function AuditPage() {
  return <AuditLog basePath="/settings/audit" />;
}
