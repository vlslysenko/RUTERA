import { Outlet, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Button, Group, Paper, Table, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconBan, IconCheck, IconLock, IconPlus } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ADMIN_USERS_KEY, adminUsersApi } from '@/api/admin';
import { errorCode } from '@/api/errors';
import type { AdminUser } from '@/app/types';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { StatusChip } from '@/components/StatusChip';
import { languageName } from '@/i18n/languages';
import { cx } from '@/utils/cx';
import classes from './UsersPage.module.css';

/**
 * Status = shape + color + word: active / inactive, plus a lock chip. The backend resets the failure counter when
 * it locks an account, so the count is shown only for unlocked users who have failed sign-ins.
 */
export function UserStatusChips({ user }: { user: AdminUser }) {
  const { t } = useTranslation();
  return (
    <Group gap={6} wrap="wrap">
      {user.active ? (
        <StatusChip tone="good" icon={<IconCheck stroke={2.2} />}>
          {t('users.active')}
        </StatusChip>
      ) : (
        <StatusChip tone="neutral" icon={<IconBan stroke={2.2} />}>
          {t('users.inactive')}
        </StatusChip>
      )}
      {user.locked ? (
        <StatusChip tone="warn" icon={<IconLock stroke={2.2} />}>
          {t('users.locked')}
        </StatusChip>
      ) : (
        user.failedLogins > 0 && (
          <StatusChip tone="neutral" icon={<IconAlertTriangle stroke={2.2} />}>
            {t('users.failed_logins')}: {user.failedLogins}
          </StatusChip>
        )
      )}
    </Group>
  );
}

/** Users of the firma; a row opens the card in the right panel (/settings/users/:id). */
export function UsersPage() {
  const { t } = useTranslation();
  const me = useUser();
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ADMIN_USERS_KEY, queryFn: adminUsersApi.list });
  const canManage = hasPrivilege(me, PRIV.ADMIN_USER_MANAGE);

  const open = (u: AdminUser) => void navigate(`/settings/users/${u.id}`);

  return (
    <>
      <Group justify="space-between" align="center" mb="sm">
        <Group gap="sm" align="baseline">
          <Title order={3}>{t('users.title')}</Title>
          {q.data && <Text c="dimmed">{q.data.length}</Text>}
        </Group>
        {canManage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate('/settings/users/new')}>
            {t('users.add')}
          </Button>
        )}
      </Group>
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState text={t('users.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('users.name')}</Table.Th>
                <Table.Th>{t('users.login')}</Table.Th>
                <Table.Th>{t('users.role')}</Table.Th>
                <Table.Th>{t('users.language')}</Table.Th>
                <Table.Th>{t('users.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.map((u) => {
                const selected = String(u.id) === id;
                return (
                  <Table.Tr
                    key={u.id}
                    tabIndex={0}
                    aria-current={selected ? 'true' : undefined}
                    className={cx(classes.row, selected && classes.selected)}
                    onClick={() => open(u)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        open(u);
                      }
                    }}
                  >
                    <Table.Td>
                      <Text span fw={500}>
                        {u.name}
                      </Text>
                      {u.id === me.id && (
                        <Text span c="dimmed" size="xs" ml={6}>
                          {t('users.self')}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td className={classes.mono}>{u.login}</Table.Td>
                    <Table.Td>{t(`role.${u.role}`)}</Table.Td>
                    <Table.Td>{languageName(u.language)}</Table.Td>
                    <Table.Td>
                      <UserStatusChips user={u} />
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
      <Outlet />
    </>
  );
}
