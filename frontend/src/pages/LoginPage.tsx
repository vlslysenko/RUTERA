import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { useLogin, useMe } from '@/auth/useAuth';
import { LanguageMenu } from '@/layout/LanguageMenu';
import classes from './LoginPage.module.css';

const schema = z.object({
  login: z.string().trim().min(1),
  password: z.string().min(1),
});

type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { data: me } = useMe();
  const login = useLogin();

  const from = (location.state as { from?: string } | null)?.from ?? '/';

  // Already signed in (e.g. page reload on /login): go to the app.
  useEffect(() => {
    if (me) void navigate(from, { replace: true });
  }, [me, from, navigate]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { login: '', password: '' } });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await login.mutateAsync(values);
      await navigate(from, { replace: true });
    } catch {
      // The error is rendered from login.error below.
    }
  });

  const errorKey = login.isError ? errorCode(login.error) : null;

  return (
    <div className={classes.page}>
      <div className={classes.card}>
        <div>
          <div className={classes.logo}>
            <span className={classes.dot} />
            {t('app.name')}
          </div>
          <Text c="dimmed" size="sm" mt={4}>
            {t('auth.login.subtitle')}
          </Text>
        </div>

        <form onSubmit={(e) => void onSubmit(e)} noValidate>
          <Stack gap="sm">
            <TextInput
              label={t('auth.login.login')}
              autoComplete="username"
              autoFocus
              error={errors.login ? t('auth.login.required') : undefined}
              {...register('login')}
            />
            <PasswordInput
              label={t('auth.login.password')}
              autoComplete="current-password"
              error={errors.password ? t('auth.login.required') : undefined}
              {...register('password')}
            />
            {errorKey && (
              <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} p="xs">
                {t(errorKey)}
              </Alert>
            )}
            <Button type="submit" fullWidth loading={login.isPending} mt={4}>
              {t('auth.login.submit')}
            </Button>
          </Stack>
        </form>

        <div className={classes.footer}>
          <Text size="xs" c="dimmed">
            {t('auth.login.title')}
          </Text>
          <LanguageMenu />
        </div>
      </div>
    </div>
  );
}
