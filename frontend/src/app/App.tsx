import { Suspense } from 'react';
import { RouterProvider } from 'react-router';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { queryClient } from './queryClient';
import { router } from './router';
import { cssVariablesResolver, theme } from './theme';

export function App() {
  return (
    // Light theme only (REQUIREMENTS.md, section 15: no dark theme).
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} forceColorScheme="light">
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <Suspense fallback={<FullScreenLoader />}>
          <RouterProvider router={router} />
        </Suspense>
      </QueryClientProvider>
    </MantineProvider>
  );
}
