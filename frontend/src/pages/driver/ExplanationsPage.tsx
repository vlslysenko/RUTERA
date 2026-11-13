import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Group, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconArrowLeft, IconChevronRight, IconDownload, IconSignature } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError, saveBlob } from '@/api/client';
import { errorCode } from '@/api/errors';
import { EXPLANATION_REASONS, EXPLANATIONS_KEY, explanationsApi, needsText, type Explanation, type ExplanationReason } from '@/api/explanations';
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

/** Cabinet: one explanation; while pending the driver picks the reason, describes it and signs with a finger. */
export function ExplanationSign() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: [...EXPLANATIONS_KEY, 'one', id], queryFn: () => explanationsApi.get(id!), retry: false, enabled: !!id });
  const [reason, setReason] = useState<ExplanationReason | null>(null);
  const [reasonText, setReasonText] = useState('');
  const [place, setPlace] = useState('');
  const [signature, setSignature] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const err = (k: string) => (errors[k] ? t(errors[k]) : undefined);

  const back = (
    <Button component={Link} to="/driver/explanations" variant="subtle" size="sm" leftSection={<IconArrowLeft size={16} />} w="fit-content">
      {t('explanation.my_title')}
    </Button>
  );
  if (q.isPending) return <Waiting query={q} />;
  if (q.isError) return <Stack gap="sm">{back}<PartError error={q.error} /></Stack>;
  const e = q.data;

  const sign = async () => {
    const local: Record<string, string> = {};
    if (!reason) local.reason = 'validation.required';
    if (needsText(reason) && !reasonText.trim()) local.reasonText = 'validation.required';
    if (!signature) local.signature = 'explanation.sign_required';
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      const signed = await explanationsApi.sign(e.id, { reason: reason!, reasonText: reasonText.trim() || null, place: place.trim() || null, signature: signature! });
      qc.setQueryData([...EXPLANATIONS_KEY, 'one', id], signed);
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
          <Card title={t('explanation.reason')}>
            <Radio.Group value={reason} onChange={(v) => setReason(v as ExplanationReason)} error={err('reason')}>
              <Stack gap={6}>
                {EXPLANATION_REASONS.map((r) => (
                  <Radio key={r} value={r} size="md" label={t(`explanation.reason.${r}`)} classNames={{ root: classes.choice }} />
                ))}
              </Stack>
            </Radio.Group>
            <Textarea
              mt="sm"
              size="md"
              label={t(needsText(reason) ? 'explanation.text_required' : 'explanation.text')}
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
          <Card title={t('explanation.signature')}>
            <SignaturePad label={t('explanation.sign_here')} description={t('explanation.sign_hint')} error={err('signature')} onChange={setSignature} />
          </Card>
          <Button size="lg" leftSection={<IconSignature size={20} />} onClick={() => void sign()} loading={busy}>
            {t('explanation.sign_send')}
          </Button>
        </>
      ) : (
        <Card title={t('explanation.reason')}>
          <Stack gap="xs">
            <Text>{e.reason ? t(`explanation.reason.${e.reason}`) : '—'}</Text>
            {e.reasonText && <Text size="sm">{e.reasonText}</Text>}
            {e.place && <Text size="sm" c="dimmed">{e.place}</Text>}
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
