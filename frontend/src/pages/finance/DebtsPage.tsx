import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Anchor, Group, Paper, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { DEBTS_KEY, financeApi, type DebtRow } from '@/api/finance';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { formatMoney } from '@/pages/trips/tripFormat';
import { formatDate } from '@/utils/deadline';
import { cx } from '@/utils/cx';
import classes from './Finance.module.css';
import { DateField } from '@/forms/DateField';

const BUCKETS = ['notDue', 'd1to30', 'd31to60', 'd61to90', 'over90'] as const;
type Bucket = (typeof BUCKETS)[number];

/** Sums per currency of the rows (debts of different currencies are never added together). */
function totals(rows: DebtRow[]): DebtRow[] {
  const by = new Map<string, DebtRow>();
  for (const r of rows) {
    const s = by.get(r.currency) ?? {
      contractor: { id: 0, name: '' },
      currency: r.currency,
      open: 0,
      overdue: 0,
      invoices: 0,
      oldestDueDate: null,
      maxDaysOverdue: 0,
      buckets: { notDue: 0, d1to30: 0, d31to60: 0, d61to90: 0, over90: 0 },
    };
    s.open += r.open;
    s.overdue += r.overdue;
    s.invoices += r.invoices;
    s.maxDaysOverdue = Math.max(s.maxDaysOverdue, r.maxDaysOverdue);
    for (const b of BUCKETS) s.buckets[b] += r.buckets[b];
    by.set(r.currency, s);
  }
  return [...by.values()];
}

/** Who owes us what: open sales invoices per contractor and currency, split by how long they are overdue. */
export function DebtsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const [asOf, setAsOf] = useState('');
  const q = useQuery({ queryKey: [...DEBTS_KEY, asOf], queryFn: () => financeApi.debts(asOf || undefined) });
  const money = (v: number, currency: string) => (v ? formatMoney(Math.round(v * 100) / 100, currency, lang) : '—');
  const bucketClass = (b: Bucket) => (b === 'notDue' ? undefined : b === 'd1to30' ? classes.late1 : b === 'd31to60' ? classes.late2 : classes.late3);

  return (
    <Stack gap="sm">
      <Group gap="sm" align="flex-end">
        <DateField label={t('debts.as_of')} description={t('debts.as_of_hint')} value={asOf} onChange={setAsOf} w={200} />
      </Group>
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState text={t('debts.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('invoices.contractor')}</Table.Th>
                <Table.Th className={classes.num}>{t('invoices.open')}</Table.Th>
                <Table.Th className={classes.num}>{t('debts.overdue')}</Table.Th>
                {BUCKETS.map((b) => (
                  <Table.Th key={b} className={cx(classes.num, classes.hideSm)}>
                    {t(`debts.bucket.${b}`)}
                  </Table.Th>
                ))}
                <Table.Th className={cx(classes.num, classes.hideSm)}>{t('debts.invoices')}</Table.Th>
                <Table.Th>{t('debts.oldest_due')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.map((r) => (
                <Table.Tr key={`${r.contractor.id}-${r.currency}`}>
                  <Table.Td>
                    <Anchor component={Link} to={`/finance?contractor=${r.contractor.id}`} fw={500} size="sm">
                      {r.contractor.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td className={classes.num}>{money(r.open, r.currency)}</Table.Td>
                  <Table.Td className={cx(classes.num, r.overdue > 0 && classes.overdue)}>{money(r.overdue, r.currency)}</Table.Td>
                  {BUCKETS.map((b) => (
                    <Table.Td key={b} className={cx(classes.num, classes.hideSm, r.buckets[b] > 0 && bucketClass(b))}>
                      {money(r.buckets[b], r.currency)}
                    </Table.Td>
                  ))}
                  <Table.Td className={cx(classes.num, classes.hideSm)}>{r.invoices}</Table.Td>
                  <Table.Td className={classes.mono}>
                    {r.oldestDueDate ? formatDate(r.oldestDueDate, lang) : '—'}
                    {r.maxDaysOverdue > 0 && (
                      <Text span size="xs" c="red" ml={6}>
                        {t('invoices.overdue_days', { days: r.maxDaysOverdue })}
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
            <Table.Tfoot>
              {totals(q.data).map((s) => (
                <Table.Tr key={s.currency} className={classes.totalRow}>
                  <Table.Td>{t('debts.total', { currency: s.currency })}</Table.Td>
                  <Table.Td className={classes.num}>{money(s.open, s.currency)}</Table.Td>
                  <Table.Td className={cx(classes.num, s.overdue > 0 && classes.overdue)}>{money(s.overdue, s.currency)}</Table.Td>
                  {BUCKETS.map((b) => (
                    <Table.Td key={b} className={cx(classes.num, classes.hideSm)}>
                      {money(s.buckets[b], s.currency)}
                    </Table.Td>
                  ))}
                  <Table.Td className={cx(classes.num, classes.hideSm)}>{s.invoices}</Table.Td>
                  <Table.Td />
                </Table.Tr>
              ))}
            </Table.Tfoot>
          </Table>
        </Paper>
      )}
    </Stack>
  );
}
