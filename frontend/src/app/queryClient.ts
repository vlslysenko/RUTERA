import { onlineManager, QueryClient } from '@tanstack/react-query';
import { ApiError, setUnauthenticatedHandler } from '@/api/client';

export const queryClient = new QueryClient({
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

/**
 * A 401 from any request means the session ended (12 h after sign-in, 4 h idle or a server restart): the session is
 * asked again, /auth/me answers "none" and RequireAuth leads to the login page, instead of the screen showing
 * "Please sign in" as its own error. The client reports it for every request (src/api/client.ts), so a calculation or
 * a button that calls the API directly is covered as well as queries and mutations.
 */
setUnauthenticatedHandler(() => void queryClient.invalidateQueries({ queryKey: ['auth', 'me'] }));

// TanStack Query v5 assumes "online" until an offline event; a phone that starts without a connection would then
// fail its requests instead of pausing them (and hide the saved data of the driver cabinet).
if (typeof navigator !== 'undefined' && navigator.onLine === false) onlineManager.setOnline(false);
