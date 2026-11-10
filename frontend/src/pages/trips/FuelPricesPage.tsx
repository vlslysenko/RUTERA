import { useQuery } from '@tanstack/react-query';
import { Paper, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { calcApi, FUEL_PRICES_KEY } from '@/api/calc';
import { errorCode } from '@/api/errors';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { countryName } from '@/utils/countries';
import { formatDate } from '@/utils/deadline';
import classes from './Trips.module.css';

/**
 * Reference fuel prices per country (GET /app/calc/fuel-prices, shared by all firmas, kept by the operator): the
 * calculator takes them for a country where the firma has no fuel card purchases of its own. Read only here.
 */
export function FuelPricesPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const q = useQuery({ queryKey: FUEL_PRICES_KEY, queryFn: calcApi.fuelPrices });
  const price = (v: number | null, currency: string) =>
    v == null ? '—' : new Intl.NumberFormat(lang, { style: 'currency', currency, minimumFractionDigits: 3, maximumFractionDigits: 3 }).format(v);

  if (q.isPending) return <FullScreenLoader />;
  if (q.isError) return <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />;
  const rows = [...q.data].sort((a, b) => countryName(a.country, lang).localeCompare(countryName(b.country, lang), lang) || a.product.localeCompare(b.product));

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        {t('fuel_prices.hint')}
      </Text>
      {rows.length === 0 ? (
        <EmptyState text={t('fuel_prices.empty')} />
      ) : (
        <Paper withBorder className={classes.table}>
          <Table.ScrollContainer minWidth={640}>
            <Table fz="sm" striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('trips.country')}</Table.Th>
                  <Table.Th>{t('fuel_prices.product')}</Table.Th>
                  <Table.Th>{t('fuel_prices.date')}</Table.Th>
                  <Table.Th ta="right">{t('fuel_prices.net')}</Table.Th>
                  <Table.Th ta="right">{t('fuel_prices.gross')}</Table.Th>
                  <Table.Th ta="right">{t('fuel_prices.vat')}</Table.Th>
                  <Table.Th>{t('fuel_prices.source')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((p) => (
                  <Table.Tr key={p.id}>
                    <Table.Td>{countryName(p.country, lang)}</Table.Td>
                    <Table.Td>{t(`fuel.product.${p.product}`, { defaultValue: p.product })}</Table.Td>
                    <Table.Td>{formatDate(p.priceDate, lang)}</Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {price(p.net, p.currency)}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {price(p.gross, p.currency)}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {p.vatRate != null ? `${new Intl.NumberFormat(lang, { maximumFractionDigits: 1 }).format(p.vatRate)} %` : '—'}
                    </Table.Td>
                    <Table.Td>{p.source ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Paper>
      )}
    </Stack>
  );
}
