import { MutationCache, onlineManager, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';

/**
 * A 401 from any request means the session ended (12 h after sign-in or 4 h idle): the session is asked again,
 * /auth/me answers "none" and RequireAuth leads to the login page, instead of every part showing its own error.
 */
function sessionEnded(error: unknown) {
  if (error instanceof ApiError && error.status === 401) void queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: sessionEnded }),
  mutationCache: new MutationCache({ onError: sessionEnded }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // Never retry client errors (401, 403, 404, validation); retry network/5xx once.
        if (error instanceof ApiError && error.status > 0 && error.status < 500) return false;
        return failureCount < 1;
      },
    },
    mutations: {
      retry: false,
    },
  },
});

// TanStack Query v5 assumes "online" until an offline event; a phone that starts without a connection would then
// fail its requests instead of pausing them (and hide the saved data of the driver cabinet).
if (typeof navigator !== 'undefined' && navigator.onLine === false) onlineManager.setOnline(false);
