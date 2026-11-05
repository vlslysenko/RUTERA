import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Anchor, Button, Group, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFileText } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { CMR_KEY, cmrApi } from '@/api/cmr';
import { hasAnyPrivilege, hasPrivilege, PRIV } from '@/auth/privileges';
import { useUser } from '@/auth/useAuth';
import { CmrStatusChip } from './TripChips';
import classes from './Trips.module.css';

/**
 * CMR notes of an order or a trip in its card, with "create CMR": from an order one prefilled draft (opened in the
 * editor), from a trip one draft per order (a single one opens in the editor, several in the list of the trip).
 */
export function CmrLinks({ orderId, tripId, canCreate = true }: { orderId?: number; tripId?: number; canCreate?: boolean }) {
  const { t } = useTranslation();
  const me = useUser();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const canRead = hasAnyPrivilege(me, [PRIV.TRIP_ORDER_VIEW, PRIV.TRIP_CMR_MANAGE]);
  const canManage = hasPrivilege(me, PRIV.TRIP_CMR_MANAGE);
  const filter = { orderId, tripId, size: 20 };
  const q = useQuery({ queryKey: [...CMR_KEY, 'list', filter], queryFn: () => cmrApi.list(filter), enabled: canRead });
  const scope = orderId != null ? `order=${orderId}` : `trip=${tripId}`;

  if (!canRead) return null;

  const create = async () => {
    setBusy(true);
    try {
      const made = orderId != null ? [await cmrApi.fromOrder(orderId)] : await cmrApi.fromTrip(tripId!);
      void qc.invalidateQueries({ queryKey: [...CMR_KEY, 'list'] });
      notifications.show({ color: 'green', message: t('cmr.drafts_created', { numbers: made.map((c) => c.number).join(', ') }) });
      await navigate(made.length === 1 ? `/trips/cmr/${made[0].id}/edit` : `/trips/cmr?${scope}`);
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    } finally {
      setBusy(false);
    }
  };

  const items = q.data?.items ?? [];
  return (
    <section className={classes.section}>
      <Group justify="space-between">
        <h3 className={classes.sectionTitle}>CMR</h3>
        {canManage && canCreate && (
          <Tooltip label={t('hint.action.cmr_create')}>
            <Button size="xs" variant="light" leftSection={<IconFileText size={14} />} onClick={() => void create()} loading={busy}>
              {t('cmr.create')}
            </Button>
          </Tooltip>
        )}
      </Group>
      {q.isError ? (
        <Text size="sm" c="red">
          {t(errorCode(q.error))}
        </Text>
      ) : items.length === 0 ? (
        <Text size="sm" c="dimmed">
          {q.isPending ? '…' : t('cmr.none')}
        </Text>
      ) : (
        <Stack gap={4}>
          {items.map((c) => (
            <Group key={c.id} gap={8} wrap="nowrap">
              <Anchor component={Link} to={`/trips/cmr/${c.id}`} className={classes.mono} size="sm">
                {c.number}
              </Anchor>
              <CmrStatusChip status={c.status} />
              <Text size="sm" c="dimmed" truncate>
                {c.consignee?.name ?? c.deliveryPlace ?? ''}
              </Text>
            </Group>
          ))}
          {q.data && q.data.total > items.length && (
            <Anchor component={Link} to={`/trips/cmr?${scope}`} size="sm">
              {t('cmr.all', { n: q.data.total })}
            </Anchor>
          )}
        </Stack>
      )}
    </section>
  );
}
