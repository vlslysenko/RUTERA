import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Group, Paper, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { TimeInput } from '@mantine/dates';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconArrowLeft, IconChevronRight, IconDownload, IconSignature } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError, saveBlob } from '@/api/client';
import { errorCode } from '@/api/errors';
import { art12Choices, EXPLANATION_REASONS, EXPLANATIONS_KEY, explanationsApi, type Art12Claim, type Explanation, type ExplanationReason, type ReasonOption } from '@/api/explanations';
import { useUser } from '@/auth/useAuth';
import { SignaturePad } from '@/components/SignaturePad';
import { formatMinutes } from '@/pages/tacho/hosFormat';
import { ExplanationStatusChip } from '@/pages/tacho/ExplanationChips';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { languageName } from '@/i18n/languages';
import { cx } from '@/utils/cx';
import { Card, PartError, Waiting } from './DriverParts';
import classes from './DriverApp.module.css';

/** What the explanation is about: the rule, when, by how much, the vehicle. */
export function ExplanationFacts({ e }: { e: Explanation }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const tz = useUser().firma?.timezone;
  return (
    <Stack gap={4}>
      <Text fw={600}>{t(`hos.rule.${e.ruleCode}`)}</Text>
      <Text size="sm">
        {formatInstantShort(e.startedAt, lang, tz)}
        {e.endedAt ? ` – ${formatInstantShort(e.endedAt, lang, tz)}` : ''}
      </Text>
      {e.exceededMin != null && e.exceededMin > 0 && (
        <Text size="sm" c="red" fw={600}>
          {t('explanation.exceeded_by', { time: formatMinutes(t, e.exceededMin) })}
        </Text>
      )}
      {e.vehicle && (
        <Text size="sm" c="dimmed">
          {e.vehicle.plate}
        </Text>
      )}
    </Stack>
  );
}

async function downloadPdf(e: Explanation) {
  const { blob, fileName } = await explanationsApi.pdf(e.id);
  saveBlob(blob, fileName ?? `explanation-${e.id}.pdf`);
}

/** Cabinet: the driver's explanations, those waiting for a signature first. */
export function MyExplanations() {
  const { t } = useTranslation();
  const driverId = useUser().driver!.id;
  const filter = { driverId, size: 50 };
  const q = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'list', filter], queryFn: () => explanationsApi.list(filter), retry: false });
  const items = [...(q.data?.items ?? [])].sort((a, b) => Number(b.status === 'PENDING') - Number(a.status === 'PENDING'));

  return (
    <Stack gap="sm">
      <Card title={t('explanation.my_title')}>
        {q.isPending ? (
          <Waiting query={q} />
        ) : q.isError && q.data === undefined ? (
          <PartError error={q.error} />
        ) : items.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('explanation.none')}
          </Text>
        ) : (
          <Stack gap={0}>
            {items.map((e) => (
              <Link key={e.id} to={`/driver/explanations/${e.id}`} className={cx(classes.row, classes.rowLink)}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <ExplanationFacts e={e} />
                </div>
                <Group gap={4} wrap="nowrap">
                  <ExplanationStatusChip status={e.status} />
                  <IconChevronRight size={18} />
                </Group>
              </Link>
            ))}
          </Stack>
        )}
      </Card>
    </Stack>
  );
}

/**
 * The form of a pending explanation: the reason (from the reasons the infringement allows), the description, the place,
 * an Article 12 claim where the infringement and the reason allow one (with the note to copy onto the printout), and the
 * finger signature.
 */
function SignForm({ e }: { e: Explanation }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const reasons = useQuery({
    queryKey: [...EXPLANATIONS_KEY, 'reasons', e.ruleCode],
    queryFn: () => explanationsApi.reasons(e.ruleCode),
    staleTime: Infinity,
    retry: false,
  });
  const [reason, setReason] = useState<ExplanationReason | null>(null);
  const [reasonText, setReasonText] = useState('');
  const [place, setPlace] = useState('');
  const [art12, setArt12] = useState<Art12Claim>('NONE');
  const [art12Place, setArt12Place] = useState('');
  const [breakFrom, setBreakFrom] = useState('');
  const [breakTo, setBreakTo] = useState('');
  const [printoutNote, setPrintoutNote] = useState(false);
  const [signature, setSignature] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const err = (k: string) => (errors[k] ? t(errors[k]) : undefined);

  // Without the reasons endpoint every reason is offered, with no Art. 12 claim.
  const options: ReasonOption[] = reasons.data ?? EXPLANATION_REASONS.map((code) => ({ code, art12Safety: false, art12Home: false, textRequired: code === 'OTHER' }));
  const option = options.find((o) => o.code === reason);
  const choices = art12Choices(e, option);
  const claim = choices.includes(art12) ? art12 : 'NONE';
  const anyArt12 = Boolean(e.art12Allowed?.safety || e.art12Allowed?.home);

  // The note for the printout follows what is filled in; the place is typed, so it waits for a pause.
  const [notePlace] = useDebouncedValue(art12Place.trim(), 500);
  const note = useQuery({
    queryKey: [...EXPLANATIONS_KEY, 'printout-note', e.id, reason, claim, notePlace, breakFrom, breakTo],
    queryFn: () => explanationsApi.printoutNote(e.id, { reason: reason ?? undefined, art12: claim, place: notePlace || undefined, breakFrom: breakFrom || undefined, breakTo: breakTo || undefined }),
    enabled: claim !== 'NONE' && reason != null,
    retry: false,
  });

  const chooseReason = (r: ExplanationReason) => {
    setReason(r);
    setPrintoutNote(false);
  };

  const sign = async () => {
    const local: Record<string, string> = {};
    if (!reason) local.reason = 'validation.required';
    if (option?.textRequired && !reasonText.trim()) local.reasonText = 'validation.required';
    if (claim !== 'NONE') {
      if (!art12Place.trim()) local.art12Place = 'validation.required';
      if (!printoutNote) local.printoutNote = 'explanation.art12.note_required';
      if (claim === 'HOME_2H' && !breakFrom) local.art12BreakFrom = 'validation.required';
      if (claim === 'HOME_2H' && !breakTo) local.art12BreakTo = 'validation.required';
    }
    if (!signature) local.signature = 'explanation.sign_required';
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      const signed = await explanationsApi.sign(e.id, {
        reason: reason!,
        reasonText: reasonText.trim() || null,
        place: place.trim() || null,
        signature: signature!,
        art12: claim,
        ...(claim !== 'NONE'
          ? {
              art12Place: art12Place.trim(),
              art12BreakFrom: claim === 'HOME_2H' ? breakFrom : null,
              art12BreakTo: claim === 'HOME_2H' ? breakTo : null,
              printoutNote: true,
              printoutNoteAt: new Date().toISOString(),
            }
          : {}),
      });
      qc.setQueryData([...EXPLANATIONS_KEY, 'one', String(e.id)], signed);
      void qc.invalidateQueries({ queryKey: [...EXPLANATIONS_KEY, 'list'] });
      notifications.show({ color: 'green', message: t('explanation.signed') });
    } catch (x) {
      if (x instanceof ApiError && x.details) setErrors(x.details);
      else notifications.show({ color: 'red', message: t(errorCode(x)) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Card title={t('explanation.reason')}>
        <Radio.Group value={reason} onChange={(v) => chooseReason(v as ExplanationReason)} error={err('reason')}>
          <Stack gap={6}>
            {options.map((o) => (
              <Radio key={o.code} value={o.code} size="md" label={t(`explanation.reason.${o.code}`)} classNames={{ root: classes.choice }} />
            ))}
          </Stack>
        </Radio.Group>
        <Textarea
          mt="sm"
          size="md"
          label={t(option?.textRequired ? 'explanation.text_required' : 'explanation.text')}
          description={t('explanation.text_hint')}
          autosize
          minRows={2}
          maxRows={6}
          maxLength={1000}
          value={reasonText}
          onChange={(x) => setReasonText(x.currentTarget.value)}
          error={err('reasonText')}
        />
        <TextInput mt="sm" size="md" label={t('explanation.place')} maxLength={100} value={place} onChange={(x) => setPlace(x.currentTarget.value)} error={err('place')} />
      </Card>

      {anyArt12 && reason && (
        <Card title={t('explanation.art12.title')}>
          <Stack gap="sm">
            <Text size="sm" c="dimmed">
              {t('explanation.art12.hint')}
            </Text>
            {choices.length === 1 ? (
              <Alert color="gray" variant="light" p="sm">
                {t('explanation.art12.not_for_reason')}
              </Alert>
            ) : (
              <Radio.Group value={claim} onChange={(v) => setArt12(v as Art12Claim)} error={err('art12')}>
                <Stack gap={6}>
                  {choices.map((c) => (
                    <Radio key={c} value={c} size="md" label={t(`explanation.art12.${c}`)} classNames={{ root: classes.choice }} />
                  ))}
                </Stack>
              </Radio.Group>
            )}
            {claim !== 'NONE' && (
              <>
                <TextInput size="md" label={t('explanation.art12.place')} maxLength={100} value={art12Place} onChange={(x) => setArt12Place(x.currentTarget.value)} error={err('art12Place')} />
                {claim === 'HOME_2H' && (
                  <Group grow align="flex-start">
                    <TimeInput size="md" label={t('explanation.art12.break_from')} value={breakFrom} onChange={(x) => setBreakFrom(x.currentTarget.value)} error={err('art12BreakFrom')} />
                    <TimeInput size="md" label={t('explanation.art12.break_to')} value={breakTo} onChange={(x) => setBreakTo(x.currentTarget.value)} error={err('art12BreakTo')} />
                  </Group>
                )}
                <Paper withBorder p="sm">
                  <Text size="sm" fw={600} mb={4}>
                    {t('explanation.art12.note_title')}
                  </Text>
                  {note.data ? (
                    <>
                      <Text size="xs" c="dimmed" mb={6}>
                        {note.data.instruction}
                      </Text>
                      <Text className={classes.mono} style={{ whiteSpace: 'pre-wrap' }}>
                        {note.data.text}
                      </Text>
                    </>
                  ) : note.isError ? (
                    <PartError error={note.error} />
                  ) : (
                    <Waiting query={note} />
                  )}
                </Paper>
                <Checkbox size="md" label={t('explanation.art12.note_done')} checked={printoutNote} onChange={(x) => setPrintoutNote(x.currentTarget.checked)} error={err('printoutNote')} />
              </>
            )}
          </Stack>
        </Card>
      )}

      <Card title={t('explanation.signature')}>
        <SignaturePad label={t('explanation.sign_here')} description={t('explanation.sign_hint')} error={err('signature')} onChange={setSignature} />
      </Card>
      <Button size="lg" leftSection={<IconSignature size={20} />} onClick={() => void sign()} loading={busy}>
        {t('explanation.sign_send')}
      </Button>
    </>
  );
}

/** Cabinet: one explanation; while pending the driver fills in the form and signs with a finger, afterwards the PDF. */
export function ExplanationSign() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'one', id], queryFn: () => explanationsApi.get(id!), retry: false, enabled: !!id });

  const back = (
    <Button component={Link} to="/driver/explanations" variant="subtle" size="sm" leftSection={<IconArrowLeft size={16} />} w="fit-content">
      {t('explanation.my_title')}
    </Button>
  );
  if (q.isPending) return <Waiting query={q} />;
  if (q.isError)
    return (
      <Stack gap="sm">
        {back}
        <PartError error={q.error} />
      </Stack>
    );
  const e = q.data;

  return (
    <Stack gap="sm">
      {back}
      <Card title={t('explanation.title')} right={<ExplanationStatusChip status={e.status} />}>
        <Stack gap="sm">
          <ExplanationFacts e={e} />
          <Text size="xs" c="dimmed">
            {t('explanation.doc_lang', { lang: languageName(e.docLang) })}
          </Text>
        </Stack>
      </Card>

      {e.status === 'PENDING' ? (
        <>
          <Alert color="blue" variant="light" icon={<IconAlertTriangle size={16} />} p="sm">
            {t('explanation.art12_hint')}
          </Alert>
          <SignForm e={e} />
        </>
      ) : (
        <Card title={t('explanation.reason')}>
          <Stack gap="xs">
            <Text>{e.reason ? t(`explanation.reason.${e.reason}`) : '—'}</Text>
            {e.reasonText && <Text size="sm">{e.reasonText}</Text>}
            {e.place && (
              <Text size="sm" c="dimmed">
                {e.place}
              </Text>
            )}
            {e.art12 && e.art12 !== 'NONE' && (
              <Text size="sm">
                {t(`explanation.art12_short.${e.art12}`)}
                {e.art12Place ? ` · ${e.art12Place}` : ''}
              </Text>
            )}
            {e.status === 'SIGNED' && (
              <Button size="md" variant="default" leftSection={<IconDownload size={18} />} onClick={() => void downloadPdf(e).catch((x) => notifications.show({ color: 'red', message: t(errorCode(x)) }))}>
                {t('explanation.pdf')}
              </Button>
            )}
            <Button size="md" variant="subtle" onClick={() => void navigate('/driver/explanations')}>
              {t('common.back')}
            </Button>
          </Stack>
        </Card>
      )}
    </Stack>
  );
}
