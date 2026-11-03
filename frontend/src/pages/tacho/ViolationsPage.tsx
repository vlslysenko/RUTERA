import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Pagination, Paper, Select, Stack, Switch, Table, Text } from '@mantine/core';
import { IconTools } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { SEVERITIES, VIOLATIONS_KEY, hosApi, type Severity, type Violation, type ViolationFilter } from '@/api/hos';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { Plate } from '@/components/Plate';
import { cx } from '@/utils/cx';
import { DateField } from '@/forms/DateField';
import { formatInstant } from './format';
import { formatMinutes } from './hosFormat';
import { ReviewChip, SeverityChip } from './ViolationChips';
import classes from './Hos.module.css';

const PAGE_SIZE = 50;

/**
 * Violations of 561/2006 found in the tachograph data, newest first; a row opens the violation in the right panel.
 * Filters live in the URL (?driver=&from=&to=&severity=&all=1), so the activity view can link to one day.
 */
export function ViolationsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  // Violation times in the firma zone, the zone the backend cuts days and the from/to filter in.
  const tz = me.firma?.timezone;
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list, enabled: hasPrivilege(me, PRIV.DRIVER_VIEW) });

  const page = Number(params.get('page') ?? '1');
  const filter: ViolationFilter = {
    driverId: params.get('driver') ? Number(params.get('driver')) : undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    severity: (params.get('severity') as Severity | null) ?? undefined,
    unacknowledged: params.get('all') === '1' ? undefined : true,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const q = useQuery({
    queryKey: [...VIOLATIONS_KEY, 'list', filter],
    queryFn: () => hosApi.violations(filter),
    placeholderData: keepPreviousData,
  });

  /** Changing a filter starts again from the first page. */
  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });

  const open = (v: Violation) => void navigate({ pathname: `/tacho/violations/${v.id}`, search: params.toString() });
  const notReady = q.isError && q.error instanceof ApiError && q.error.status === 404;
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1;

  return (
    <>
      {notReady ? (
        <EmptyState icon={<IconTools size={28} stroke={1.5} />} text={t('common.in_development')} />
      ) : (
        <Stack gap="sm">
          <div className={classes.filters}>
            {drivers.data && (
              <Select
                placeholder={t('fleet.driver')}
                aria-label={t('fleet.driver')}
                data={drivers.data.map((d) => ({ value: String(d.id), label: d.name }))}
                value={params.get('driver')}
                onChange={(v) => set('driver', v)}
                clearable
                searchable
                w={200}
              />
            )}
            <Select
              placeholder={t('hos.v.col.severity')}
              aria-label={t('hos.v.col.severity')}
              data={SEVERITIES.map((s) => ({ value: s, label: t(`hos.severity.${s}`) }))}
              value={params.get('severity')}
              onChange={(v) => set('severity', v)}
              clearable
              w={180}
            />
            <DateField
              aria-label={t('tacho.filter.from')}
              title={t('tacho.filter.from')}
              value={params.get('from') ?? ''}
              onChange={(v) => set('from', v || null)}
              w={150}
            />
            <DateField
              aria-label={t('tacho.filter.to')}
              title={t('tacho.filter.to')}
              value={params.get('to') ?? ''}
              onChange={(v) => set('to', v || null)}
              w={150}
            />
            <Switch
              label={t('hos.v.only_new')}
              checked={params.get('all') !== '1'}
              onChange={(e) => set('all', e.currentTarget.checked ? null : '1')}
              mb={6}
            />
            {q.data && (
              <Text c="dimmed" mb={6}>
                {q.data.total}
              </Text>
            )}
          </div>

          {q.isPending ? (
            <FullScreenLoader fullScreen={false} />
          ) : q.isError ? (
            <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
          ) : q.data.items.length === 0 ? (
            <EmptyState text={t('hos.v.empty')} />
          ) : (
            <>
              <Paper className={classes.tableWrap}>
                <Table className={classes.table}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('hos.v.col.when')}</Table.Th>
                      <Table.Th>{t('fleet.driver')}</Table.Th>
                      <Table.Th>{t('hos.v.col.rule')}</Table.Th>
                      <Table.Th>{t('hos.v.col.severity')}</Table.Th>
                      <Table.Th>{t('hos.v.col.value')}</Table.Th>
                      <Table.Th>{t('drivers.vehicle')}</Table.Th>
                      <Table.Th>{t('users.status')}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {q.data.items.map((v) => {
                      const selected = String(v.id) === id;
                      return (
                        <Table.Tr
                          key={v.id}
                          tabIndex={0}
                          aria-current={selected ? 'true' : undefined}
                          className={cx(classes.clickRow, selected && classes.selected)}
                          onClick={() => open(v)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              open(v);
                            }
                          }}
                        >
                          <Table.Td className={classes.mono}>{formatInstant(v.startAt, lang, tz)}</Table.Td>
                          <Table.Td>
                            <Text span fw={500}>
                              {v.driver.name}
                            </Text>
                          </Table.Td>
                          <Table.Td>{t(`hos.rule.${v.rule}`)}</Table.Td>
                          <Table.Td>
                            <SeverityChip severity={v.severity} />
                          </Table.Td>
                          <Table.Td className={classes.mono}>
                            {formatMinutes(t, v.actualValue)} / {formatMinutes(t, v.limitValue)}
                          </Table.Td>
                          <Table.Td>{v.vehicle ? <Plate>{v.vehicle.plate}</Plate> : <Text span c="dimmed">—</Text>}</Table.Td>
                          <Table.Td>
                            <ReviewChip v={v} />
                          </Table.Td>
                        </Table.Tr>
                      );
                    })}
                  </Table.Tbody>
                </Table>
              </Paper>
              {pages > 1 && (
                <Pagination
                  total={pages}
                  value={page}
                  onChange={(p) =>
                    setParams((sp) => {
                      sp.set('page', String(p));
                      return sp;
                    })
                  }
                  size="sm"
                />
              )}
            </>
          )}
        </Stack>
      )}
      <Outlet />
    </>
  );
}
