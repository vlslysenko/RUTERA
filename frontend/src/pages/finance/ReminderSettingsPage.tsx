import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useFieldArray, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ActionIcon, Button, Checkbox, Group, NumberInput, Paper, Select, Stack, TagsInput, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { financeApi, REMINDER_SCHEDULE_KEY, type ReminderSchedule } from '@/api/finance';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { numberField, toNumberInputValue } from '@/forms/number';
import { applyServerErrors } from '@/forms/serverErrors';
import { reminderStepLabel } from './financeFormat';
import classes from './Finance.module.css';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/* Same rules as ReminderService: hour 0..23, up to 5 cc addresses, up to 10 steps of -60..365 days, no repeats. */
const schema = z
  .object({
    sendHour: z.string(),
    ccEmails: z
      .array(z.string())
      .max(5, 'validation.too_long')
      .refine((a) => a.every((e) => EMAIL_RE.test(e)), 'validation.invalid_format'),
    stopWhenPaid: z.boolean(),
    steps: z
      .array(z.object({ offsetDays: numberField(-60, 365, { integer: true }).refine((v) => v != null, 'validation.required'), escalate: z.boolean() }))
      .max(10, 'validation.too_long'),
  })
  .superRefine((v, ctx) => {
    const seen = new Set<number>();
    v.steps.forEach((s, i) => {
      if (s.offsetDays == null) return;
      if (seen.has(s.offsetDays)) ctx.addIssue({ code: 'custom', path: ['steps', i, 'offsetDays'], message: 'validation.invalid_value' });
      seen.add(s.offsetDays);
    });
  });

type FormInput = z.input<typeof schema>;
type FormValues = z.output<typeof schema>;
const FIELDS: readonly FieldPath<FormInput>[] = ['sendHour', 'ccEmails', 'stopWhenPaid', 'steps'];

const toForm = (s: ReminderSchedule): FormInput => ({
  sendHour: String(s.sendHour),
  ccEmails: s.ccEmails,
  stopWhenPaid: s.stopWhenPaid,
  steps: s.steps.map((x) => ({ offsetDays: x.offsetDays, escalate: x.escalate })),
});

/** Plan of payment reminders for sales invoices: the hour, extra recipients and the steps around the due date. */
export function ReminderSettingsPage() {
  const { t } = useTranslation();
  const me = useUser();
  const qc = useQueryClient();
  const canManage = hasPrivilege(me, PRIV.FINANCE_REMINDER_MANAGE);
  const q = useQuery({ queryKey: REMINDER_SCHEDULE_KEY, queryFn: financeApi.schedule });
  const {
    control,
    handleSubmit,
    setError,
    watch,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormInput, unknown, FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { sendHour: '8', ccEmails: [], stopWhenPaid: true, steps: [] },
    values: q.data ? toForm(q.data) : undefined,
  });
  const steps = useFieldArray({ control, name: 'steps' });
  const watched = watch('steps');

  const onSubmit = handleSubmit(async (f) => {
    try {
      const saved = await financeApi.saveSchedule({
        sendHour: Number(f.sendHour),
        ccEmails: f.ccEmails.map((e) => e.trim()).filter(Boolean),
        stopWhenPaid: f.stopWhenPaid,
        steps: f.steps.map((s) => ({ offsetDays: s.offsetDays ?? 0, escalate: s.escalate })).sort((a, b) => a.offsetDays - b.offsetDays),
      });
      qc.setQueryData(REMINDER_SCHEDULE_KEY, saved);
      reset(toForm(saved));
      notifications.show({ color: 'green', message: t('reminders.saved') });
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  });

  if (q.isPending) return <FullScreenLoader fullScreen={false} />;
  if (q.isError) return <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />;

  const stepError = (i: number) => {
    const code = errors.steps?.[i]?.offsetDays?.message;
    return code ? t(code) : undefined;
  };

  return (
    <Paper p="md" maw={640}>
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            {t('reminders.intro')}
          </Text>
          <Group grow align="flex-start">
            <Controller
              name="sendHour"
              control={control}
              render={({ field }) => (
                <Select
                  label={t('reminders.send_hour')}
                  data={Array.from({ length: 24 }, (_, h) => ({ value: String(h), label: `${String(h).padStart(2, '0')}:00` }))}
                  value={field.value}
                  onChange={(v) => v && field.onChange(v)}
                  allowDeselect={false}
                  disabled={!canManage}
                  error={errors.sendHour?.message && t(errors.sendHour.message)}
                />
              )}
            />
            <Controller
              name="stopWhenPaid"
              control={control}
              render={({ field }) => (
                <Checkbox
                  mt={30}
                  label={t('reminders.stop_when_paid')}
                  checked={field.value}
                  onChange={(e) => field.onChange(e.currentTarget.checked)}
                  disabled={!canManage}
                />
              )}
            />
          </Group>
          <Controller
            name="ccEmails"
            control={control}
            render={({ field }) => (
              <TagsInput
                label={t('reminders.cc')}
                description={t('reminders.cc_hint')}
                value={field.value}
                onChange={field.onChange}
                maxTags={5}
                splitChars={[',', ' ', ';']}
                disabled={!canManage}
                error={errors.ccEmails?.message && t(errors.ccEmails.message)}
              />
            )}
          />

          <Stack gap={6}>
            <Group justify="space-between">
              <Text size="xs" fw={600} tt="uppercase" c="dimmed">
                {t('reminders.steps')}
              </Text>
              {canManage && (
                <Button
                  size="xs"
                  variant="light"
                  leftSection={<IconPlus size={14} />}
                  onClick={() => steps.append({ offsetDays: Math.max(0, ...watched.map((s) => Number(s.offsetDays) || 0)) + 7, escalate: false })}
                  disabled={steps.fields.length >= 10}
                >
                  {t('reminders.add_step')}
                </Button>
              )}
            </Group>
            {errors.steps?.message && (
              <Text size="sm" c="red">
                {t(errors.steps.message)}
              </Text>
            )}
            {steps.fields.length === 0 && (
              <Text size="sm" c="dimmed">
                {t('reminders.no_steps')}
              </Text>
            )}
            {steps.fields.map((s, i) => {
              const offset = watched[i]?.offsetDays;
              const n = offset === null || offset === undefined || offset === '' ? null : Number(offset);
              return (
                <div key={s.id} className={classes.step}>
                  <Controller
                    name={`steps.${i}.offsetDays`}
                    control={control}
                    render={({ field }) => (
                      <NumberInput
                        size="xs"
                        aria-label={t('reminders.offset')}
                        value={field.value ?? ''}
                        onChange={(v) => field.onChange(toNumberInputValue(v))}
                        allowDecimal={false}
                        min={-60}
                        max={365}
                        disabled={!canManage}
                        error={stepError(i)}
                      />
                    )}
                  />
                  <Text size="sm">{n == null || Number.isNaN(n) ? '—' : reminderStepLabel(t, n)}</Text>
                  <Controller
                    name={`steps.${i}.escalate`}
                    control={control}
                    render={({ field }) => (
                      <Tooltip label={t('reminders.escalate_hint')}>
                        <Checkbox size="xs" label={t('reminders.escalate')} checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} disabled={!canManage} />
                      </Tooltip>
                    )}
                  />
                  {canManage ? (
                    <Tooltip label={t('common.delete')}>
                      <ActionIcon variant="subtle" color="red" onClick={() => steps.remove(i)} aria-label={t('common.delete')}>
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Tooltip>
                  ) : (
                    <span />
                  )}
                </div>
              );
            })}
            <Text size="xs" c="dimmed">
              {t('reminders.offset_hint')}
            </Text>
          </Stack>

          {canManage && (
            <Group justify="flex-end" gap="xs">
              <Button variant="default" onClick={() => q.data && reset(toForm(q.data))} disabled={!isDirty || isSubmitting}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
                {t('common.save')}
              </Button>
            </Group>
          )}
        </Stack>
      </form>
    </Paper>
  );
}
