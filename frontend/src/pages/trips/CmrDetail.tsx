import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, List, SegmentedControl, Stack, Table, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBan, IconDownload, IconFileTypePdf, IconPencil, IconRubberStamp, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { CMR_KEY, cmrApi, type Cmr, type CmrParty, type CmrSignature } from '@/api/cmr';
import { ORDERS_KEY, TRIPS_KEY } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { countryName } from '@/utils/countries';
import { formatDate } from '@/utils/deadline';
import { boxLabel, numberInText, showCmrPdf } from './cmrFormat';
import { CmrStatusChip } from './TripChips';
import { formatInstantShort, formatMoney, placeLine } from './tripFormat';
import classes from './Trips.module.css';

const LIST = '/trips/cmr';
const DASH = '—';

/** Field of the issue check (422 details) -> the box it names. */
const ISSUE_FIELDS: Record<string, string> = {
  'sender.name': 'cmr.sender',
  'consignee.name': 'cmr.consignee',
  'carrier.name': 'cmr.carrier',
  loadingPlace: 'cmr.loading_place',
  deliveryPlace: 'cmr.delivery_place',
  lines: 'cmr.goods',
};

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      {children}
    </section>
  );
}

function PartyView({ p }: { p: CmrParty | null }) {
  const { i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  if (!p) return <>{DASH}</>;
  return (
    <div>
      {p.name && <Text size="sm" fw={500}>{p.name}</Text>}
      {p.address && (
        <Text size="sm" className={classes.notes}>
          {p.address}
        </Text>
      )}
      {(p.country || p.vatId) && (
        <Text size="sm" c="dimmed">
          {[p.country && countryName(p.country, lang), p.vatId].filter(Boolean).join(' · ')}
        </Text>
      )}
    </div>
  );
}

function Overview({ c }: { c: Cmr }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const tz = me.firma?.timezone;
  const date = (d: string | null) => (d ? formatDate(d, lang) : DASH);
  const text = (s: string | null) => (s ? <span className={classes.notes}>{s}</span> : DASH);
  const num = (v: number | null, digits: number) => (v == null ? '' : new Intl.NumberFormat(lang, { maximumFractionDigits: digits }).format(v));

  const links: KeyValueItem[] = [
    {
      label: t('cmr.order'),
      value: c.order ? (
        hasPrivilege(me, PRIV.TRIP_ORDER_VIEW) ? (
          <Anchor component={Link} to={`/trips/orders/${c.order.id}`} className={classes.mono}>
            {c.order.number}
          </Anchor>
        ) : (
          <span className={classes.mono}>{c.order.number}</span>
        )
      ) : (
        DASH
      ),
    },
    {
      label: t('orders.trip'),
      value: c.trip ? (
        hasPrivilege(me, PRIV.TRIP_TRIP_VIEW) ? (
          <Anchor component={Link} to={`/trips/${c.trip.id}`} className={classes.mono}>
            {c.trip.number}
          </Anchor>
        ) : (
          <span className={classes.mono}>{c.trip.number}</span>
        )
      ) : (
        DASH
      ),
    },
    ...(c.issuedAt ? [{ label: t('cmr.issued_at'), value: formatInstantShort(c.issuedAt, lang, tz) }] : []),
  ];
  const parties: KeyValueItem[] = [
    { label: boxLabel(1, t('cmr.sender')), value: <PartyView p={c.sender} /> },
    { label: boxLabel(2, t('cmr.consignee')), value: <PartyView p={c.consignee} /> },
    { label: boxLabel(16, t('cmr.carrier')), value: <PartyView p={c.carrier} /> },
    ...c.successiveCarriers.map((p, i) => ({ label: boxLabel(17, `${t('cmr.successive_carrier')} ${i + 1}`), value: <PartyView p={p} /> })),
  ];
  const places: KeyValueItem[] = [
    { label: boxLabel(3, t('cmr.delivery_place')), value: placeLine(c.deliveryPlace, c.deliveryCountry && countryName(c.deliveryCountry, lang)) },
    { label: boxLabel(4, t('cmr.loading_place')), value: placeLine(c.loadingPlace, c.loadingCountry && countryName(c.loadingCountry, lang)) },
    { label: boxLabel(4, t('cmr.loading_date')), value: date(c.loadingDate) },
    { label: boxLabel(5, t('cmr.documents_attached')), value: text(c.documentsAttached) },
    { label: boxLabel(21, t('cmr.established')), value: [c.establishedPlace, c.establishedDate && formatDate(c.establishedDate, lang)].filter(Boolean).join(', ') || DASH },
    { label: t('cmr.vehicle_plates'), value: c.vehiclePlates ? <span className={classes.mono}>{c.vehiclePlates}</span> : DASH },
  ];
  const terms: KeyValueItem[] = [
    { label: boxLabel(13, t('cmr.sender_instructions')), value: text(c.senderInstructions) },
    {
      label: boxLabel(14, t('cmr.payment')),
      value: [c.paymentTerms && t(`cmr.payment_terms.${c.paymentTerms}`), c.paymentInstructions].filter(Boolean).join(' · ') || DASH,
    },
    { label: boxLabel(15, t('cmr.cash_on_delivery')), value: c.cashOnDelivery ? formatMoney(c.cashOnDelivery.amount, c.cashOnDelivery.currency, lang) : DASH },
    { label: boxLabel(18, t('cmr.carrier_reservations')), value: text(c.carrierReservations) },
    { label: boxLabel(19, t('cmr.special_agreements')), value: text(c.specialAgreements) },
  ];
  const sig = (s: CmrSignature | null | undefined) => (s ? [s.name, s.place, s.date && formatDate(s.date, lang)].filter(Boolean).join(', ') : '');
  const signatures: KeyValueItem[] = [
    { label: boxLabel(22, t('cmr.sender')), value: sig(c.signatures?.sender) || DASH },
    { label: boxLabel(23, t('cmr.carrier')), value: sig(c.signatures?.carrier) || DASH },
    { label: boxLabel(24, t('cmr.consignee')), value: sig(c.signatures?.consignee) || DASH },
  ];
  const total = (pick: (l: Cmr['lines'][number]) => number | null) =>
    c.lines.some((l) => pick(l) != null) ? c.lines.reduce((s, l) => s + (pick(l) ?? 0), 0) : null;

  return (
    <Stack gap="lg">
      <KeyValueList items={links} />
      <Section title={t('cmr.section.parties')}>
        <KeyValueList items={parties} />
      </Section>
      <Section title={t('cmr.section.places')}>
        <KeyValueList items={places} />
      </Section>
      <Section title={`6–12 · ${t('cmr.goods')}`}>
        {c.lines.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('cmr.no_goods')}
          </Text>
        ) : (
          <Table fz="sm" withRowBorders>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('cmr.line.marks')}</Table.Th>
                <Table.Th className={classes.num}>{t('cmr.line.packages')}</Table.Th>
                <Table.Th>{t('cmr.line.nature')}</Table.Th>
                <Table.Th className={classes.num}>{t('cmr.line.gross_kg')}</Table.Th>
                <Table.Th className={classes.num}>{t('cmr.line.volume_m3')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {c.lines.map((l, i) => (
                <Table.Tr key={i}>
                  <Table.Td>{l.marks ?? ''}</Table.Td>
                  <Table.Td className={classes.num}>{[num(l.packages, 0), l.packing].filter(Boolean).join(' ')}</Table.Td>
                  <Table.Td>
                    {l.nature ?? ''}
                    {l.statNumber && (
                      <Text span size="xs" c="dimmed" className={classes.mono}>
                        {' '}
                        {l.statNumber}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td className={classes.num}>{num(l.grossWeightKg, 1)}</Table.Td>
                  <Table.Td className={classes.num}>{num(l.volumeM3, 3)}</Table.Td>
                </Table.Tr>
              ))}
              {c.lines.length > 1 && (
                <Table.Tr>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {t('calc.total')}
                    </Text>
                  </Table.Td>
                  <Table.Td className={classes.num}>{num(total((l) => l.packages), 0)}</Table.Td>
                  <Table.Td />
                  <Table.Td className={classes.num}>{num(total((l) => l.grossWeightKg), 1)}</Table.Td>
                  <Table.Td className={classes.num}>{num(total((l) => l.volumeM3), 3)}</Table.Td>
                </Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        )}
      </Section>
      <Section title={t('cmr.section.terms')}>
        <KeyValueList items={terms} />
      </Section>
      <Section title={t('cmr.section.signatures')}>
        <KeyValueList items={signatures} />
      </Section>
      {c.notes && (
        <Section title={t('drivers.notes')}>
          <Text size="sm" className={classes.notes}>
            {c.notes}
          </Text>
        </Section>
      )}
    </Stack>
  );
}

/** CMR card: the boxes of the note, PDF, issue / cancel; the same component renders in the right panel and as a page. */
export function CmrDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...CMR_KEY, id], queryFn: () => cmrApi.get(id), enabled: id !== '' });
  const c = q.data;
  const canManage = hasPrivilege(me, PRIV.TRIP_CMR_MANAGE);
  const [confirm, setConfirm] = useState<'delete' | 'cancel' | null>(null);
  const [missing, setMissing] = useState<string[]>([]);
  const [copies, setCopies] = useState('1');
  const [busy, setBusy] = useState(false);
  // The drawer stays mounted when another row is clicked: close the panels of the previous note.
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setConfirm(null);
    setMissing([]);
  }

  const refresh = (saved: Cmr) => {
    qc.setQueryData([...CMR_KEY, id], saved);
    void qc.invalidateQueries({ queryKey: [...CMR_KEY, 'list'] });
    void qc.invalidateQueries({ queryKey: ORDERS_KEY });
    void qc.invalidateQueries({ queryKey: TRIPS_KEY });
  };

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const issue = () =>
    run(async () => {
      setMissing([]);
      try {
        const saved = await cmrApi.issue(id);
        refresh(saved);
        notifications.show({ color: 'green', message: t('cmr.issued', { number: numberInText(saved.number) }) });
      } catch (e) {
        if (e instanceof ApiError && e.status === 422 && e.details) setMissing(Object.keys(e.details));
        else throw e;
      }
    });

  const cancel = () =>
    run(async () => {
      const saved = await cmrApi.cancel(id);
      refresh(saved);
      setConfirm(null);
      notifications.show({ color: 'green', message: t('cmr.cancelled', { number: numberInText(saved.number) }) });
    });

  const remove = () =>
    run(async () => {
      try {
        await cmrApi.remove(id);
      } catch (e) {
        setConfirm(null);
        throw e;
      }
      notifications.show({ color: 'green', message: t('cmr.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: CMR_KEY, predicate: (x) => x.queryKey[CMR_KEY.length] !== id });
    });

  const pdf = (save: boolean) => c && run(() => showCmrPdf(c, Number(copies), save));

  const draft = c?.status === 'DRAFT';
  const actions = c ? (
    <>
      {canManage && draft && (
        <Tooltip label={t('hint.action.cmr_issue')}>
          <Button size="xs" leftSection={<IconRubberStamp size={14} />} onClick={() => void issue()} disabled={busy}>
            {t('cmr.issue')}
          </Button>
        </Tooltip>
      )}
      <Button variant="default" size="xs" leftSection={<IconFileTypePdf size={14} />} onClick={() => void pdf(false)} disabled={busy}>
        {t('cmr.pdf')}
      </Button>
      <Button variant="default" size="xs" leftSection={<IconDownload size={14} />} onClick={() => void pdf(true)} disabled={busy} aria-label={t('cmr.download')}>
        {t('cmr.download')}
      </Button>
      {draft && (
        <Group gap={6} wrap="nowrap">
          <Text size="xs" c="dimmed">
            {t('cmr.copies')}
          </Text>
          <SegmentedControl size="xs" data={['1', '2', '3', '4']} value={copies} onChange={setCopies} aria-label={t('cmr.copies')} />
        </Group>
      )}
      {canManage && draft && (
        <>
          <Button variant="default" size="xs" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)} disabled={busy}>
            {t('common.edit')}
          </Button>
          <Button variant="default" size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirm('delete')} disabled={busy}>
            {t('common.delete')}
          </Button>
        </>
      )}
      {canManage && c.status === 'ISSUED' && (
        <Tooltip label={t('hint.action.cmr_cancel')}>
          <Button variant="default" size="xs" color="red" leftSection={<IconBan size={14} />} onClick={() => setConfirm('cancel')} disabled={busy}>
            {t('cmr.cancel')}
          </Button>
        </Tooltip>
      )}
    </>
  ) : undefined;

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={`${LIST}/${id}/page`}
      title={c ? <span className={classes.mono}>{c.number}</span> : '…'}
      subtitle={c && [c.sender?.name, c.consignee?.name ?? c.deliveryPlace].filter(Boolean).join(' → ')}
      status={c && <CmrStatusChip status={c.status} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : c ? (
        <Stack gap="md">
          {confirm && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t(confirm === 'delete' ? 'cmr.delete_confirm' : 'cmr.cancel_confirm', { number: confirm === 'delete' ? c.number : numberInText(c.number) })}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void (confirm === 'delete' ? remove() : cancel())} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirm(null)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          {missing.length > 0 && (
            <Alert color="orange" variant="light" p="sm" title={t('cmr.issue_missing')}>
              <List size="sm">
                {missing.map((f) => (
                  <List.Item key={f}>{t(ISSUE_FIELDS[f] ?? 'cmr.other_box')}</List.Item>
                ))}
              </List>
              {canManage && (
                <Button size="xs" variant="light" mt="xs" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)}>
                  {t('common.edit')}
                </Button>
              )}
            </Alert>
          )}
          {c.status === 'CANCELLED' && (
            <Alert color="gray" variant="light" p="xs">
              {t('cmr.cancelled_hint')}
            </Alert>
          )}
          <Overview c={c} />
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
