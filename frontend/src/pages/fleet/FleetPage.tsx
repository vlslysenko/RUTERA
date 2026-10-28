import { Outlet, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Paper, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { fleetApi, type Vehicle } from '@/api/fleet';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { PageHeader } from '@/components/PageHeader';
import { Plate } from '@/components/Plate';
import { cx } from '@/utils/cx';
import { VehicleStatusChip } from './VehicleStatusChip';
import classes from './FleetPage.module.css';

export const VEHICLES_QUERY_KEY = ['fleet', 'vehicles'] as const;

/** Fleet list; a row opens the vehicle card in the right panel (child route /fleet/:id). */
export function FleetPage() {
  const { t, i18n } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: VEHICLES_QUERY_KEY, queryFn: fleetApi.list });

  const km = new Intl.NumberFormat(i18n.resolvedLanguage ?? 'en');
  const open = (v: Vehicle) => void navigate(`/fleet/${v.id}`);

  return (
    <>
      <PageHeader title={t('fleet.title')} subtitle={q.data?.length} />
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('fleet.plate')}</Table.Th>
                <Table.Th>{t('fleet.model')}</Table.Th>
                <Table.Th>{t('fleet.status')}</Table.Th>
                <Table.Th>{t('fleet.driver')}</Table.Th>
                <Table.Th className={classes.num}>{t('fleet.odometer')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.map((v) => {
                const selected = String(v.id) === id;
                return (
                  <Table.Tr
                    key={v.id}
                    tabIndex={0}
                    aria-current={selected ? 'true' : undefined}
                    className={cx(classes.row, selected && classes.selected)}
                    onClick={() => open(v)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        open(v);
                      }
                    }}
                  >
                    <Table.Td>
                      <Plate>{v.plate}</Plate>
                    </Table.Td>
                    <Table.Td>{v.model}</Table.Td>
                    <Table.Td>
                      <VehicleStatusChip status={v.status} />
                    </Table.Td>
                    <Table.Td>
                      {v.driver ?? (
                        <Text span c="dimmed">
                          {t('fleet.no_driver')}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td className={cx(classes.num, classes.mono)}>{km.format(v.odometerKm)} km</Table.Td>
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
