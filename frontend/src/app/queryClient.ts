import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';

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
