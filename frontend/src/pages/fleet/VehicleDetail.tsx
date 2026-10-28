import { useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { fleetApi } from '@/api/fleet';
import { DetailDrawer } from '@/components/DetailDrawer';
import { ErrorState } from '@/components/ErrorState';
import { KeyValueList } from '@/components/KeyValueList';
import { Plate } from '@/components/Plate';
import { VehicleStatusChip } from './VehicleStatusChip';

/** Vehicle card: the same component renders in the right panel (/fleet/:id) and as a page (/fleet/:id/page). */
export function VehicleDetail({ mode }: { mode: 'drawer' | 'page' }) {
  const { t, i18n } = useTranslation();
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['fleet', 'vehicles', id], queryFn: () => fleetApi.get(id), enabled: id !== '' });
  const v = q.data;
  const km = new Intl.NumberFormat(i18n.resolvedLanguage ?? 'en');

  return (
    <DetailDrawer
      mode={mode}
      listPath="/fleet"
      pagePath={`/fleet/${id}/page`}
      title={v ? <Plate size="lg">{v.plate}</Plate> : '…'}
      subtitle={v?.model}
      status={v && <VehicleStatusChip status={v.status} />}
      loading={q.isPending}
    >
      {q.isError ? (
        <ErrorState message={t(errorCode(q.error))} onRetry={() => void q.refetch()} />
      ) : v ? (
        <KeyValueList
          items={[
            { label: t('fleet.plate'), value: v.plate },
            { label: t('fleet.model'), value: v.model },
            { label: t('fleet.status'), value: <VehicleStatusChip status={v.status} /> },
            { label: t('fleet.driver'), value: v.driver ?? t('fleet.no_driver') },
            { label: t('fleet.odometer'), value: `${km.format(v.odometerKm)} km` },
          ]}
        />
      ) : null}
    </DetailDrawer>
  );
}
