import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, get, useFieldArray, useForm, type Control, type FieldErrors, type FieldPath, type UseFormRegister } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Alert, Button, Group, NumberInput, Paper, SegmentedControl, Select, Stack, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { CMR_KEY, cmrApi, type Cmr, type CmrInput, type CmrParty, type CmrSignature } from '@/api/cmr';
import { CONTRACTORS_KEY, contractorsApi, ORDERS_KEY, ordersApi, TRIPS_KEY, tripsApi } from '@/api/trips';
import { hasAnyPrivilege, hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { decimalSeparator, numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { countryOptions } from '@/utils/countries';
import { boxLabel, numberInText } from './cmrFormat';
import { CURRENCIES } from './tripFormat';
import classes from './Trips.module.css';
import { FormDateField } from '@/forms/DateField';

const LIST = '/trips/cmr';
const NUMBER_RE = /^[\p{L}\p{N}][\p{L}\p{N} ./_-]*$/u;
const MAX_LINES = 30;
const MAX_SUCCESSIVE = 5;

/* Same limits as CmrDto.Input; CmrService checks countries, numbers and the cash on delivery currency again. */
const text = (max: number) => z.string().max(max, 'validation.too_long');
const party = z.object({ name: text(200), address: text(500), country: z.string().nullable(), vatId: text(32) });
const signature = z.object({ name: text(100), place: text(100), date: z.string() });
const line = z.object({
  marks: text(100),
  packages: numberField(0, 1_000_000, { integer: true }),
  packing: text(100),
  nature: text(300),
  statNumber: text(32),
  grossWeightKg: numberField(0, 9_999_999.9),
  volumeM3: numberField(0, 99_999.999),
});

const schema = z
  .object({
    number: z
      .string()
      .trim()
      .max(32, 'validation.too_long')
      .refine((v) => v === '' || NUMBER_RE.test(v), 'validation.invalid_format'),
    orderId: z.string().nullable(),
    tripId: z.string().nullable(),
    sender: party,
    senderContractorId: z.string().nullable(),
    consignee: party,
    consigneeContractorId: z.string().nullable(),
    carrier: party,
    successiveCarriers: z.array(party).max(MAX_SUCCESSIVE),
    deliveryPlace: text(200),
    deliveryCountry: z.string().nullable(),
    loadingPlace: text(200),
    loadingCountry: z.string().nullable(),
    loadingDate: z.string(),
    documentsAttached: text(500),
    lines: z.array(line).max(MAX_LINES),
    senderInstructions: text(1000),
    paymentTerms: z.string(),
    paymentInstructions: text(500),
    codAmount: numberField(0, 999_999_999_999.99),
    codCurrency: z.string().nullable(),
    carrierReservations: text(1000),
    specialAgreements: text(1000),
    establishedPlace: text(100),
    establishedDate: z.string(),
    vehiclePlates: text(64),
    signatures: z.object({ sender: signature, carrier: signature, consignee: signature }),
    notes: text(2000),
  })
  .superRefine((v, ctx) => {
    if (v.codAmount != null && !v.codCurrency) ctx.addIssue({ code: 'custom', path: ['codCurrency'], message: 'validation.required' });
    // A note without a party, a place or an order would only use up a number.
    if (!v.orderId && !v.sender.name.trim() && !v.consignee.name.trim() && !v.loadingPlace.trim() && !v.deliveryPlace.trim()) {
      ctx.addIssue({ code: 'custom', path: ['sender', 'name'], message: 'validation.required' });
    }
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
type PartyForm = FormInput['sender'];
type PartyPath = 'sender' | 'consignee' | 'carrier' | `successiveCarriers.${number}`;

const PARTY_FIELDS = ['name', 'address', 'country', 'vatId'] as const;
const LINE_FIELDS = ['marks', 'packages', 'packing', 'nature', 'statNumber', 'grossWeightKg', 'volumeM3'] as const;
const FIELDS: readonly FieldPath<FormInput>[] = [
  'number', 'orderId', 'tripId', 'senderContractorId', 'consigneeContractorId', 'deliveryPlace', 'deliveryCountry',
  'loadingPlace', 'loadingCountry', 'loadingDate', 'documentsAttached', 'lines', 'successiveCarriers', 'senderInstructions',
  'paymentTerms', 'paymentInstructions', 'codAmount', 'codCurrency', 'carrierReservations', 'specialAgreements',
  'establishedPlace', 'establishedDate', 'vehiclePlates', 'notes',
  ...(['sender', 'consignee', 'carrier'] as const).flatMap((p) => PARTY_FIELDS.map((f) => `${p}.${f}` as const)),
  ...(['sender', 'carrier', 'consignee'] as const).flatMap((s) => (['name', 'place', 'date'] as const).map((f) => `signatures.${s}.${f}` as const)),
];

const emptyParty = (): PartyForm => ({ name: '', address: '', country: null, vatId: '' });
const emptySignature = () => ({ name: '', place: '', date: '' });
const emptyLine = (): FormInput['lines'][number] => ({ marks: '', packages: null, packing: '', nature: '', statNumber: '', grossWeightKg: null, volumeM3: null });

const empty = (): FormInput => ({
  number: '',
  orderId: null,
  tripId: null,
  sender: emptyParty(),
  senderContractorId: null,
  consignee: emptyParty(),
  consigneeContractorId: null,
  carrier: emptyParty(),
  successiveCarriers: [],
  deliveryPlace: '',
  deliveryCountry: null,
  loadingPlace: '',
  loadingCountry: null,
  loadingDate: '',
  documentsAttached: '',
  lines: [emptyLine()],
  senderInstructions: '',
  paymentTerms: '',
  paymentInstructions: '',
  codAmount: null,
  codCurrency: 'EUR',
  carrierReservations: '',
  specialAgreements: '',
  establishedPlace: '',
  establishedDate: '',
  vehiclePlates: '',
  signatures: { sender: emptySignature(), carrier: emptySignature(), consignee: emptySignature() },
  notes: '',
});

const partyForm = (p: CmrParty | null): PartyForm => ({ name: p?.name ?? '', address: p?.address ?? '', country: p?.country ?? null, vatId: p?.vatId ?? '' });
const signatureForm = (s: CmrSignature | null | undefined) => ({ name: s?.name ?? '', place: s?.place ?? '', date: s?.date ?? '' });
const id = (v: number | null | undefined) => (v == null ? null : String(v));

function toFormValues(c: Cmr): FormInput {
  return {
    number: c.number,
    orderId: id(c.order?.id),
    tripId: id(c.trip?.id),
    sender: partyForm(c.sender),
    senderContractorId: id(c.senderContractorId),
    consignee: partyForm(c.consignee),
    consigneeContractorId: id(c.consigneeContractorId),
    carrier: partyForm(c.carrier),
    successiveCarriers: c.successiveCarriers.map(partyForm),
    deliveryPlace: c.deliveryPlace ?? '',
    deliveryCountry: c.deliveryCountry,
    loadingPlace: c.loadingPlace ?? '',
    loadingCountry: c.loadingCountry,
    loadingDate: c.loadingDate ?? '',
    documentsAttached: c.documentsAttached ?? '',
    lines: c.lines.length
      ? c.lines.map((l) => ({
          marks: l.marks ?? '',
          packages: l.packages,
          packing: l.packing ?? '',
          nature: l.nature ?? '',
          statNumber: l.statNumber ?? '',
          grossWeightKg: l.grossWeightKg,
          volumeM3: l.volumeM3,
        }))
      : [emptyLine()],
    senderInstructions: c.senderInstructions ?? '',
    paymentTerms: c.paymentTerms ?? '',
    paymentInstructions: c.paymentInstructions ?? '',
    codAmount: c.cashOnDelivery?.amount ?? null,
    codCurrency: c.cashOnDelivery?.currency ?? 'EUR',
    carrierReservations: c.carrierReservations ?? '',
    specialAgreements: c.specialAgreements ?? '',
    establishedPlace: c.establishedPlace ?? '',
    establishedDate: c.establishedDate ?? '',
    vehiclePlates: c.vehiclePlates ?? '',
    signatures: { sender: signatureForm(c.signatures?.sender), carrier: signatureForm(c.signatures?.carrier), consignee: signatureForm(c.signatures?.consignee) },
    notes: c.notes ?? '',
  };
}

const blank = (s: string) => s.trim() || null;
const toParty = (p: FormValues['sender']): CmrParty => ({ name: blank(p.name), address: blank(p.address), country: p.country, vatId: blank(p.vatId) });
const toSignature = (s: FormValues['signatures']['sender']): CmrSignature => ({ name: blank(s.name), place: blank(s.place), date: s.date || null });
const toId = (v: string | null) => (v ? Number(v) : null);

function toInput(f: FormValues): CmrInput {
  return {
    number: blank(f.number),
    orderId: toId(f.orderId),
    tripId: toId(f.tripId),
    sender: toParty(f.sender),
    senderContractorId: toId(f.senderContractorId),
    consignee: toParty(f.consignee),
    consigneeContractorId: toId(f.consigneeContractorId),
    carrier: toParty(f.carrier),
    successiveCarriers: f.successiveCarriers.map(toParty),
    deliveryPlace: blank(f.deliveryPlace),
    deliveryCountry: f.deliveryCountry,
    loadingPlace: blank(f.loadingPlace),
    loadingCountry: f.loadingCountry,
    loadingDate: f.loadingDate || null,
    documentsAttached: blank(f.documentsAttached),
    lines: f.lines.map((l) => ({
      marks: blank(l.marks),
      packages: l.packages,
      packing: blank(l.packing),
      nature: blank(l.nature),
      statNumber: blank(l.statNumber),
      grossWeightKg: l.grossWeightKg,
      volumeM3: l.volumeM3,
    })),
    senderInstructions: blank(f.senderInstructions),
    paymentTerms: (f.paymentTerms || null) as CmrInput['paymentTerms'],
    paymentInstructions: blank(f.paymentInstructions),
    cashOnDelivery: f.codAmount == null ? null : { amount: f.codAmount, currency: f.codCurrency },
    carrierReservations: blank(f.carrierReservations),
    specialAgreements: blank(f.specialAgreements),
    establishedPlace: blank(f.establishedPlace),
    establishedDate: f.establishedDate || null,
    vehiclePlates: blank(f.vehiclePlates),
    signatures: { sender: toSignature(f.signatures.sender), carrier: toSignature(f.signatures.carrier), consignee: toSignature(f.signatures.consignee) },
    notes: blank(f.notes),
  };
}

interface PartyProps {
  path: PartyPath;
  control: Control<FormInput>;
  register: UseFormRegister<FormInput>;
  errors: FieldErrors<FormInput>;
}

/** Name, address, country and VAT id of one party. */
function PartyFields({ path, control, register, errors }: PartyProps) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const err = (f: string) => {
    const code = get(errors, `${path}.${f}`)?.message as string | undefined;
    return code ? t(code) : undefined;
  };
  return (
    <Stack gap={6}>
      <TextInput size="xs" label={t('contractors.name')} error={err('name')} {...register(`${path}.name`)} />
      <Textarea size="xs" label={t('contractors.address')} autosize minRows={1} maxRows={4} error={err('address')} {...register(`${path}.address`)} />
      <Group grow align="flex-start">
        <Controller
          name={`${path}.country`}
          control={control}
          render={({ field }) => (
            <Select size="xs" label={t('trips.country')} data={countryOptions(lang, field.value)} value={field.value} onChange={field.onChange} searchable clearable error={err('country')} />
          )}
        />
        <TextInput size="xs" label={t('contractors.vat_id')} classNames={{ input: classes.mono }} error={err('vatId')} {...register(`${path}.vatId`)} />
      </Group>
    </Stack>
  );
}

/** Fills a party from a contractor; for the sender and the consignee it also keeps the link. */
function ContractorPick({
  options,
  value,
  onPick,
}: {
  options: { id: number; name: string; address: string | null; country: string; vatId: string | null }[];
  value: string | null;
  onPick: (c: (typeof options)[number] | null) => void;
}) {
  const { t } = useTranslation();
  return (
    <Select
      size="xs"
      placeholder={t('cmr.from_contractor')}
      aria-label={t('cmr.from_contractor')}
      data={options.map((c) => ({ value: String(c.id), label: c.name }))}
      value={value}
      onChange={(v) => onPick(options.find((c) => String(c.id) === v) ?? null)}
      searchable
      clearable
      nothingFoundMessage={t('fleet.nothing_found')}
    />
  );
}

/** Create (/trips/cmr/new) or edit a draft (/trips/cmr/:id/edit) in the right panel: the boxes of the CMR form. */
export function CmrForm() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const params = useParams();
  const isNew = params.id === undefined;
  const cmrId = params.id ?? '';
  const backPath = isNew ? LIST : `${LIST}/${cmrId}`;
  const dec = decimalSeparator(lang);

  const q = useQuery({ queryKey: [...CMR_KEY, cmrId], queryFn: () => cmrApi.get(cmrId), enabled: !isNew });
  const current = q.data;
  const contractors = useQuery({
    queryKey: [...CONTRACTORS_KEY, 'list', '', ''],
    queryFn: () => contractorsApi.list(),
    enabled: hasAnyPrivilege(me, [PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CONTRACTOR_MANAGE]),
  });
  const orders = useQuery({
    queryKey: [...ORDERS_KEY, 'list', { size: 100 }],
    queryFn: () => ordersApi.list({ size: 100 }),
    enabled: hasPrivilege(me, PRIV.TRIP_ORDER_VIEW),
  });
  const trips = useQuery({
    queryKey: [...TRIPS_KEY, 'list', { size: 100 }],
    queryFn: () => tripsApi.list({ size: 100 }),
    enabled: hasPrivilege(me, PRIV.TRIP_TRIP_VIEW),
  });

  const {
    control,
    register,
    handleSubmit,
    setError,
    setValue,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: empty(),
    values: current ? toFormValues(current) : undefined,
  });
  const lines = useFieldArray({ control, name: 'lines' });
  const successive = useFieldArray({ control, name: 'successiveCarriers' });
  const err = (path: string) => {
    const code = get(errors, path)?.message as string | undefined;
    return code ? t(code) : undefined;
  };

  // The order and trip of the note stay selectable even when they are not among the latest 100.
  const orderOptions = (orders.data?.items ?? []).map((o) => ({ value: String(o.id), label: `${o.number} · ${o.customer.name}` }));
  if (current?.order && !orderOptions.some((o) => o.value === String(current.order!.id))) orderOptions.push({ value: String(current.order.id), label: current.order.number });
  const tripOptions = (trips.data?.items ?? []).map((x) => ({ value: String(x.id), label: `${x.number} · ${x.vehicle.plate}` }));
  if (current?.trip && !tripOptions.some((o) => o.value === String(current.trip!.id))) tripOptions.push({ value: String(current.trip.id), label: current.trip.number });
  const contractorList = contractors.data ?? [];
  const carriers = contractorList.filter((c) => c.isCarrier);
  const codCurrency = watch('codCurrency');
  const currencyOptions = codCurrency && !CURRENCIES.includes(codCurrency) ? [...CURRENCIES, codCurrency] : CURRENCIES;

  const fill = (path: 'sender' | 'consignee' | 'carrier', c: { name: string; address: string | null; country: string; vatId: string | null }) =>
    setValue(path, { name: c.name, address: c.address ?? '', country: c.country, vatId: c.vatId ?? '' }, { shouldDirty: true });

  const onSubmit = handleSubmit(async (f) => {
    try {
      const body = toInput(f);
      const saved = isNew ? await cmrApi.create(body) : await cmrApi.update(cmrId, body);
      qc.setQueryData([...CMR_KEY, String(saved.id)], saved);
      void qc.invalidateQueries({ queryKey: [...CMR_KEY, 'list'] });
      notifications.show({ color: 'green', message: t(isNew ? 'cmr.created' : 'cmr.updated', { number: numberInText(saved.number) }) });
      await navigate(`${LIST}/${saved.id}`, { replace: true });
    } catch (e) {
      // Errors come as lines[2].packages / successiveCarriers[0].country / cashOnDelivery.currency.
      const aliases: Record<string, FieldPath<FormInput>> = { 'cashOnDelivery.amount': 'codAmount', 'cashOnDelivery.currency': 'codCurrency' };
      const paths: FieldPath<FormInput>[] = [...FIELDS];
      f.lines.forEach((_, i) =>
        LINE_FIELDS.forEach((field) => {
          aliases[`lines[${i}].${field}`] = `lines.${i}.${field}`;
          paths.push(`lines.${i}.${field}`);
        }),
      );
      f.successiveCarriers.forEach((_, i) =>
        PARTY_FIELDS.forEach((field) => {
          aliases[`successiveCarriers[${i}].${field}`] = `successiveCarriers.${i}.${field}`;
          paths.push(`successiveCarriers.${i}.${field}`);
        }),
      );
      if (!applyServerErrors(e, setError, paths, aliases)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  const country = (name: 'loadingCountry' | 'deliveryCountry') => (
    <Controller
      name={name}
      control={control}
      render={({ field }) => (
        <Select size="xs" label={t('trips.country')} data={countryOptions(lang, field.value)} value={field.value} onChange={field.onChange} searchable clearable error={err(name)} />
      )}
    />
  );
  const title = (box: string | number, key: string) => <h3 className={classes.sectionTitle}>{boxLabel(box, t(key))}</h3>;
  const area = (name: FieldPath<FormInput>, label: string, maxRows = 4) => (
    <Textarea size="xs" label={label} autosize minRows={1} maxRows={maxRows} error={err(name)} {...register(name)} />
  );

  const editable = isNew || current?.status === 'DRAFT';

  return (
    <DetailDrawer
      mode="drawer"
      listPath={backPath}
      title={isNew ? t('cmr.new') : (current?.number ?? '…')}
      subtitle={!isNew && t('common.edit')}
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : !editable ? (
        <Alert color="gray" variant="light">
          {t('cmr.not_draft')}
        </Alert>
      ) : (
        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            <Group grow align="flex-start">
              <TextInput size="xs" label={t('cmr.number')} placeholder={t('cmr.number_auto')} classNames={{ input: classes.mono }} error={err('number')} {...register('number')} />
              <Controller
                name="orderId"
                control={control}
                render={({ field }) => (
                  <Select size="xs" label={t('cmr.order')} data={orderOptions} value={field.value} onChange={field.onChange} searchable clearable error={err('orderId')} />
                )}
              />
              <Controller
                name="tripId"
                control={control}
                render={({ field }) => (
                  <Select size="xs" label={t('orders.trip')} data={tripOptions} value={field.value} onChange={field.onChange} searchable clearable error={err('tripId')} />
                )}
              />
            </Group>

            {title(1, 'cmr.sender')}
            <Controller
              name="senderContractorId"
              control={control}
              render={({ field }) => (
                <ContractorPick
                  options={contractorList}
                  value={field.value}
                  onPick={(c) => {
                    field.onChange(c ? String(c.id) : null);
                    if (c) fill('sender', c);
                  }}
                />
              )}
            />
            <PartyFields path="sender" control={control} register={register} errors={errors} />

            {title(2, 'cmr.consignee')}
            <Controller
              name="consigneeContractorId"
              control={control}
              render={({ field }) => (
                <ContractorPick
                  options={contractorList}
                  value={field.value}
                  onPick={(c) => {
                    field.onChange(c ? String(c.id) : null);
                    if (c) fill('consignee', c);
                  }}
                />
              )}
            />
            <PartyFields path="consignee" control={control} register={register} errors={errors} />

            {title(3, 'cmr.delivery_place')}
            <Group grow align="flex-start">
              <TextInput size="xs" label={t('cmr.place')} error={err('deliveryPlace')} {...register('deliveryPlace')} />
              {country('deliveryCountry')}
            </Group>

            {title(4, 'cmr.loading_place')}
            <Group grow align="flex-start">
              <TextInput size="xs" label={t('cmr.place')} error={err('loadingPlace')} {...register('loadingPlace')} />
              {country('loadingCountry')}
              <FormDateField size="xs" label={t('cmr.loading_date')} error={err('loadingDate')} control={control} name="loadingDate" />
            </Group>

            {title(5, 'cmr.documents_attached')}
            {area('documentsAttached', t('cmr.documents_attached'))}

            <Group justify="space-between">
              {title('6–12', 'cmr.goods')}
              <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => lines.append(emptyLine())} disabled={lines.fields.length >= MAX_LINES}>
                {t('cmr.add_line')}
              </Button>
            </Group>
            {err('lines') && (
              <Alert color="red" variant="light" p="xs">
                {err('lines')}
              </Alert>
            )}
            {lines.fields.map((l, i) => (
              <Paper key={l.id} withBorder p="xs">
                <Stack gap={6}>
                  <Group align="flex-end" gap="xs" wrap="nowrap">
                    <TextInput size="xs" label={t('cmr.line.nature')} style={{ flex: 1 }} error={err(`lines.${i}.nature`)} {...register(`lines.${i}.nature`)} />
                    <ActionIcon variant="subtle" color="red" mb={2} onClick={() => lines.remove(i)} aria-label={t('common.delete')}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Group>
                  <Group grow align="flex-start">
                    <TextInput size="xs" label={t('cmr.line.marks')} error={err(`lines.${i}.marks`)} {...register(`lines.${i}.marks`)} />
                    <Controller
                      name={`lines.${i}.packages`}
                      control={control}
                      render={({ field }) => (
                        <NumberInput
                          size="xs"
                          label={t('cmr.line.packages')}
                          value={field.value ?? ''}
                          onChange={(v) => field.onChange(toNumberInputValue(v))}
                          allowNegative={false}
                          allowDecimal={false}
                          hideControls
                          error={err(`lines.${i}.packages`)}
                        />
                      )}
                    />
                    <TextInput size="xs" label={t('cmr.line.packing')} error={err(`lines.${i}.packing`)} {...register(`lines.${i}.packing`)} />
                  </Group>
                  <Group grow align="flex-start">
                    <TextInput size="xs" label={t('cmr.line.stat_number')} classNames={{ input: classes.mono }} error={err(`lines.${i}.statNumber`)} {...register(`lines.${i}.statNumber`)} />
                    <Controller
                      name={`lines.${i}.grossWeightKg`}
                      control={control}
                      render={({ field }) => (
                        <NumberInput
                          size="xs"
                          label={t('cmr.line.gross_kg')}
                          value={field.value ?? ''}
                          onChange={(v) => field.onChange(toNumberInputValue(v))}
                          decimalScale={1}
                          decimalSeparator={dec}
                          thousandSeparator=" "
                          allowNegative={false}
                          hideControls
                          error={err(`lines.${i}.grossWeightKg`)}
                        />
                      )}
                    />
                    <Controller
                      name={`lines.${i}.volumeM3`}
                      control={control}
                      render={({ field }) => (
                        <NumberInput
                          size="xs"
                          label={t('cmr.line.volume_m3')}
                          value={field.value ?? ''}
                          onChange={(v) => field.onChange(toNumberInputValue(v))}
                          decimalScale={3}
                          decimalSeparator={dec}
                          allowNegative={false}
                          hideControls
                          error={err(`lines.${i}.volumeM3`)}
                        />
                      )}
                    />
                  </Group>
                </Stack>
              </Paper>
            ))}

            {title(13, 'cmr.sender_instructions')}
            {area('senderInstructions', t('cmr.sender_instructions'))}

            {title(14, 'cmr.payment')}
            <Controller
              name="paymentTerms"
              control={control}
              render={({ field }) => (
                <SegmentedControl
                  size="xs"
                  data={[
                    { value: '', label: t('cmr.payment_terms.none') },
                    { value: 'PAID', label: t('cmr.payment_terms.PAID') },
                    { value: 'FORWARD', label: t('cmr.payment_terms.FORWARD') },
                  ]}
                  value={field.value}
                  onChange={field.onChange}
                />
              )}
            />
            {area('paymentInstructions', t('cmr.payment_instructions'))}

            {title(15, 'cmr.cash_on_delivery')}
            <Group grow align="flex-start">
              <Controller
                name="codAmount"
                control={control}
                render={({ field }) => (
                  <NumberInput
                    size="xs"
                    label={t('cmr.amount')}
                    value={field.value ?? ''}
                    onChange={(v) => field.onChange(toNumberInputValue(v))}
                    decimalScale={2}
                    decimalSeparator={dec}
                    thousandSeparator=" "
                    allowNegative={false}
                    hideControls
                    error={err('codAmount')}
                  />
                )}
              />
              <Controller
                name="codCurrency"
                control={control}
                render={({ field }) => (
                  <Select size="xs" label={t('orders.currency')} data={currencyOptions} value={field.value} onChange={field.onChange} searchable error={err('codCurrency')} />
                )}
              />
            </Group>

            {title(16, 'cmr.carrier')}
            <ContractorPick options={carriers} value={null} onPick={(c) => c && fill('carrier', c)} />
            <PartyFields path="carrier" control={control} register={register} errors={errors} />
            <TextInput size="xs" label={t('cmr.vehicle_plates')} classNames={{ input: classes.mono }} error={err('vehiclePlates')} {...register('vehiclePlates')} />

            <Group justify="space-between">
              {title(17, 'cmr.successive_carriers')}
              <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => successive.append(emptyParty())} disabled={successive.fields.length >= MAX_SUCCESSIVE}>
                {t('cmr.add_carrier')}
              </Button>
            </Group>
            {successive.fields.map((p, i) => (
              <Paper key={p.id} withBorder p="xs">
                <Group align="flex-start" gap="xs" wrap="nowrap">
                  <div style={{ flex: 1 }}>
                    <PartyFields path={`successiveCarriers.${i}`} control={control} register={register} errors={errors} />
                  </div>
                  <ActionIcon variant="subtle" color="red" mt={22} onClick={() => successive.remove(i)} aria-label={t('common.delete')}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              </Paper>
            ))}

            {title(18, 'cmr.carrier_reservations')}
            {area('carrierReservations', t('cmr.carrier_reservations'))}
            {title(19, 'cmr.special_agreements')}
            {area('specialAgreements', t('cmr.special_agreements'))}

            {title(21, 'cmr.established')}
            <Group grow align="flex-start">
              <TextInput size="xs" label={t('cmr.place')} error={err('establishedPlace')} {...register('establishedPlace')} />
              <FormDateField size="xs" label={t('cmr.date')} error={err('establishedDate')} control={control} name="establishedDate" />
            </Group>

            <h3 className={classes.sectionTitle}>{boxLabel('22–24', t('cmr.section.signatures'))}</h3>
            {(['sender', 'carrier', 'consignee'] as const).map((s, i) => (
              <Group key={s} grow align="flex-start">
                <TextInput size="xs" label={boxLabel(22 + i, t(`cmr.${s}`))} error={err(`signatures.${s}.name`)} {...register(`signatures.${s}.name`)} />
                <TextInput size="xs" label={t('cmr.place')} error={err(`signatures.${s}.place`)} {...register(`signatures.${s}.place`)} />
                <FormDateField size="xs" label={t('cmr.date')} error={err(`signatures.${s}.date`)} control={control} name={`signatures.${s}.date`} />
              </Group>
            ))}

            <Textarea size="xs" label={t('drivers.notes')} description={t('cmr.notes_hint')} autosize minRows={2} maxRows={6} error={err('notes')} {...register('notes')} />
            <Group justify="flex-end" gap="xs">
              <Button variant="default" onClick={() => void navigate(backPath)} disabled={isSubmitting}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={isSubmitting} disabled={!isNew && !isDirty}>
                {isNew ? t('common.create') : t('common.save')}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </DetailDrawer>
  );
}
