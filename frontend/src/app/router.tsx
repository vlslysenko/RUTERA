import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { PRIV } from '@/auth/privileges';
import { RequireAuth } from '@/auth/RequireAuth';
import { PrivilegeGuard, ScreenGuard } from '@/auth/ScreenGuard';
import { useUser } from '@/auth/useAuth';
import { AppLayout } from '@/layout/AppLayout';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { OverviewPage } from '@/pages/OverviewPage';
import { PlaceholderPage } from '@/pages/PlaceholderPage';
import { DriverDetail } from '@/pages/drivers/DriverDetail';
import { DriverForm } from '@/pages/drivers/DriverForm';
import { DriversPage } from '@/pages/drivers/DriversPage';
import { FleetPage } from '@/pages/fleet/FleetPage';
import { VehicleDetail } from '@/pages/fleet/VehicleDetail';
import { VehicleForm } from '@/pages/fleet/VehicleForm';
import { PasswordTab } from '@/pages/settings/PasswordTab';
import { ProfileTab } from '@/pages/settings/ProfileTab';
import { SettingsLayout } from '@/pages/settings/SettingsLayout';
import { UserDetail } from '@/pages/settings/users/UserDetail';
import { TachoFileDetail } from '@/pages/tacho/TachoFileDetail';
import { TachoFilesPage } from '@/pages/tacho/TachoFilesPage';
import { UsersPage } from '@/pages/settings/users/UsersPage';
import { SCREENS, startScreenPath } from './navigation';
import type { ScreenKey } from './types';

/** "/" sends the user to the start screen from the profile, or the role default. */
function StartRedirect() {
  const user = useUser();
  return <Navigate to={startScreenPath(user)} replace />;
}

/** A screen is reachable only for users who see it in the menu (privileges). */
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
              children: [
                { path: ':id', element: <VehicleDetail mode="drawer" /> },
                {
                  element: <PrivilegeGuard privilege={PRIV.FLEET_VEHICLE_MANAGE} />,
                  children: [
                    { path: 'new', element: <VehicleForm /> },
                    { path: ':id/edit', element: <VehicleForm /> },
                  ],
                },
              ],
            },
            { path: `${SCREENS.fleet.path}/:id/page`, element: <VehicleDetail mode="page" /> },
          ]),
          screen('drivers', [
            {
              path: SCREENS.drivers.path,
              element: <DriversPage />,
              children: [
                { path: ':id', element: <DriverDetail mode="drawer" /> },
                {
                  element: <PrivilegeGuard privilege={PRIV.DRIVER_MANAGE} />,
                  children: [
                    { path: 'new', element: <DriverForm /> },
                    { path: ':id/edit', element: <DriverForm /> },
                  ],
                },
              ],
            },
            { path: `${SCREENS.drivers.path}/:id/page`, element: <DriverDetail mode="page" /> },
          ]),
          screen('tacho', [
            {
              path: SCREENS.tacho.path,
              element: <TachoFilesPage />,
              children: [{ path: ':id', element: <TachoFileDetail mode="drawer" /> }],
            },
            { path: `${SCREENS.tacho.path}/:id/page`, element: <TachoFileDetail mode="page" /> },
          ]),
          placeholder('documents'),
          placeholder('finance'),
          placeholder('fuel'),
          placeholder('reports'),
          {
            path: 'settings',
            element: <SettingsLayout />,
            children: [
              { index: true, element: <ProfileTab /> },
              { path: 'password', element: <PasswordTab /> },
              {
                element: <PrivilegeGuard privilege={PRIV.ADMIN_USER_VIEW} />,
                children: [
                  {
                    path: 'users',
                    element: <UsersPage />,
                    children: [
                      { path: 'new', element: <UserDetail mode="drawer" /> },
                      { path: ':id', element: <UserDetail mode="drawer" /> },
                    ],
                  },
                  { path: 'users/:id/page', element: <UserDetail mode="page" /> },
                ],
              },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
