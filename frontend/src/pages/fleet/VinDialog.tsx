import { useState, type ReactNode } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Badge, Button, Checkbox, Group, Loader, Modal, NumberInput, Select, Stack, Table, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { fleetApi, vinKey, type VinConfidence, type VinDecode, type VinField, type VinSuggestion } from '@/api/fleet';
import { countryName } from '@/utils/countries';

/** Form values the dialog compares with; `untouched` = the user has not typed into the field yet (new vehicle). */
export type VinFormState = Record<VinField, { value: string | number | null; untouched: boolean }>;

const FIELD_LABEL: Record<VinField, string> = {
  make: 'fleet.make',
  model: 'fleet.model',
  kind: 'fleet.kind',
  axles: 'fleet.axles',
  fuelType: 'fleet.fuel_type',
  euroClass: 'fleet.euro_class',
};

const VALUE_PREFIX: Partial<Record<VinField, string>> = {
  kind: 'vehicle.kind',
  fuelType: 'vehicle.fuel',
  euroClass: 'vehicle.euro',
};

const CONFIDENCE_COLOR: Record<VinConfidence, string> = { HIGH: 'green', MEDIUM: 'blue', LOW: 'gray' };

const asText = (v: string | number | null) => (v == null ? '' : String(v).trim());

/** Whether a suggestion is ticked before the user decides: likely enough, new, and not over something typed. */
function preselected(s: VinSuggestion, value: string | null, form: VinFormState): boolean {
  const current = form[s.field];
  return (
    value != null &&
    s.confidence !== 'LOW' &&
    asText(current.value).toLowerCase() !== value.toLowerCase() &&
    (current.untouched || asText(current.value) === '')
  );
}

interface Choice {
  checked?: boolean;
  value?: string | null;
}

/**
 * "Data from the VIN, confirm?": what the VIN tells (maker, country, model year, check digit) and the field values
 * it suggests, each with its confidence. Only the ticked values go into the form. An optional first registration
 * year makes the Euro class suggestion possible when the VIN carries no year.
 */
export function VinDialog({
  vin,
  form,
  onClose,
  onApply,
}: {
  vin: string | null;
  form: VinFormState;
  onClose: () => void;
  onApply: (values: Partial<Record<VinField, string>>) => void;
}) {
  const { t, i18n } = useTranslation();
  const [yearInput, setYearInput] = useState<string | number>('');
  const [choices, setChoices] = useState<Partial<Record<VinField, Choice>>>({});
  const maxYear = new Date().getFullYear() + 1;
  const [debounced] = useDebouncedValue(yearInput, 400);
  const year = typeof debounced === 'number' && debounced >= 1980 && debounced <= maxYear ? debounced : null;

  const q = useQuery({
    queryKey: vinKey(vin ?? '', year),
    queryFn: () => fleetApi.decodeVin(vin ?? '', year),
    enabled: vin != null,
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  });
  const data: VinDecode | undefined = q.data;

  const close = () => {
    setChoices({});
    setYearInput('');
    onClose();
  };

  const valueOf = (s: VinSuggestion) => {
    const c = choices[s.field];
    return c && c.value !== undefined ? c.value : s.value;
  };
  const checkedOf = (s: VinSuggestion) => {
    const v = valueOf(s);
    const c = choices[s.field];
    if (v == null) return false;
    return c?.checked ?? preselected(s, v, form);
  };
  const label = (field: VinField, v: string) => (VALUE_PREFIX[field] ? t(`${VALUE_PREFIX[field]}.${v}`) : v);

  const suggestions = data?.status === 'OK' ? data.suggestions : [];
  const picked = suggestions.filter((s) => checkedOf(s));

  const apply = () => {
    const values: Partial<Record<VinField, string>> = {};
    for (const s of picked) {
      const v = valueOf(s);
      if (v != null) values[s.field] = v;
    }
    onApply(values);
    close();
  };

  const yearLine = () => {
    if (!data) return null;
    if (data.modelYear != null) {
      return (
        <Group gap={6}>
          <Text size="sm">{data.modelYear}</Text>
          {data.modelYearConfidence && (
            <Badge size="xs" variant="light" color={CONFIDENCE_COLOR[data.modelYearConfidence]}>
              {t(`fleet.vin.confidence.${data.modelYearConfidence}`)}
            </Badge>
          )}
        </Group>
      );
    }
    return (
      <Text size="sm" c="dimmed">
        {t(data.yearNotEncoded ? 'fleet.vin.year_not_encoded' : 'fleet.vin.unknown')}
      </Text>
    );
  };

  const checkLine = () => {
    if (!data?.checkDigit) return null;
    if (data.checkDigit === 'VALID') return <Text size="sm" c="green">{t('fleet.vin.check_valid')}</Text>;
    return data.checkDigitRequired ? (
      <Text size="sm" c="red">{t('fleet.vin.check_invalid_required')}</Text>
    ) : (
      <Text size="sm" c="dimmed">{t('fleet.vin.check_invalid_optional')}</Text>
    );
  };

  const infoRow = (title: string, content: ReactNode) => (
    <Group gap="xs" wrap="nowrap" align="baseline">
      <Text size="sm" c="dimmed" w={150} style={{ flexShrink: 0 }}>
        {title}
      </Text>
      <div>{content}</div>
    </Group>
  );

  return (
    <Modal opened={vin != null} onClose={close} title={t('fleet.vin.title')} size="lg">
      {q.isPending ? (
        <Group justify="center" p="lg">
          <Loader size="sm" />
        </Group>
      ) : q.isError ? (
        <Text c="red" size="sm">
          {t(errorCode(q.error))}
        </Text>
      ) : data && data.status !== 'OK' ? (
        <Text c="red" size="sm">
          {t(data.status === 'INVALID_CHARS' ? 'fleet.vin.invalid_chars' : 'fleet.vin.invalid_length')}
        </Text>
      ) : data ? (
        <Stack gap="md">
          <Text size="sm">{t('fleet.vin.intro')}</Text>
          <Stack gap={4}>
            {infoRow(t('fleet.vin'), <Text size="sm" ff="monospace">{data.vin}</Text>)}
            {infoRow(
              t('fleet.vin.manufacturer'),
              <Text size="sm">
                {data.manufacturer ?? t('fleet.vin.unknown')}
                {data.country && ` · ${countryName(data.country, i18n.resolvedLanguage ?? 'en')}`}
              </Text>,
            )}
            {infoRow(t('fleet.vin.model_year'), yearLine())}
            {infoRow(t('fleet.vin.check_digit'), checkLine())}
          </Stack>

          {data.manufacturer && (
            <NumberInput
              label={t('fleet.vin.registration_year')}
              description={t('fleet.vin.registration_year_hint')}
              value={yearInput}
              onChange={setYearInput}
              min={1980}
              max={maxYear}
              allowDecimal={false}
              allowNegative={false}
              hideControls
              w={260}
              rightSection={q.isFetching ? <Loader size="xs" /> : null}
            />
          )}

          {suggestions.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('fleet.vin.nothing')}
            </Text>
          ) : (
            <Table verticalSpacing={6} horizontalSpacing="xs">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th w={32} />
                  <Table.Th>{t('fleet.vin.col_field')}</Table.Th>
                  <Table.Th>{t('fleet.vin.col_value')}</Table.Th>
                  <Table.Th>{t('fleet.vin.col_confidence')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {suggestions.map((s) => {
                  const v = valueOf(s);
                  const current = asText(form[s.field].value);
                  const same = v != null && current.toLowerCase() === v.toLowerCase();
                  return (
                    <Table.Tr key={s.field}>
                      <Table.Td>
                        <Checkbox
                          aria-label={t(FIELD_LABEL[s.field])}
                          checked={checkedOf(s)}
                          disabled={v == null || same}
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            setChoices((c) => ({ ...c, [s.field]: { ...c[s.field], checked } }));
                          }}
                        />
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{t(FIELD_LABEL[s.field])}</Text>
                      </Table.Td>
                      <Table.Td>
                        {s.options.length > 1 ? (
                          <Select
                            size="xs"
                            data={s.options.map((o) => ({ value: o, label: label(s.field, o) }))}
                            value={v}
                            placeholder={t('fleet.vin.choose')}
                            onChange={(value) =>
                              setChoices((c) => ({ ...c, [s.field]: { value, checked: value != null } }))
                            }
                            allowDeselect={false}
                            comboboxProps={{ withinPortal: true }}
                          />
                        ) : (
                          <Text size="sm">
                            {v != null && label(s.field, v)}
                            {s.field === 'axles' && data.wheels && ` (${data.wheels.toLowerCase()})`}
                          </Text>
                        )}
                        <Text size="xs" c="dimmed">
                          {t(`fleet.vin.basis.${s.basis}`)}
                          {same
                            ? ` · ${t('fleet.vin.same')}`
                            : current !== '' && !form[s.field].untouched
                              ? ` · ${t('fleet.vin.current', { value: label(s.field, current) })}`
                              : ''}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Badge size="sm" variant="light" color={CONFIDENCE_COLOR[s.confidence]}>
                          {t(`fleet.vin.confidence.${s.confidence}`)}
                        </Badge>
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          )}

          {data.engines.length > 0 && (
            <Text size="sm">
              <Text span c="dimmed" size="sm">
                {t('fleet.vin.engines')}:{' '}
              </Text>
              {data.engines.join(', ')}
            </Text>
          )}
          <Text size="xs" c="dimmed">
            {t('fleet.vin.disclaimer')}
          </Text>
        </Stack>
      ) : null}

      <Group justify="flex-end" gap="xs" mt="lg">
        <Button variant="default" onClick={close}>
          {t('common.cancel')}
        </Button>
        <Button onClick={apply} disabled={picked.length === 0}>
          {t('fleet.vin.apply')}
        </Button>
      </Group>
    </Modal>
  );
}
