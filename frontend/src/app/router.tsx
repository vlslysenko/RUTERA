import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { RequireAuth } from '@/auth/RequireAuth';
import { ScreenGuard } from '@/auth/ScreenGuard';
import { useUser } from '@/auth/useAuth';
import { AppLayout } from '@/layout/AppLayout';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { OverviewPage } from '@/pages/OverviewPage';
import { PlaceholderPage } from '@/pages/PlaceholderPage';
import { FleetPage } from '@/pages/fleet/FleetPage';
import { VehicleDetail } from '@/pages/fleet/VehicleDetail';
import { SettingsPage } from '@/pages/settings/SettingsPage';
import { SCREENS, startScreenPath } from './navigation';
import type { ScreenKey } from './types';

/** "/" sends the user to the start screen from the profile, or the role default. */
function StartRedirect() {
  const user = useUser();
  return <Navigate to={startScreenPath(user.role, user.startScreen)} replace />;
}

/** A screen is reachable only for roles that see it in the menu. */
function screen(key: ScreenKey, routes: RouteObject[]): RouteObject {
  return { element: <ScreenGuard screen={key} />, children: routes };
}

const placeholder = (key: ScreenKey): RouteObject =>
  screen(key, [{ path: SCREENS[key].path, element: <PlaceholderPage screen={key} /> }]);

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <StartRedirect /> },
          screen('overview', [{ path: SCREENS.overview.path, element: <OverviewPage /> }]),
          placeholder('map'),
          placeholder('trips'),
          screen('fleet', [
            {
              path: SCREENS.fleet.path,
              element: <FleetPage />,
              children: [{ path: ':id', element: <VehicleDetail mode="drawer" /> }],
            },
            { path: `${SCREENS.fleet.path}/:id/page`, element: <VehicleDetail mode="page" /> },
          ]),
          placeholder('drivers'),
          placeholder('tacho'),
          placeholder('documents'),
          placeholder('finance'),
          placeholder('fuel'),
          placeholder('reports'),
          { path: 'settings', element: <SettingsPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
