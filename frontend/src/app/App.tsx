import { Suspense } from 'react';
import { RouterProvider } from 'react-router';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { ME_QUERY_KEY } from '@/auth/useAuth';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { DatesLocaleProvider } from '@/i18n/DatesLocaleProvider';
import { startOfflineCache } from './offlineCache';
import { queryClient } from './queryClient';
import { router } from './router';
import { cssVariablesResolver, theme } from './theme';

// Offline data of the driver cabinet, restored before the first render.
startOfflineCache(queryClient, ME_QUERY_KEY);

export function App() {
  return (
    // Light theme only (REQUIREMENTS.md, section 15: no dark theme).
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} forceColorScheme="light">
      <Notifications position="top-right" />
      <DatesLocaleProvider>
        <QueryClientProvider client={queryClient}>
          <Suspense fallback={<FullScreenLoader />}>
            <RouterProvider router={router} />
          </Suspense>
        </QueryClientProvider>
      </DatesLocaleProvider>
    </MantineProvider>
  );
}
