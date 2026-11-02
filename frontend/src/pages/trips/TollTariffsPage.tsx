import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ActionIcon, Alert, Button, Group, NumberInput, Paper, Select, Stack, Table, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { calcApi, TOLLS_KEY, type TollTariff, type TollTariffInput } from '@/api/calc';
import { EURO_CLASSES } from '@/api/fleet';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { decimalSeparator } from '@/forms/number';
import { countryName, countryOptions } from '@/utils/countries';
import { formatDate } from '@/utils/deadline';
import { CURRENCIES, formatMoney } from './tripFormat';
import classes from './Trips.module.css';

interface Row {
  country: string | null;
  euroClass: string | null;
  axlesMin: number | string;
  weightMinKg: number | string;
  ratePerKm: number | string;
  currency: string | null;
  validFrom: string;
  notes: string;
}

const EMPTY: Row = { country: 'PL', euroClass: null, axlesMin: '', weightMinKg: '', ratePerKm: '', currency: 'EUR', validFrom: '', notes: '' };
const num = (v: number | string) => (v === '' ? null : Number(v));

function TariffForm({ tariff, onDone }: { tariff: TollTariff | null; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const qc = useQueryClient();
  const [row, setRow] = useState<Row>(
    tariff
      ? { country: tariff.country, euroClass: tariff.euroClass, axlesMin: tariff.axlesMin ?? '', weightMinKg: tariff.weightMinKg ?? '', ratePerKm: tariff.ratePerKm, currency: tariff.currency, validFrom: tariff.validFrom ?? '', notes: tariff.notes ?? '' }
      : EMPTY,
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const err = (k: string) => (errors[k] ? t(errors[k]) : undefined);
  const set = (patch: Partial<Row>) => setRow((r) => ({ ...r, ...patch }));

  const save = async () => {
    const local: Record<string, string> = {};
    if (!row.country) local.country = 'validation.required';
    if (num(row.ratePerKm) == null) local.ratePerKm = 'validation.required';
    if (!row.currency) local.currency = 'validation.required';
    setErrors(local);
    if (Object.keys(local).length) return;
    const body: TollTariffInput = {
      country: row.country ?? '',
      euroClass: row.euroClass,
      axlesMin: num(row.axlesMin),
      weightMinKg: num(row.weightMinKg),
      ratePerKm: num(row.ratePerKm) ?? 0,
      currency: row.currency ?? '',
      validFrom: row.validFrom || null,
      notes: row.notes.trim() || null,
    };
    setBusy(true);
    try {
      if (tariff) await calcApi.updateToll(tariff.id, body);
      else await calcApi.createToll(body);
      void qc.invalidateQueries({ queryKey: TOLLS_KEY });
      notifications.show({ color: 'green', message: t('tolls.saved') });
      onDone();
    } catch (e) {
      if (e instanceof ApiError && e.details) setErrors(e.details);
      else notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper withBorder p="sm">
      <Stack gap="sm">
        <Group grow align="flex-start">
          <Select label={t('trips.country')} data={countryOptions(lang, row.country)} value={row.country} onChange={(v) => set({ country: v })} searchable error={err('country')} />
          <NumberInput label={t('tolls.rate_per_km')} value={row.ratePerKm} onChange={(v) => set({ ratePerKm: v })} decimalScale={4} decimalSeparator={decimalSeparator(lang)} allowNegative={false} hideControls error={err('ratePerKm')} />
          <Select label={t('orders.currency')} data={CURRENCIES} value={row.currency} onChange={(v) => set({ currency: v })} searchable error={err('currency')} />
        </Group>
        <Group grow align="flex-start">
          <Select label={t('tolls.euro_class')} placeholder={t('tolls.any')} data={EURO_CLASSES.map((e) => ({ value: e, label: t(`vehicle.euro.${e}`) }))} value={row.euroClass} onChange={(v) => set({ euroClass: v })} clearable error={err('euroClass')} />
          <NumberInput label={t('tolls.axles_min')} placeholder={t('tolls.any')} value={row.axlesMin} onChange={(v) => set({ axlesMin: v })} min={2} max={9} allowDecimal={false} error={err('axlesMin')} />
          <NumberInput label={t('tolls.weight_min')} placeholder={t('tolls.any')} value={row.weightMinKg} onChange={(v) => set({ weightMinKg: v })} min={0} allowDecimal={false} thousandSeparator=" " hideControls error={err('weightMinKg')} />
        </Group>
        <Group grow align="flex-start">
          <TextInput type="date" label={t('tolls.valid_from')} value={row.validFrom} onChange={(e) => set({ validFrom: e.currentTarget.value })} error={err('validFrom')} />
          <TextInput label={t('drivers.notes')} value={row.notes} onChange={(e) => set({ notes: e.currentTarget.value })} maxLength={500} error={err('notes')} />
        </Group>
        <Text size="xs" c="dimmed">
          {t('tolls.match_hint')}
        </Text>
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onDone} disabled={busy}>
            {t('common.cancel')}
          </Button>
          <Button onClick={() => void save()} loading={busy}>
            {tariff ? t('common.save') : t('common.create')}
          </Button>
        </Group>
      </Stack>
    </Paper>
  );
}

/** Toll tariffs per km of the firma, by country and optionally euro class, axles and weight (most specific wins). */
export function TollTariffsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const qc = useQueryClient();
  const canManage = hasPrivilege(me, PRIV.TRIP_TRIP_MANAGE);
  const q = useQuery({ queryKey: TOLLS_KEY, queryFn: calcApi.tolls });
  const [editing, setEditing] = useState<TollTariff | 'new' | null>(null);
  const [deleting, setDeleting] = useState<TollTariff | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (tariff: TollTariff) => {
    setBusy(true);
    try {
      await calcApi.removeToll(tariff.id);
      void qc.invalidateQueries({ queryKey: TOLLS_KEY });
      notifications.show({ color: 'green', message: t('tolls.deleted') });
      setDeleting(null);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const criteria = (x: TollTariff) =>
    [x.euroClass ? t(`vehicle.euro.${x.euroClass}`) : null, x.axlesMin != null ? t('tolls.axles_from', { n: x.axlesMin }) : null, x.weightMinKg != null ? t('tolls.weight_from', { kg: new Intl.NumberFormat(lang).format(x.weightMinKg) }) : null]
      .filter(Boolean)
      .join(' · ') || t('tolls.any');

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('tolls.intro')}
        </Text>
        {canManage && editing === null && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('tolls.add')}
          </Button>
        )}
      </Group>
      {editing === 'new' && <TariffForm tariff={null} onDone={() => setEditing(null)} />}
      {editing !== null && editing !== 'new' && <TariffForm key={editing.id} tariff={editing} onDone={() => setEditing(null)} />}
      {deleting && (
        <Alert color="red" variant="light">
          <Group gap="sm">
            <Text size="sm">{t('tolls.delete_confirm', { country: countryName(deleting.country, lang) })}</Text>
            <Button size="xs" color="red" onClick={() => void remove(deleting)} loading={busy}>
              {t('common.confirm')}
            </Button>
            <Button size="xs" variant="default" onClick={() => setDeleting(null)} disabled={busy}>
              {t('common.cancel')}
            </Button>
          </Group>
        </Alert>
      )}
      {q.isPending ? (
        <FullScreenLoader fullScreen={false} />
      ) : q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState text={t('tolls.empty')} />
      ) : (
        <Paper className={classes.tableWrap}>
          <Table className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('trips.country')}</Table.Th>
                <Table.Th>{t('tolls.criteria')}</Table.Th>
                <Table.Th className={classes.num}>{t('tolls.rate_per_km')}</Table.Th>
                <Table.Th>{t('tolls.valid_from')}</Table.Th>
                <Table.Th>{t('drivers.notes')}</Table.Th>
                {canManage && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {q.data.map((x) => (
                <Table.Tr key={x.id}>
                  <Table.Td>{countryName(x.country, lang)}</Table.Td>
                  <Table.Td>{criteria(x)}</Table.Td>
                  <Table.Td className={classes.num}>{formatMoney(x.ratePerKm, x.currency, lang)}</Table.Td>
                  <Table.Td className={classes.mono}>{x.validFrom ? formatDate(x.validFrom, lang) : '—'}</Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1} maw={280}>
                      {x.notes ?? '—'}
                    </Text>
                  </Table.Td>
                  {canManage && (
                    <Table.Td>
                      <Group gap={2} wrap="nowrap" justify="flex-end">
                        <Tooltip label={t('common.edit')}>
                          <ActionIcon variant="subtle" color="gray" size="sm" onClick={() => setEditing(x)} aria-label={t('common.edit')}>
                            <IconPencil size={15} />
                          </ActionIcon>
                        </Tooltip>
                        <Tooltip label={t('common.delete')}>
                          <ActionIcon variant="subtle" color="red" size="sm" onClick={() => setDeleting(x)} aria-label={t('common.delete')}>
                            <IconTrash size={15} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
}
