import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Anchor, Button, Group, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPencil, IconRoute, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { ORDERS_KEY, ordersApi, type Order } from '@/api/trips';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList, type KeyValueItem } from '@/components/KeyValueList';
import { countryName } from '@/utils/countries';
import { formatDate } from '@/utils/deadline';
import { CmrLinks } from './CmrLinks';
import { CreateTripPanel } from './CreateTripPanel';
import { OrderStatusChip, TripStatusChip } from './TripChips';
import { formatMoney } from './tripFormat';
import classes from './Trips.module.css';

const LIST = '/trips/orders';
const DASH = '—';

function Section({ title, items }: { title: string; items: KeyValueItem[] }) {
  return (
    <section className={classes.section}>
      <h3 className={classes.sectionTitle}>{title}</h3>
      <KeyValueList items={items} />
    </section>
  );
}

function Overview({ o }: { o: Order }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const me = useUser();
  const date = (iso: string | null) => (iso ? formatDate(iso, lang) : DASH);
  const contractorLink = hasPrivilege(me, PRIV.TRIP_ORDER_VIEW) || hasPrivilege(me, PRIV.TRIP_CONTRACTOR_MANAGE);

  const order: KeyValueItem[] = [
    { label: t('orders.date'), value: date(o.orderDate) },
    {
      label: t('orders.customer'),
      value: contractorLink ? (
        <Anchor component={Link} to={`/trips/contractors/${o.customer.id}`}>
          {o.customer.name}
        </Anchor>
      ) : (
        o.customer.name
      ),
    },
    { label: t('orders.customer_ref'), value: o.customerRef ? <span className={classes.mono}>{o.customerRef}</span> : DASH },
  ];
  const route: KeyValueItem[] = [
    { label: t('orders.loading'), value: `${o.loading.place}, ${countryName(o.loading.country, lang)}` },
    { label: t('orders.loading_date'), value: date(o.loading.date) },
    { label: t('orders.unloading'), value: `${o.unloading.place}, ${countryName(o.unloading.country, lang)}` },
    { label: t('orders.unloading_date'), value: date(o.unloading.date) },
  ];
  const cargo: KeyValueItem[] = [
    { label: t('orders.price'), value: formatMoney(o.price, o.currency, lang) },
    { label: t('orders.cargo'), value: o.cargo ?? DASH },
    { label: t('orders.weight_kg'), value: o.weightKg != null ? new Intl.NumberFormat(lang).format(o.weightKg) : DASH },
  ];

  return (
    <Stack gap="lg">
      <Section title={t('orders.section.order')} items={order} />
      <Section title={t('orders.section.route')} items={route} />
      <Section title={t('orders.section.cargo')} items={cargo} />
      {o.notes && (
        <section className={classes.section}>
          <h3 className={classes.sectionTitle}>{t('drivers.notes')}</h3>
          <Text size="sm" className={classes.notes}>
            {o.notes}
          </Text>
        </section>
      )}
    </Stack>
  );
}

/** Order card: details, its trip or "create trip"; the same component renders in the right panel and as a page. */
export function OrderDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: [...ORDERS_KEY, id], queryFn: () => ordersApi.get(id), enabled: id !== '' });
  const o = q.data;
  const canManage = hasPrivilege(me, PRIV.TRIP_ORDER_MANAGE);
  const canPlan = canManage && hasPrivilege(me, PRIV.TRIP_TRIP_MANAGE);
  const canSeeTrip = hasPrivilege(me, PRIV.TRIP_TRIP_VIEW);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [planning, setPlanning] = useState(false);
  const [busy, setBusy] = useState(false);
  // The drawer stays mounted when another row is clicked: close the panels of the previous order.
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setPlanning(false);
    setConfirmDelete(false);
  }

  const remove = async () => {
    setBusy(true);
    try {
      await ordersApi.remove(id);
      notifications.show({ color: 'green', message: t('orders.deleted') });
      await navigate(LIST, { replace: true });
      void qc.invalidateQueries({ queryKey: ORDERS_KEY, predicate: (x) => x.queryKey[ORDERS_KEY.length] !== id });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
      setConfirmDelete(false);
    } finally {
      setBusy(false);
    }
  };

  // On a trip or invoiced the backend answers 409; the button is not offered then.
  const deletable = o && !o.trip && o.status !== 'INVOICED';
  const plannable = o && !o.trip && o.status === 'NEW';
  const actions =
    o && canManage ? (
      <>
        {plannable && canPlan && (
          <Button size="xs" leftSection={<IconRoute size={14} />} onClick={() => setPlanning(true)} disabled={busy || planning}>
            {t('orders.create_trip')}
          </Button>
        )}
        <Button variant="default" size="xs" leftSection={<IconPencil size={14} />} onClick={() => void navigate(`${LIST}/${id}/edit`)} disabled={busy}>
          {t('common.edit')}
        </Button>
        {deletable && (
          <Button variant="default" size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => setConfirmDelete(true)} disabled={busy}>
            {t('common.delete')}
          </Button>
        )}
      </>
    ) : undefined;

  return (
    <DetailDrawer
      mode={mode}
      listPath={LIST}
      pagePath={`${LIST}/${id}/page`}
      title={o ? <span className={classes.mono}>{o.number}</span> : '…'}
      subtitle={o?.customer.name}
      status={o && <OrderStatusChip status={o.status} />}
      actions={actions}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : o ? (
        <Stack gap="md">
          {confirmDelete && (
            <Alert color="red" variant="light" p="sm">
              <Stack gap="xs">
                <Text size="sm">{t('orders.delete_confirm')}</Text>
                <Group gap="xs">
                  <Button size="xs" color="red" onClick={() => void remove()} loading={busy}>
                    {t('common.confirm')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setConfirmDelete(false)} disabled={busy}>
                    {t('common.cancel')}
                  </Button>
                </Group>
              </Stack>
            </Alert>
          )}
          {o.trip && (
            <Group gap={8} wrap="wrap">
              <Text size="sm" c="dimmed">
                {t('orders.trip')}:
              </Text>
              {canSeeTrip ? (
                <Anchor component={Link} to={`/trips/${o.trip.id}`} className={classes.mono}>
                  {o.trip.number}
                </Anchor>
              ) : (
                <span className={classes.mono}>{o.trip.number}</span>
              )}
              <TripStatusChip status={o.trip.status} />
            </Group>
          )}
          {planning && plannable && <CreateTripPanel order={o} onClose={() => setPlanning(false)} />}
          <Overview o={o} />
          <CmrLinks orderId={o.id} canCreate={o.status !== 'CANCELLED'} />
        </Stack>
      ) : null}
    </DetailDrawer>
  );
}
