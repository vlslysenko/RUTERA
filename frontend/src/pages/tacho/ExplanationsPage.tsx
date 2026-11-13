import { useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Modal, NumberInput, Pagination, Paper, Select, Stack, Table, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBellRinging, IconDownload, IconPlus, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError, saveBlob } from '@/api/client';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { errorCode } from '@/api/errors';
import { EXPLANATION_STATUSES, EXPLANATIONS_KEY, explanationsApi, type Explanation, type ExplanationFilter, type ExplanationStatus } from '@/api/explanations';
import { VIOLATION_RULES, VIOLATIONS_KEY, type ViolationRule } from '@/api/hos';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { KeyValueList } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { DateField } from '@/forms/DateField';
import { languageName } from '@/i18n/languages';
import { useCrewOptions } from '@/pages/trips/crew';
import { fromZonedInput } from '@/utils/zone';
import { ExplanationStatusChip } from './ExplanationChips';
import { formatInstant } from './format';
import { formatMinutes } from './hosFormat';
import classes from './Hos.module.css';

const PAGE_SIZE = 50;

/** The office asks a driver for an explanation by hand (one not found in the tachograph data, or older). */
function NewExplanation({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const tz = useUser().firma?.timezone;
  const navigate = useNavigate();
  const qc = useQueryClient();
  const crew = useCrewOptions();
  const [driverId, setDriverId] = useState<string | null>(null);
  const [vehicleId, setVehicleId] = useState<string | null>(null);
  const [rule, setRule] = useState<string | null>(null);
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [exceeded, setExceeded] = useState<number | string>('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const err = (k: string) => (errors[k] ? t(errors[k]) : undefined);

  const save = async () => {
    const local: Record<string, string> = {};
    if (!driverId) local.driverId = 'validation.required';
    if (!rule) local.ruleCode = 'validation.required';
    if (!start) local.startedAt = 'validation.required';
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      const e = await explanationsApi.create({
        driverId: Number(driverId),
        vehicleId: vehicleId ? Number(vehicleId) : null,
        ruleCode: rule as ViolationRule,
        startedAt: fromZonedInput(start, tz) ?? start,
        endedAt: end ? fromZonedInput(end, tz) : null,
        exceededMin: exceeded === '' ? null : Number(exceeded),
      });
      void qc.invalidateQueries({ queryKey: EXPLANATIONS_KEY });
      notifications.show({ color: 'green', message: t('explanation.requested') });
      onClose();
      await navigate(`/tacho/explanations/${e.id}`);
    } catch (x) {
      if (x instanceof ApiError && x.details) setErrors(x.details);
      else notifications.show({ color: 'red', message: t(errorCode(x)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="sm">
      <Select label={t('fleet.driver')} data={crew.drivers} value={driverId} onChange={setDriverId} searchable error={err('driverId')} />
      <Select label={t('trips.vehicle')} data={crew.vehicles} value={vehicleId} onChange={setVehicleId} searchable clearable error={err('vehicleId')} />
      <Select label={t('hos.v.col.rule')} data={VIOLATION_RULES.map((r) => ({ value: r, label: t(`hos.rule.${r}`) }))} value={rule} onChange={setRule} error={err('ruleCode')} />
      <Group grow align="flex-start">
        <DateField withTime label={t('explanation.from')} value={start} onChange={setStart} error={err('startedAt')} />
        <DateField withTime label={t('explanation.to')} value={end} onChange={setEnd} error={err('endedAt')} />
      </Group>
      <NumberInput label={t('explanation.exceeded_min')} value={exceeded} onChange={setExceeded} min={0} max={10000} allowDecimal={false} allowNegative={false} hideControls error={err('exceededMin')} />
      <Text size="xs" c="dimmed">
        {t('explanation.request_hint')}
      </Text>
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={busy}>
          {t('common.cancel')}
        </Button>
        <Button onClick={() => void save()} loading={busy}>
          {t('explanation.request')}
        </Button>
      </Group>
    </Stack>
  );
}

/**
 * Tachograph -> Explanations: what drivers were asked to explain, signed or still waiting; a row opens it in the right
 * panel. Filters in the URL (?driver=&status=&from=&to=).
 */
export function ExplanationsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const tz = me.firma?.timezone;
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list, enabled: hasPrivilege(me, PRIV.DRIVER_VIEW) });
  const canManage = hasPrivilege(me, PRIV.TACHO_VIOLATION_ACK);

  const page = Number(params.get('page') ?? '1');
  const filter: ExplanationFilter = {
    driverId: params.get('driver') ? Number(params.get('driver')) : undefined,
    status: (params.get('status') as ExplanationStatus | null) ?? undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const q = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'list', filter], queryFn: () => explanationsApi.list(filter), placeholderData: keepPreviousData });
  const set = (key: string, value: string | null) =>
    setParams((p) => {
      if (value) p.set(key, value);
      else p.delete(key);
      p.delete('page');
      return p;
    });
  const open = (e: Explanation) => void navigate({ pathname: `/tacho/explanations/${e.id}`, search: params.toString() });
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-end" wrap="wrap">
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
              placeholder={t('explanation.col.status')}
              aria-label={t('explanation.col.status')}
              data={EXPLANATION_STATUSES.map((s) => ({ value: s, label: t(`explanation.status.${s}`) }))}
              value={params.get('status')}
              onChange={(v) => set('status', v)}
              clearable
              w={180}
            />
            <DateField aria-label={t('tacho.filter.from')} hint={t('hint.filter.violations')} value={params.get('from') ?? ''} onChange={(v) => set('from', v || null)} w={150} />
            <DateField aria-label={t('tacho.filter.to')} hint={t('hint.filter.violations')} value={params.get('to') ?? ''} onChange={(v) => set('to', v || null)} w={150} />
            {q.data && (
              <Text c="dimmed" mb={6}>
                {q.data.total}
              </Text>
            )}
          </div>
          {canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
              {t('explanation.request')}
            </Button>
          )}
        </Group>
        <Text size="xs" c="dimmed">
          {t('explanation.office_hint')}
        </Text>

        {q.isPending ? (
          <FullScreenLoader fullScreen={false} />
        ) : q.isError ? (
          q.error instanceof ApiError && q.error.status === 404 ? (
            <EmptyState text={t('common.in_development')} />
          ) : (
            <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
          )
        ) : q.data.items.length === 0 ? (
          <EmptyState text={t('explanation.empty')} />
        ) : (
          <>
            <Paper className={classes.tableWrap}>
              <Table className={classes.table} highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t('hos.v.col.when')}</Table.Th>
                    <Table.Th>{t('fleet.driver')}</Table.Th>
                    <Table.Th>{t('hos.v.col.rule')}</Table.Th>
                    <Table.Th ta="right">{t('explanation.col.exceeded')}</Table.Th>
                    <Table.Th>{t('explanation.col.status')}</Table.Th>
                    <Table.Th>{t('explanation.col.signed')}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {q.data.items.map((e) => (
                    <Table.Tr key={e.id} onClick={() => open(e)} style={{ cursor: 'pointer' }} data-active={String(e.id) === id || undefined}>
                      <Table.Td className={classes.mono}>{formatInstant(e.startedAt, lang, tz)}</Table.Td>
                      <Table.Td>
                        <Anchor component={Link} to={{ pathname: `/tacho/explanations/${e.id}`, search: params.toString() }} onClick={(x) => x.stopPropagation()}>
                          {e.driver.name}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{t(`hos.rule.${e.ruleCode}`)}</Table.Td>
                      <Table.Td ta="right" className={classes.mono}>
                        {e.exceededMin != null ? formatMinutes(t, e.exceededMin) : '—'}
                      </Table.Td>
                      <Table.Td>
                        <ExplanationStatusChip status={e.status} />
                      </Table.Td>
                      <Table.Td className={classes.mono}>{e.signedAt ? formatInstant(e.signedAt, lang, tz) : '—'}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Paper>
            {pages > 1 && <Pagination total={pages} value={page} onChange={(p) => setParams((x) => (x.set('page', String(p)), x))} />}
          </>
        )}
      </Stack>
      <Modal opened={creating} onClose={() => setCreating(false)} title={t('explanation.request')} size="lg">
        {creating && <NewExplanation onClose={() => setCreating(false)} />}
      </Modal>
      <Outlet />
    </>
  );
}

/** One explanation: what it is about, the driver's reason and signature time, the PDF, and withdrawing a pending one. */
export function ExplanationDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const tz = me.firma?.timezone;
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { search } = useLocation();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'one', id], queryFn: () => explanationsApi.get(id), enabled: id !== '' });
  const e = q.data;
  const canManage = hasPrivilege(me, PRIV.TACHO_VIOLATION_ACK);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);

  const pdf = async () => {
    try {
      const { blob, fileName } = await explanationsApi.pdf(id);
      saveBlob(blob, fileName ?? `explanation-${id}.pdf`);
    } catch (x) {
      notifications.show({ color: 'red', message: t(errorCode(x)) });
    }
  };
  const remind = async () => {
    setBusy(true);
    try {
      await explanationsApi.remind(id);
      notifications.show({ color: 'green', message: t('explanation.reminded') });
    } catch (x) {
      notifications.show({ color: 'red', message: t(errorCode(x)) });
    } finally {
      setBusy(false);
    }
  };
  const cancel = async () => {
    setBusy(true);
    try {
      await explanationsApi.cancel(id);
      void qc.invalidateQueries({ queryKey: EXPLANATIONS_KEY });
      void qc.invalidateQueries({ queryKey: VIOLATIONS_KEY });
      notifications.show({ color: 'green', message: t('explanation.cancelled') });
      await navigate(`/tacho/explanations${search}`);
    } catch (x) {
      notifications.show({ color: 'red', message: t(errorCode(x)) });
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  };

  return (
    <DetailDrawer
      mode={mode}
      listPath={`/tacho/explanations${search}`}
      pagePath={`/tacho/explanations/${id}/page${search}`}
      title={e ? t(`hos.rule.${e.ruleCode}`) : '…'}
      subtitle={e && `${e.driver.name} · ${formatInstant(e.startedAt, lang, tz)}`}
      status={e && <ExplanationStatusChip status={e.status} />}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : e ? (
        <Stack gap="lg">
          <KeyValueList
            items={[
              { label: t('hos.v.period'), value: `${formatInstant(e.startedAt, lang, tz)}${e.endedAt ? ` – ${formatInstant(e.endedAt, lang, tz)}` : ''}` },
              { label: t('explanation.col.exceeded'), value: e.exceededMin != null ? <span className={classes.mono}>{formatMinutes(t, e.exceededMin)}</span> : '—' },
              { label: t('fleet.driver'), value: <Anchor component={Link} to={`/drivers/${e.driver.id}`}>{e.driver.name}</Anchor> },
              { label: t('drivers.vehicle'), value: e.vehicle ? <Plate>{e.vehicle.plate}</Plate> : '—' },
              { label: t('explanation.reason'), value: e.reason ? t(`explanation.reason.${e.reason}`) : '—' },
              ...(e.reasonText ? [{ label: t('explanation.text'), value: e.reasonText }] : []),
              ...(e.place ? [{ label: t('explanation.place'), value: e.place }] : []),
              { label: t('explanation.col.signed'), value: e.signedAt ? formatInstant(e.signedAt, lang, tz) : '—' },
              { label: t('explanation.doc_lang_label'), value: languageName(e.docLang) },
              { label: t('explanation.asked_by'), value: e.createdBy ? e.createdBy.name : t(`explanation.source.${e.source}`) },
              ...(e.cancelledAt ? [{ label: t('explanation.cancelled_at'), value: formatInstant(e.cancelledAt, lang, tz) }] : []),
            ]}
          />
          {e.violationId != null && (
            <Anchor component={Link} to={`/tacho/violations/${e.violationId}`} size="sm">
              {t('explanation.open_violation')}
            </Anchor>
          )}
          <Group gap="xs">
            {e.status !== 'CANCELLED' && (
              <Button size="xs" variant="default" leftSection={<IconDownload size={14} />} onClick={() => void pdf()}>
                {t(e.status === 'SIGNED' ? 'explanation.pdf' : 'explanation.pdf_preview')}
              </Button>
            )}
            {canManage && e.status === 'PENDING' && (
              <Tooltip label={t('hint.action.explanation_remind')}>
                <Button size="xs" variant="default" leftSection={<IconBellRinging size={14} />} onClick={() => void remind()} loading={busy && !confirm}>
                  {t('explanation.remind')}
                </Button>
              </Tooltip>
            )}
            {canManage && e.status === 'PENDING' && !confirm && (
              <Button size="xs" variant="subtle" color="red" leftSection={<IconX size={14} />} onClick={() => setConfirm(true)}>
                {t('explanation.cancel')}
              </Button>
            )}
          </Group>
          {confirm && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('explanation.cancel_confirm', { name: e.driver.name })}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void cancel()} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirm(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
