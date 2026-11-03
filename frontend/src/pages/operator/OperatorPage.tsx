import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Button, Group, Pagination, Paper, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { expiresDay, OPERATOR_KEY, operatorApi, type OperatorFirma } from '@/api/operator';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { cx } from '@/utils/cx';
import { formatDate } from '@/utils/deadline';
import { FirmaStateChips } from './OperatorChips';
import classes from './Operator.module.css';

const PAGE_SIZE = 50;

/**
 * Operator console (role SUPERUSER): every firma of the installation with its plan, vehicle limit, AI access and
 * access period; a row opens the card with the paid options in the right panel (/operator/:id).
 */
export function OperatorPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const search = params.get('q') ?? '';
  const page = Number(params.get('page') ?? '1') || 1;
  const [query] = useDebouncedValue(search.trim(), 300);

  const q = useQuery({
    queryKey: [...OPERATOR_KEY, 'list', query, page],
    queryFn: () => operatorApi.list(query, page - 1, PAGE_SIZE),
    placeholderData: keepPreviousData,
  });
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1;

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
  const open = (f: OperatorFirma) => void navigate({ pathname: `/operator/${f.id}`, search: params.toString() });

  return (
    <>
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          {t('operator.intro')}
        </Text>
        <Group justify="space-between" align="center" gap="sm">
          <TextInput
            className={classes.search}
            leftSection={<IconSearch size={16} />}
            placeholder={t('operator.search')}
            aria-label={t('operator.search')}
            value={search}
            onChange={(e) => set('q', e.currentTarget.value || null)}
          />
          <Group gap="sm">
            {q.data && <Text c="dimmed">{q.data.total}</Text>}
            <Button leftSection={<IconPlus size={16} />} onClick={() => void navigate({ pathname: '/operator/new', search: params.toString() })}>
              {t('operator.add')}
            </Button>
          </Group>
        </Group>
        {q.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : q.isError ? (
          <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
        ) : q.data.items.length === 0 ? (
          <EmptyState text={t('operator.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('operator.col.company')}</Table.Th>
                    <Table.Th>{t('trips.country')}</Table.Th>
                    <Table.Th>{t('firma.plan')}</Table.Th>
                    <Table.Th className={classes.num}>{t('operator.col.vehicles')}</Table.Th>
                    <Table.Th className={classes.num}>{t('operator.col.users')}</Table.Th>
                    <Table.Th>{t('firma.ai_access')}</Table.Th>
                    <Table.Th>{t('users.status')}</Table.Th>
                    <Table.Th>{t('operator.expires')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {q.data.items.map((f) => {
                    const selected = String(f.id) === id;
                    const until = expiresDay(f.expiresAt);
                    return (
                      <Table.Tr
                        key={f.id}
                        tabIndex={0}
                        aria-current={selected ? 'true' : undefined}
                        className={cx(classes.row, selected && classes.selected)}
                        onClick={() => open(f)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            open(f);
                          }
                        }}
                      >
                        <Table.Td>
                          <Text span fw={500}>
                            {f.name}
                          </Text>
                          <Text span c="dimmed" size="xs" ml={6} className={classes.mono}>
                            {f.shortCode}
                          </Text>
                        </Table.Td>
                        <Table.Td>{f.country}</Table.Td>
                        <Table.Td className={classes.mono}>{f.plan}</Table.Td>
                        <Table.Td className={cx(classes.num, classes.mono, f.vehicles > f.vehicleLimit && classes.over)}>
                          {f.vehicles} / {f.vehicleLimit}
                        </Table.Td>
                        <Table.Td className={cx(classes.num, classes.mono)}>{f.users}</Table.Td>
                        <Table.Td>{t(f.aiAccess ? 'firma.ai_on' : 'firma.ai_off')}</Table.Td>
                        <Table.Td>
                          <FirmaStateChips firma={f} />
                        </Table.Td>
                        <Table.Td className={classes.mono}>{until ? formatDate(until, lang) : t('operator.no_end')}</Table.Td>
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
      </Stack>
      <Outlet />
    </>
  );
}
