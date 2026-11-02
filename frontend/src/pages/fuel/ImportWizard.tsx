import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, FileInput, Group, Paper, Select, SimpleGrid, Stack, Stepper, Table, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconFileSpreadsheet, IconRefresh } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import {
  FUEL_CARDS_KEY,
  FUEL_IMPORTS_KEY,
  FUEL_PROVIDERS,
  FUEL_TX_KEY,
  fuelApi,
  MAPPING_FIELDS,
  REQUIRED_GROUPS,
  type FuelImport,
  type FuelMapping,
  type FuelPreview,
  type FuelPreviewValues,
  type FuelProvider,
  type FuelReadOptions,
  type MappingField,
} from '@/api/fuel';
import { useUser } from '@/auth/useAuth';
import { CURRENCIES, formatInstantShort, formatMoney } from '@/pages/trips/tripFormat';
import { cx } from '@/utils/cx';
import classes from './Fuel.module.css';

const MAX_BYTES = 20 * 1024 * 1024;
const AUTO: FuelReadOptions = { dateFormat: null, decimalComma: null, currency: null };

/** Required groups still without a column (date, product, gross or net, card number or plate). */
function missingGroups(mapping: FuelMapping): MappingField[][] {
  return REQUIRED_GROUPS.filter((g) => !g.some((f) => mapping[f]));
}

/** Error code of a 422 on the file (columns_missing / unreadable / too_large), else the general code. */
function fileError(e: unknown): string {
  return e instanceof ApiError ? (e.details?.file ?? e.details?.provider ?? e.details?.mapping ?? e.details?.options ?? e.code) : errorCode(e);
}

/** Preview columns: what was read from each row, in the order a person checks a receipt. */
const PREVIEW_COLUMNS: { key: keyof FuelPreviewValues; field: MappingField; label: string }[] = [
  { key: 'at', field: 'date', label: 'fuel.at' },
  { key: 'cardNumber', field: 'cardNumber', label: 'fuel.field.cardNumber' },
  { key: 'plate', field: 'plate', label: 'fuel.field.plate' },
  { key: 'station', field: 'station', label: 'fuel.field.station' },
  { key: 'country', field: 'country', label: 'fuel.field.country' },
  { key: 'productRaw', field: 'product', label: 'fuel.field.product' },
  { key: 'quantity', field: 'quantity', label: 'fuel.field.quantity' },
  { key: 'unitPrice', field: 'unitPrice', label: 'fuel.field.unitPrice' },
  { key: 'net', field: 'net', label: 'fuel.field.net' },
  { key: 'vat', field: 'vat', label: 'fuel.field.vat' },
  { key: 'gross', field: 'gross', label: 'fuel.field.gross' },
  { key: 'odometer', field: 'odometer', label: 'fuel.field.odometer' },
];

/**
 * Import of a provider file in three steps: choose the file (and provider); check how the first rows are read, correct
 * the column of each field and the read options and check again (the mapping is saved for the next file of this
 * provider); then the result with new, duplicate and bad rows.
 */
export function ImportWizard({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [step, setStep] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [provider, setProvider] = useState<FuelProvider | null>(null);
  const [preview, setPreview] = useState<FuelPreview | null>(null);
  const [mapping, setMapping] = useState<FuelMapping>({});
  const [options, setOptions] = useState<FuelReadOptions>(AUTO);
  const [saveMapping, setSaveMapping] = useState(true);
  const [result, setResult] = useState<FuelImport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** First read: the saved mapping or recognised headers; later reads: what the user set. */
  const runPreview = async (p: FuelProvider | null, m?: FuelMapping, o?: FuelReadOptions) => {
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setError('fuel.import.too_large');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await fuelApi.preview(file, p, m, o);
      setPreview(res);
      setProvider(res.provider);
      setMapping(res.mapping);
      setOptions(res.options);
      setStep(1);
    } catch (e) {
      setError(fileError(e));
    } finally {
      setBusy(false);
    }
  };

  const runImport = async () => {
    if (!file || !provider) return;
    setBusy(true);
    setError(null);
    try {
      const res = await fuelApi.importFile(file, provider, mapping, options, saveMapping);
      setResult(res);
      setStep(2);
      void qc.invalidateQueries({ queryKey: FUEL_IMPORTS_KEY });
      void qc.invalidateQueries({ queryKey: FUEL_TX_KEY });
      // Unknown cards were created.
      void qc.invalidateQueries({ queryKey: FUEL_CARDS_KEY });
      notifications.show({ color: 'green', message: t('fuel.import.done', { n: res.rowsNew }) });
    } catch (e) {
      setError(fileError(e));
    } finally {
      setBusy(false);
    }
  };

  const missing = missingGroups(mapping);
  const changed = preview && (JSON.stringify(mapping) !== JSON.stringify(preview.mapping) || JSON.stringify(options) !== JSON.stringify(preview.options));
  const headerOptions = (preview?.headers ?? []).map((h) => ({ value: h, label: h }));
  const required = new Set(REQUIRED_GROUPS.flat());
  const cell = (key: keyof FuelPreviewValues, v: FuelPreviewValues) => {
    const x = v[key];
    if (x == null) return '';
    if (key === 'at') return formatInstantShort(String(x), lang, tz);
    if (key === 'net' || key === 'vat' || key === 'gross' || key === 'unitPrice') return formatMoney(Number(x), v.currency, lang);
    if (key === 'quantity') return `${new Intl.NumberFormat(lang, { maximumFractionDigits: 3 }).format(Number(x))} ${v.unit ? t(`fuel.unit.${v.unit}`) : ''}`;
    if (key === 'productRaw') return `${x}${v.product ? ` → ${t(`fuel.product.${v.product}`)}` : ''}`;
    return String(x);
  };

  return (
    <Paper withBorder p="md">
      <Stepper active={step} size="sm" mb="md" allowNextStepsSelect={false}>
        <Stepper.Step label={t('fuel.import.step_file')} />
        <Stepper.Step label={t('fuel.import.step_columns')} />
        <Stepper.Step label={t('fuel.import.step_result')} />
      </Stepper>

      {error && (
        <Alert color="red" variant="light" mb="sm" icon={<IconAlertTriangle size={16} />}>
          {t(error)}
        </Alert>
      )}

      {step === 0 && (
        <Stack gap="sm" maw={520}>
          <FileInput
            label={t('fuel.import.file')}
            description={t('fuel.import.file_hint')}
            accept=".csv,.txt,.xlsx"
            leftSection={<IconFileSpreadsheet size={16} />}
            value={file}
            onChange={(f) => {
              setFile(f);
              setError(null);
            }}
            clearable
          />
          <Select
            label={t('fuel.provider')}
            description={t('fuel.import.provider_hint')}
            data={FUEL_PROVIDERS.map((p) => ({ value: p, label: t(`fuel.provider.${p}`) }))}
            value={provider}
            onChange={(v) => setProvider(v as FuelProvider | null)}
            clearable
          />
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button onClick={() => void runPreview(provider)} disabled={!file} loading={busy}>
              {t('fuel.import.check')}
            </Button>
          </Group>
        </Stack>
      )}

      {step === 1 && preview && (
        <Stack gap="sm">
          <Group gap="sm" align="flex-end" wrap="wrap">
            <Select
              label={t('fuel.provider')}
              data={FUEL_PROVIDERS.map((p) => ({ value: p, label: t(`fuel.provider.${p}`) }))}
              value={provider}
              // Another provider: its saved mapping or its header synonyms.
              onChange={(v) => v && void runPreview(v as FuelProvider)}
              allowDeselect={false}
              w={200}
            />
            <Text size="sm" c="dimmed" mb={8}>
              {t('fuel.import.file_info', { name: file?.name ?? '', rows: preview.dataRows, header: preview.headerRow })}
            </Text>
          </Group>

          <Text size="xs" fw={600} tt="uppercase" c="dimmed">
            {t('fuel.import.columns')}
          </Text>
          <SimpleGrid cols={{ base: 2, sm: 3, lg: 4 }} spacing="xs" verticalSpacing={6}>
            {MAPPING_FIELDS.map((f) => (
              <Select
                key={f}
                size="xs"
                label={`${t(`fuel.field.${f}`)}${required.has(f) ? ' *' : ''}`}
                placeholder={t('fuel.import.not_in_file')}
                data={headerOptions}
                value={mapping[f] ?? null}
                onChange={(v) => setMapping((m) => ({ ...m, [f]: v }))}
                clearable
                searchable
                error={missing.some((g) => g.includes(f)) ? ' ' : undefined}
              />
            ))}
          </SimpleGrid>
          {missing.length > 0 && (
            <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />}>
              {t('fuel.import.missing', { fields: missing.map((g) => g.map((f) => t(`fuel.field.${f}`)).join(' / ')).join(', ') })}
            </Alert>
          )}

          <Group gap="sm" align="flex-end" wrap="wrap">
            <TextInput
              size="xs"
              label={t('fuel.import.date_format')}
              placeholder={t('fuel.import.auto')}
              value={options.dateFormat ?? ''}
              onChange={(e) => setOptions((o) => ({ ...o, dateFormat: e.currentTarget.value.trim() || null }))}
              w={170}
            />
            <Select
              size="xs"
              label={t('fuel.import.decimal')}
              data={[
                { value: 'auto', label: t('fuel.import.auto') },
                { value: 'comma', label: t('fuel.import.decimal_comma') },
                { value: 'point', label: t('fuel.import.decimal_point') },
              ]}
              value={options.decimalComma == null ? 'auto' : options.decimalComma ? 'comma' : 'point'}
              onChange={(v) => setOptions((o) => ({ ...o, decimalComma: v === 'comma' ? true : v === 'point' ? false : null }))}
              allowDeselect={false}
              w={190}
            />
            <Select
              size="xs"
              label={t('fuel.import.default_currency')}
              placeholder={t('fuel.import.firma_currency')}
              data={CURRENCIES}
              value={options.currency}
              onChange={(v) => setOptions((o) => ({ ...o, currency: v }))}
              clearable
              searchable
              w={170}
            />
            <Button size="xs" variant={changed ? 'filled' : 'default'} leftSection={<IconRefresh size={14} />} onClick={() => void runPreview(provider, mapping, options)} loading={busy}>
              {t('fuel.import.check_again')}
            </Button>
          </Group>

          <div className={classes.previewWrap}>
            <Table className={classes.preview} withColumnBorders>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>#</Table.Th>
                  {PREVIEW_COLUMNS.map((c) => (
                    <Table.Th key={c.key}>
                      <span className={classes.header}>{t(c.label)}</span>
                    </Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {preview.rows.map((r) => (
                  <Table.Tr key={r.row}>
                    <Table.Td>{r.row}</Table.Td>
                    {PREVIEW_COLUMNS.map((c) => {
                      const code = r.errors[c.field];
                      return (
                        <Table.Td key={c.key} className={cx(!preview.mapping[c.field] && classes.unused, code && classes.bad)}>
                          {code ? (
                            <Tooltip label={t(code)}>
                              <span>{cell(c.key, r.values) || '!'}</span>
                            </Tooltip>
                          ) : (
                            cell(c.key, r.values)
                          )}
                        </Table.Td>
                      );
                    })}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
          {changed && (
            <Text size="sm" c="orange">
              {t('fuel.import.changed_hint')}
            </Text>
          )}
          <Checkbox label={t('fuel.import.save_mapping', { provider: t(`fuel.provider.${provider ?? 'OTHER'}`) })} checked={saveMapping} onChange={(e) => setSaveMapping(e.currentTarget.checked)} />
          <Group justify="space-between">
            <Button variant="subtle" onClick={() => setStep(0)} disabled={busy}>
              {t('fuel.import.back')}
            </Button>
            <Group gap="xs">
              <Button variant="default" onClick={onClose} disabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button onClick={() => void runImport()} disabled={missing.length > 0} loading={busy}>
                {t('fuel.import.run', { n: preview.dataRows })}
              </Button>
            </Group>
          </Group>
        </Stack>
      )}

      {step === 2 && result && (
        <Stack gap="sm">
          <Group gap="xl">
            <Stat label={t('fuel.import.rows_new')} value={result.rowsNew} tone="good" />
            <Stat label={t('fuel.import.rows_dup')} value={result.rowsDup} />
            <Stat label={t('fuel.import.rows_error')} value={result.rowsError} tone={result.rowsError ? 'bad' : undefined} />
            <Stat label={t('fuel.import.rows_total')} value={result.rowsTotal} />
          </Group>
          {result.errors.length > 0 && (
            <Stack gap={2}>
              {result.errors.slice(0, 20).map((e, i) => (
                <Text key={i} size="sm" c="red">
                  {t('fuel.import.row_error', { row: e.row, field: t(`fuel.field.${e.field}`), error: t(e.code) })}
                </Text>
              ))}
              {result.errors.length > 20 && (
                <Text size="sm" c="dimmed">
                  {t('fuel.import.more_errors', { n: result.errors.length - 20 })}
                </Text>
              )}
            </Stack>
          )}
          <Group justify="flex-end" gap="xs">
            <Button
              variant="default"
              onClick={() => {
                setStep(0);
                setFile(null);
                setPreview(null);
                setResult(null);
                setOptions(AUTO);
              }}
            >
              {t('fuel.import.another')}
            </Button>
            <Button
              onClick={() => {
                onClose();
                const q = new URLSearchParams();
                if (result.periodFrom) q.set('from', result.periodFrom);
                if (result.periodTo) q.set('to', result.periodTo);
                void navigate(`/fuel?${q.toString()}`);
              }}
            >
              {t('fuel.import.show')}
            </Button>
          </Group>
        </Stack>
      )}
    </Paper>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'good' | 'bad' }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} c={tone === 'good' ? 'green' : tone === 'bad' ? 'red' : undefined}>
        {value}
      </Text>
    </Stack>
  );
}
