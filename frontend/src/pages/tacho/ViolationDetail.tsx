import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Anchor, Button, Group, Paper, Stack, Text, Textarea, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowBackUp, IconCheck, IconSignature } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { EXPLANATIONS_KEY, explanationsApi } from '@/api/explanations';
import { HOS_KEY, VIOLATIONS_KEY, hosApi } from '@/api/hos';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { formatInstant } from './format';
import { formatMinutes, mondayOf, mondayOfDay, REST_RULES, ymdIn } from './hosFormat';
import { ExplanationStatusChip } from './ExplanationChips';
import { ReviewChip, SeverityChip } from './ViolationChips';
import classes from './Hos.module.css';

/**
 * One violation: what rule, how much over (or short of) the limit, when and in which vehicle, and the review by a
 * dispatcher (tacho.violation/ack) with an optional note. Right panel (/tacho/violations/:id) or page (.../page).
 */
export function ViolationDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const tz = me.firma?.timezone;
  const qc = useQueryClient();
  const { search } = useLocation();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...VIOLATIONS_KEY, id], queryFn: () => hosApi.violation(id), enabled: id !== '' });
  const v = q.data;
  const canAck = hasPrivilege(me, PRIV.TACHO_VIOLATION_ACK);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  /** Asks the driver to explain this violation: the cabinet gets it with the facts filled in, and a push. */
  const askExplanation = async () => {
    setBusy(true);
    try {
      const e = await explanationsApi.create({ violationId: Number(id) });
      void qc.invalidateQueries({ queryKey: [...VIOLATIONS_KEY, id] });
      void qc.invalidateQueries({ queryKey: EXPLANATIONS_KEY });
      notifications.show({ color: 'green', message: t('explanation.requested') });
      await navigate(`/tacho/explanations/${e.id}`);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const review = async (undo: boolean) => {
    setBusy(true);
    try {
      const updated = undo ? await hosApi.unack(id) : await hosApi.ack(id, note.trim() || null);
      qc.setQueryData([...VIOLATIONS_KEY, id], updated);
      void qc.invalidateQueries({ queryKey: [...VIOLATIONS_KEY, 'list'] });
      void qc.invalidateQueries({ queryKey: HOS_KEY });
      setNote('');
      notifications.show({ color: 'green', message: t(undo ? 'hos.v.unacked' : 'hos.v.acked') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const rest = v ? REST_RULES.includes(v.rule) : false;
  const diff = v ? Math.abs(v.actualValue - v.limitValue) : 0;

  return (
    <DetailDrawer
      mode={mode}
      listPath={`/tacho/violations${search}`}
      pagePath={`/tacho/violations/${id}/page${search}`}
      title={v ? t(`hos.rule.${v.rule}`) : '…'}
      subtitle={v && `${v.driver.name} · ${formatInstant(v.startAt, lang, tz)}`}
      status={
        v && (
          <Group gap={6}>
            <SeverityChip severity={v.severity} />
            <ReviewChip v={v} />
          </Group>
        )
      }
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : v ? (
        <Stack gap="lg">
          <Text size="sm" c="dimmed">
            {t(`hos.rule_hint.${v.rule}`)}
          </Text>
          <KeyValueList
            items={[
              { label: t('hos.v.measured'), value: <span className={classes.mono}>{formatMinutes(t, v.actualValue)}</span> },
              { label: t('hos.v.limit'), value: <span className={classes.mono}>{formatMinutes(t, v.limitValue)}</span> },
              {
                label: t(rest ? 'hos.v.short_by' : 'hos.v.over_by'),
                value: <span className={classes.mono}>{formatMinutes(t, diff)}</span>,
              },
              { label: t('hos.v.period'), value: `${formatInstant(v.startAt, lang, tz)} – ${formatInstant(v.endAt, lang, tz)}` },
              {
                label: t('fleet.driver'),
                value: (
                  <Anchor component={Link} to={`/drivers/${v.driver.id}`}>
                    {v.driver.name}
                  </Anchor>
                ),
              },
              {
                label: t('drivers.vehicle'),
                value: v.vehicle?.id != null ? (
                  <Anchor component={Link} to={`/fleet/${v.vehicle.id}`}>
                    <Plate>{v.vehicle.plate}</Plate>
                  </Anchor>
                ) : v.vehicle ? (
                  <Plate>{v.vehicle.plate}</Plate>
                ) : (
                  '—'
                ),
              },
              ...(v.details ? [{ label: t('drivers.notes'), value: v.details }] : []),
            ]}
          />
          <Anchor
            component={Link}
            to={`/tacho/activity?driver=${v.driver.id}&week=${tz ? mondayOfDay(ymdIn(v.startAt, tz)) : mondayOf(new Date(v.startAt))}`}
            size="sm"
          >
            {t('hos.v.show_week')}
          </Anchor>

          <section className={classes.section}>
            <h3 className={classes.sectionTitle}>{t('explanation.section')}</h3>
            {v.explanationId != null ? (
              <Group gap="xs">
                {v.explanationStatus && <ExplanationStatusChip status={v.explanationStatus} />}
                <Anchor component={Link} to={`/tacho/explanations/${v.explanationId}`} size="sm">
                  {t('explanation.open_it')}
                </Anchor>
              </Group>
            ) : canAck ? (
              <Tooltip label={t('hint.action.explanation_ask')}>
                <Button size="xs" variant="light" leftSection={<IconSignature size={14} />} onClick={() => void askExplanation()} loading={busy} w="fit-content">
                  {t('explanation.ask')}
                </Button>
              </Tooltip>
            ) : (
              <Text size="sm" c="dimmed">
                {t('explanation.none_yet')}
              </Text>
            )}
          </section>

          <section className={classes.section}>
            <h3 className={classes.sectionTitle}>{t('hos.v.review')}</h3>
            {v.ack ? (
              <Stack gap="xs">
                <Text size="sm">
                  {t('hos.v.reviewed_by', { name: v.ack.by.name, at: formatInstant(v.ack.at, lang) })}
                </Text>
                {v.ack.note && (
                  <Paper withBorder p="xs">
                    <Text size="sm">{v.ack.note}</Text>
                  </Paper>
                )}
                {canAck && (
                  <Group>
                    <Tooltip label={t('hint.action.violation_unack')}>
                      <Button
                        variant="default"
                        size="xs"
                        leftSection={<IconArrowBackUp size={14} />}
                        onClick={() => void review(true)}
                        loading={busy}
                      >
                        {t('hos.v.unack')}
                      </Button>
                    </Tooltip>
                  </Group>
                )}
              </Stack>
            ) : canAck ? (
              <Stack gap="xs">
                <Textarea
                  label={t('hos.v.ack_note')}
                  autosize
                  minRows={2}
                  maxRows={5}
                  maxLength={500}
                  value={note}
                  onChange={(e) => setNote(e.currentTarget.value)}
                />
                <Group justify="flex-end">
                  <Tooltip label={t('hint.action.violation_ack')}>
                    <Button size="xs" leftSection={<IconCheck size={14} />} onClick={() => void review(false)} loading={busy}>
                      {t('hos.v.ack')}
                    </Button>
                  </Tooltip>
                </Group>
              </Stack>
            ) : (
              <Text size="sm" c="dimmed">
                {t('hos.v.new')}
              </Text>
            )}
          </section>
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
