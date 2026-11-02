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
import { AbsenceCalendarPage } from '@/pages/drivers/AbsenceCalendarPage';
import { DriverDetail } from '@/pages/drivers/DriverDetail';
import { DriverForm } from '@/pages/drivers/DriverForm';
import { DriversLayout } from '@/pages/drivers/DriversLayout';
import { DriversPage } from '@/pages/drivers/DriversPage';
import { PaymentsPage } from '@/pages/drivers/PaymentsPage';
import { FleetPage } from '@/pages/fleet/FleetPage';
import { VehicleDetail } from '@/pages/fleet/VehicleDetail';
import { VehicleForm } from '@/pages/fleet/VehicleForm';
import { PasswordTab } from '@/pages/settings/PasswordTab';
import { ProfileTab } from '@/pages/settings/ProfileTab';
import { SettingsLayout } from '@/pages/settings/SettingsLayout';
import { UserDetail } from '@/pages/settings/users/UserDetail';
import { DriverActivityPage } from '@/pages/tacho/DriverActivityPage';
import { TachoFileDetail } from '@/pages/tacho/TachoFileDetail';
import { TachoFilesPage } from '@/pages/tacho/TachoFilesPage';
import { TachoIndex, TachoLayout } from '@/pages/tacho/TachoLayout';
import { ViolationDetail } from '@/pages/tacho/ViolationDetail';
import { ViolationsPage } from '@/pages/tacho/ViolationsPage';
import { UsersPage } from '@/pages/settings/users/UsersPage';
import { ContractorDetail } from '@/pages/trips/ContractorDetail';
import { ContractorsPage } from '@/pages/trips/ContractorsPage';
import { OrderDetail } from '@/pages/trips/OrderDetail';
import { OrderForm } from '@/pages/trips/OrderForm';
import { OrdersPage } from '@/pages/trips/OrdersPage';
import { TripDetail } from '@/pages/trips/TripDetail';
import { TripForm } from '@/pages/trips/TripForm';
import { TripsFallback, TripsLayout } from '@/pages/trips/TripsLayout';
import { TripsPage } from '@/pages/trips/TripsPage';
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
          screen('trips', [
            {
              path: SCREENS.trips.path,
              element: <TripsLayout />,
              children: [
                {
                  element: <PrivilegeGuard privilege={PRIV.TRIP_TRIP_VIEW} fallback={<TripsFallback />} />,
                  children: [
                    {
                      // /trips and its cards; static "orders" / "contractors" rank above ":id".
                      element: <TripsPage />,
                      children: [
                        { index: true, element: null },
                        { path: ':id', element: <TripDetail mode="drawer" /> },
                        {
                          element: <PrivilegeGuard privilege={PRIV.TRIP_TRIP_MANAGE} />,
                          children: [
                            { path: 'new', element: <TripForm /> },
                            { path: ':id/edit', element: <TripForm /> },
                          ],
                        },
                      ],
                    },
                  ],
                },
                {
                  element: <PrivilegeGuard privilege={PRIV.TRIP_ORDER_VIEW} />,
                  children: [
                    {
                      path: 'orders',
                      element: <OrdersPage />,
                      children: [
                        { path: ':id', element: <OrderDetail mode="drawer" /> },
                        {
                          element: <PrivilegeGuard privilege={PRIV.TRIP_ORDER_MANAGE} />,
                          children: [
                            { path: 'new', element: <OrderForm /> },
                            { path: ':id/edit', element: <OrderForm /> },
                          ],
                        },
                      ],
                    },
                  ],
                },
                {
                  element: <PrivilegeGuard privilege={[PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CONTRACTOR_MANAGE]} />,
                  children: [
                    {
                      path: 'contractors',
                      element: <ContractorsPage />,
                      children: [
                        { path: ':id', element: <ContractorDetail mode="drawer" /> },
                        {
                          element: <PrivilegeGuard privilege={PRIV.TRIP_CONTRACTOR_MANAGE} />,
                          children: [{ path: 'new', element: <ContractorDetail mode="drawer" /> }],
                        },
                      ],
                    },
                  ],
                },
              ],
            },
            {
              element: <PrivilegeGuard privilege={PRIV.TRIP_TRIP_VIEW} />,
              children: [{ path: `${SCREENS.trips.path}/:id/page`, element: <TripDetail mode="page" /> }],
            },
            {
              element: <PrivilegeGuard privilege={PRIV.TRIP_ORDER_VIEW} />,
              children: [{ path: `${SCREENS.trips.path}/orders/:id/page`, element: <OrderDetail mode="page" /> }],
            },
            {
              element: <PrivilegeGuard privilege={[PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CONTRACTOR_MANAGE]} />,
              children: [{ path: `${SCREENS.trips.path}/contractors/:id/page`, element: <ContractorDetail mode="page" /> }],
            },
          ]),
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
              element: <DriversLayout />,
              children: [
                {
                  // /drivers and the cards; static "absences" / "payments" rank above ":id".
                  element: <DriversPage />,
                  children: [
                    { index: true, element: null },
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
                { path: 'absences', element: <AbsenceCalendarPage /> },
                {
                  element: <PrivilegeGuard privilege={PRIV.DRIVER_PAYMENT_VIEW} />,
                  children: [{ path: 'payments', element: <PaymentsPage /> }],
                },
              ],
            },
            { path: `${SCREENS.drivers.path}/:id/page`, element: <DriverDetail mode="page" /> },
          ]),
          screen('tacho', [
            {
              path: SCREENS.tacho.path,
              element: <TachoLayout />,
              children: [
                { index: true, element: <TachoIndex /> },
                {
                  path: 'files',
                  element: <TachoFilesPage />,
                  children: [{ path: ':id', element: <TachoFileDetail mode="drawer" /> }],
                },
                {
                  element: <PrivilegeGuard privilege={PRIV.TACHO_HOS_VIEW} />,
                  children: [
                    { path: 'activity', element: <DriverActivityPage /> },
                    {
                      path: 'violations',
                      element: <ViolationsPage />,
                      children: [{ path: ':id', element: <ViolationDetail mode="drawer" /> }],
                    },
                  ],
                },
              ],
            },
            { path: `${SCREENS.tacho.path}/files/:id/page`, element: <TachoFileDetail mode="page" /> },
            {
              element: <PrivilegeGuard privilege={PRIV.TACHO_HOS_VIEW} />,
              children: [{ path: `${SCREENS.tacho.path}/violations/:id/page`, element: <ViolationDetail mode="page" /> }],
            },
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
