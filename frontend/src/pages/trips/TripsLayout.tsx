import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { Tabs } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { hasAnyPrivilege, hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { PageHeader } from '@/components/PageHeader';

/** Trips section: trips, orders and contractors as route tabs, each shown only with its privilege. */
export function TripsLayout() {
  const { t } = useTranslation();
  const user = useUser();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  const tabs = [
    ...(hasPrivilege(user, PRIV.TRIP_TRIP_VIEW) ? [{ value: '/trips', label: t('trips.tab.trips') }] : []),
    ...(hasPrivilege(user, PRIV.TRIP_ORDER_VIEW) ? [{ value: '/trips/orders', label: t('trips.tab.orders') }] : []),
    ...(hasAnyPrivilege(user, [PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CONTRACTOR_MANAGE])
      ? [{ value: '/trips/contractors', label: t('trips.tab.contractors') }]
      : []),
  ];
  // The trips tab owns /trips and /trips/:id; the others own their prefix.
  const active =
    tabs.find((tab) => tab.value !== '/trips' && (pathname === tab.value || pathname.startsWith(`${tab.value}/`)))?.value ??
    (pathname.startsWith('/trips') ? '/trips' : null);

  return (
    <>
      <PageHeader title={t('nav.trips')} />
      <Tabs value={active} onChange={(v) => v && void navigate(v)} mb="md">
        <Tabs.List>
          {tabs.map((tab) => (
            <Tabs.Tab key={tab.value} value={tab.value}>
              {tab.label}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      <Outlet />
    </>
  );
}

/** Users without trip.trip/view land on the first tab they may open. */
export function TripsFallback() {
  const user = useUser();
  return <Navigate to={hasPrivilege(user, PRIV.TRIP_ORDER_VIEW) ? '/trips/orders' : '/trips/contractors'} replace />;
}
