import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Group, PasswordInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconLockOpen, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ADMIN_USERS_KEY, adminUsersApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { ASSIGNABLE_ROLES, type AdminUser, type Role } from '@/app/types';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { applyServerErrors } from '@/forms/serverErrors';
import { isLanguageCode, LANGUAGES, type LanguageCode } from '@/i18n/languages';
import { PASSWORD_MAX, PASSWORD_MIN } from '../PasswordTab';
import { UserStatusChips } from './UsersPage';

const LIST_PATH = '/settings/users';
const ROLE_VALUES = ['OWNER', 'DISPATCHER', 'LOGISTICIAN', 'ACCOUNTANT', 'DRIVER', 'CUSTOM'] as const;

const editSchema = z.object({
  login: z.string(),
  firstName: z.string().trim().min(1, 'validation.required').max(100, 'validation.too_long'),
  lastName: z.string().trim().max(100, 'validation.too_long'),
  phone: z.string().trim().max(40, 'validation.too_long'),
  role: z.enum(ROLE_VALUES),
  // Explicit boolean: TS 5.5+ would otherwise infer a type predicate and narrow the form type to LanguageCode.
  language: z.string().refine((v): boolean => isLanguageCode(v), 'validation.invalid_value'),
  active: z.boolean(),
  password: z.string(),
});

const createSchema = editSchema.extend({
  login: z
    .string()
    .trim()
    .min(3, 'validation.too_short')
    .max(254, 'validation.too_long')
    .regex(/^[\p{L}\p{N}._@+-]+$/u, 'validation.invalid_format'),
  password: z.string().min(PASSWORD_MIN, 'validation.too_short').max(PASSWORD_MAX, 'validation.too_long'),
});

type FormValues = z.infer<typeof createSchema>;

const FIELDS: readonly (keyof FormValues)[] = ['login', 'firstName', 'lastName', 'phone', 'role', 'language', 'password'];

const EMPTY: FormValues = {
  login: '',
  firstName: '',
  lastName: '',
  phone: '',
  role: 'DISPATCHER',
  language: 'pl',
  active: true,
  password: '',
};

function toFormValues(u: AdminUser): FormValues {
  return {
    login: u.login,
    firstName: u.firstName,
    lastName: u.lastName ?? '',
    phone: u.phone ?? '',
    role: u.role === 'SUPERUSER' || u.role === 'API' ? 'CUSTOM' : u.role,
    language: u.language,
    active: u.active,
    password: '',
  };
}

/** User card: create (/settings/users/new), edit, reset password, unlock, delete. Drawer and page share it. */
export function UserDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  // The "new" route has no :id param; edit routes (…/:id and …/:id/page) always do.
  const params = useParams();
  const isNew = params.id === undefined || params.id === 'new';
  const id = isNew ? '' : (params.id ?? '');
  const canManage = hasPrivilege(me, PRIV.ADMIN_USER_MANAGE);

  const q = useQuery({
    queryKey: [...ADMIN_USERS_KEY, id],
    queryFn: () => adminUsersApi.get(id),
    enabled: !isNew && id !== '',
  });
  const user = q.data;
  const isSelf = user?.id === me.id;
  const readOnly = !canManage;

  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({
    resolver: zodResolver(isNew ? createSchema : editSchema),
    defaultValues: { ...EMPTY, language: me.language },
    values: user ? toFormValues(user) : undefined,
  });

  const [resetOpen, setResetOpen] = useState(false);
  const [newPassword, setNewPassword] = useState('');
  const [resetError, setResetError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  const fail = (e: unknown) => notifications.show({ color: 'red', message: t(errorCode(e)) });
  const invalidate = () => qc.invalidateQueries({ queryKey: ADMIN_USERS_KEY });

  const onSubmit = handleSubmit(async (v) => {
    try {
      if (isNew) {
        const created = await adminUsersApi.create({
          login: v.login,
          firstName: v.firstName,
          lastName: v.lastName || null,
          role: v.role as Role,
          phone: v.phone || null,
          language: v.language as LanguageCode,
          password: v.password,
        });
        await invalidate();
        notifications.show({ color: 'green', message: t('users.created') });
        await navigate(`${LIST_PATH}/${created.id}`, { replace: true });
      } else if (user) {
        const updated = await adminUsersApi.update(user.id, {
          firstName: v.firstName,
          lastName: v.lastName || null,
          role: v.role as Role,
          customRoleId: user.customRoleId,
          phone: v.phone || null,
          language: v.language as LanguageCode,
          active: v.active,
        });
        qc.setQueryData([...ADMIN_USERS_KEY, id], updated);
        await qc.invalidateQueries({ queryKey: ADMIN_USERS_KEY, exact: true });
        notifications.show({ color: 'green', message: t('users.updated') });
      }
    } catch (e) {
      if (!applyServerErrors(e, setError, FIELDS)) fail(e);
    }
  });

  const resetPassword = async () => {
    if (!user) return;
    if (newPassword.length < PASSWORD_MIN) return setResetError('validation.too_short');
    if (newPassword.length > PASSWORD_MAX) return setResetError('validation.too_long');
    setBusy(true);
    try {
      await adminUsersApi.resetPassword(user.id, newPassword);
      setResetOpen(false);
      setNewPassword('');
      setResetError(null);
      await invalidate();
      notifications.show({ color: 'green', message: t('users.password_reset') });
    } catch (e) {
      const fieldCode = e instanceof ApiError ? e.details?.newPassword : undefined;
      if (fieldCode) setResetError(fieldCode);
      else fail(e);
    } finally {
      setBusy(false);
    }
  };

  const unlock = async () => {
    if (!user) return;
    setBusy(true);
    try {
      await adminUsersApi.unlock(user.id);
      await invalidate();
      notifications.show({ color: 'green', message: t('users.unlocked') });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!user) return;
    setBusy(true);
    try {
      await adminUsersApi.remove(user.id);
      notifications.show({ color: 'green', message: t('users.deleted') });
      // Leave the card and refresh everything except the deleted user: its own queries would answer 404 (the cache
      // entry goes away by itself once the card is unmounted).
      await navigate(LIST_PATH, { replace: true });
      void qc.invalidateQueries({ queryKey: ADMIN_USERS_KEY, predicate: (q) => q.queryKey[ADMIN_USERS_KEY.length] !== id });
    } catch (e) {
      fail(e);
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  const roleOptions = ROLE_VALUES.filter((r) => ASSIGNABLE_ROLES.includes(r) || user?.role === r).map((r) => ({
    value: r,
    label: t(`role.${r}`),
    disabled: r === 'CUSTOM',
  }));

  const actions =
    !isNew && user && canManage && !isSelf ? (
      <>
        <Button variant="default" size="xs" onClick={() => setResetOpen((o) => !o)} disabled={busy}>
          {t('users.reset_password')}
        </Button>
        {user.locked && (
          <Button variant="default" size="xs" leftSection={<IconLockOpen size={14} />} onClick={() => void unlock()} loading={busy}>
            {t('users.unlock')}
          </Button>
        )}
        <Button variant="default" size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)} disabled={busy}>
          {t('users.delete')}
        </Button>
      </>
    ) : undefined;

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST_PATH}
      pagePath={isNew ? undefined : `${LIST_PATH}/${id}/page`}
      title={isNew ? t('users.new') : (user?.name ?? '…')}
      subtitle={
        user && (
          <>
            <span style={{ fontFamily: 'var(--mantine-font-family-monospace)' }}>{user.login}</span>
            {isSelf ? ` · ${t('users.self')}` : ''}
          </>
        )
      }
      status={user && <UserStatusChips user={user} />}
      actions={actions}
      loading={!isNew && q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('users.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          {resetOpen && (
            <Alert color="blue" variant="light" p="sm">
              <Stack gap="xs">
                <PasswordInput
                  label={t('users.new_password')}
                  description={t('password.hint')}
                  autoComplete="new-password"
                  value={newPassword}
                  onChange={(e) => {
                    setNewPassword(e.currentTarget.value);
                    setResetError(null);
                  }}
                  error={resetError && t(resetError)}
                />
                <Group gap="xs">
                  <Button size="xs" onClick={() => void resetPassword()} loading={busy}>
                    {t('users.reset_password')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setResetOpen(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          <form onSubmit={(e) => void onSubmit(e)} noValidate>
            <Stack gap="sm">
              {isNew ? (
                <TextInput
                  label={t('users.login')}
                  autoComplete="off"
                  autoFocus
                  error={errors.login?.message && t(errors.login.message)}
                  {...register('login')}
                />
              ) : (
                <TextInput label={t('users.login')} value={user?.login ?? ''} readOnly disabled />
              )}
              <Group grow align="flex-start">
                <TextInput
                  label={t('users.first_name')}
                  autoComplete="off"
                  readOnly={readOnly}
                  error={errors.firstName?.message && t(errors.firstName.message)}
                  {...register('firstName')}
                />
                <TextInput
                  label={t('users.last_name')}
                  autoComplete="off"
                  readOnly={readOnly}
                  error={errors.lastName?.message && t(errors.lastName.message)}
                  {...register('lastName')}
                />
              </Group>
              <TextInput
                label={t('users.phone')}
                autoComplete="off"
                readOnly={readOnly}
                error={errors.phone?.message && t(errors.phone.message)}
                {...register('phone')}
              />
              <Controller
                name="role"
                control={control}
                render={({ field, fieldState }) => (
                  <Select
                    label={t('users.role')}
                    data={roleOptions}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    onBlur={field.onBlur}
                    allowDeselect={false}
                    disabled={readOnly || isSelf}
                    error={fieldState.error?.message && t(fieldState.error.message)}
                  />
                )}
              />
              <Controller
                name="language"
                control={control}
                render={({ field, fieldState }) => (
                  <Select
                    label={t('users.language')}
                    data={LANGUAGES.map((l) => ({ value: l.code, label: l.name }))}
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    onBlur={field.onBlur}
                    allowDeselect={false}
                    searchable
                    maxDropdownHeight={260}
                    disabled={readOnly}
                    error={fieldState.error?.message && t(fieldState.error.message)}
                  />
                )}
              />
              {isNew ? (
                <PasswordInput
                  label={t('users.password')}
                  description={t('password.hint')}
                  autoComplete="new-password"
                  error={errors.password?.message && t(errors.password.message)}
                  {...register('password')}
                />
              ) : (
                <Controller
                  name="active"
                  control={control}
                  render={({ field }) => (
                    <Switch
                      label={t('users.active')}
                      checked={field.value}
                      onChange={(e) => field.onChange(e.currentTarget.checked)}
                      disabled={readOnly || isSelf}
                    />
                  )}
                />
              )}
              {canManage && (
                <Group justify="flex-end" gap="xs" mt="xs">
                  <Button variant="default" onClick={() => void navigate(LIST_PATH)} disabled={isSubmitting}>
                    {t('common.cancel')}
                  </Button>
                  <Button type="submit" loading={isSubmitting} disabled={!isNew && !isDirty}>
                    {isNew ? t('common.create') : t('common.save')}
                  </Button>
                </Group>
              )}
            </Stack>
          </form>
        </Stack>
      )}
    </DetailDrawer>
  );
}
