import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button, Group, Paper, PasswordInput, Stack, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { authApi } from '@/api/auth';
import { errorCode } from '@/api/errors';
import { applyServerErrors } from '@/forms/serverErrors';

export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 72;

const schema = z
  .object({
    currentPassword: z.string().min(1, 'validation.required'),
    newPassword: z.string().min(PASSWORD_MIN, 'validation.too_short').max(PASSWORD_MAX, 'validation.too_long'),
    repeat: z.string(),
  })
  .refine((v) => v.newPassword === v.repeat, { path: ['repeat'], message: 'password.mismatch' });

type FormValues = z.infer<typeof schema>;

/** Own password change: PUT /app/me/password; the session stays open. */
export function PasswordTab() {
  const { t } = useTranslation();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { currentPassword: '', newPassword: '', repeat: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await authApi.changePassword({ currentPassword: values.currentPassword, newPassword: values.newPassword });
      reset();
      notifications.show({ color: 'green', message: t('password.changed') });
    } catch (e) {
      if (!applyServerErrors(e, setError, ['currentPassword', 'newPassword'])) {
        notifications.show({ color: 'red', message: t(errorCode(e)) });
      }
    }
  });

  return (
    <Paper p="lg" maw={520}>
      <form onSubmit={(e) => void onSubmit(e)} noValidate>
        <Stack gap="md">
          <Title order={3}>{t('password.title')}</Title>
          <PasswordInput
            label={t('password.current')}
            autoComplete="current-password"
            error={errors.currentPassword?.message && t(errors.currentPassword.message)}
            {...register('currentPassword')}
          />
          <PasswordInput
            label={t('password.new')}
            description={t('password.hint')}
            autoComplete="new-password"
            error={errors.newPassword?.message && t(errors.newPassword.message)}
            {...register('newPassword')}
          />
          <PasswordInput
            label={t('password.repeat')}
            autoComplete="new-password"
            error={errors.repeat?.message && t(errors.repeat.message)}
            {...register('repeat')}
          />
          <Group justify="flex-end">
            <Button type="submit" loading={isSubmitting}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}
