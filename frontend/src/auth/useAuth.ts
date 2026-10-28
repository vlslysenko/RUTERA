import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi, type LoginRequest } from '@/api/auth';
import type { User, UserSettingsPatch } from '@/app/types';

export const ME_QUERY_KEY = ['auth', 'me'] as const;

/** Current session: User, null (signed out) or undefined while loading. */
export function useMe() {
  return useQuery({ queryKey: ME_QUERY_KEY, queryFn: authApi.me, staleTime: 5 * 60_000, retry: false });
}

/** Current user inside the authenticated area (RequireAuth guarantees it exists). */
export function useUser(): User {
  const { data } = useMe();
  if (!data) throw new Error('useUser() must be used inside RequireAuth');
  return data;
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: LoginRequest) => authApi.login(req),
    onSuccess: (user) => qc.setQueryData<User | null>(ME_QUERY_KEY, user),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => {
      qc.setQueryData<User | null>(ME_QUERY_KEY, null);
      // Drop everything that belonged to the previous session.
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== 'auth' });
    },
  });
}

export function useUpdateSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (patch: UserSettingsPatch) => authApi.updateSettings(patch),
    onSuccess: (user) => qc.setQueryData<User | null>(ME_QUERY_KEY, user),
  });
}
