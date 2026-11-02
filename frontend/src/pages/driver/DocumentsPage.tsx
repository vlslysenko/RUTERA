import { useQuery } from '@tanstack/react-query';
import { Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { useUser } from '@/auth/useAuth';
import { DeadlineChip, NextDownloadChip } from '@/components/DeadlineChip';
import { formatInstantShort } from '@/pages/trips/tripFormat';
import { formatDate } from '@/utils/deadline';
import { Card, PartError } from './DriverParts';
import classes from './DriverApp.module.css';

/** Driver cards are read at least every 28 days (Regulation (EU) 581/2010); a week ahead the chip turns yellow. */
const CARD_DOWNLOAD_DAYS = 28;

/** "Documents": the tachograph card with its next download and the own documents with their deadlines. */
export function DocumentsPage() {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const user = useUser();
  const driverId = user.driver!.id;
  const tz = user.firma?.timezone;
  const driver = useQuery({ queryKey: [...DRIVERS_KEY, String(driverId)], queryFn: () => driversApi.get(driverId), retry: false });
  const documents = useQuery({ queryKey: [...DRIVERS_KEY, String(driverId), 'documents'], queryFn: () => driversApi.documents(driverId), retry: false });
  const card = driver.data?.card;

  return (
    <Stack gap="sm">
      <Card title={t('drivers.card')}>
        {driver.isPending ? (
          <Text size="sm" c="dimmed">…</Text>
        ) : driver.isError ? (
          <PartError error={driver.error} />
        ) : !card ? (
          <Text size="sm" c="dimmed">
            {t('driver_app.no_card')}
          </Text>
        ) : (
          <>
            <div className={classes.row}>
              <span className={classes.mono}>{card.cardNumber}</span>
              {card.validTo && <DeadlineChip validTo={card.validTo} />}
            </div>
            <div className={classes.row}>
              <Text size="sm">
                {t('drivers.card_last_download')}
                <br />
                <Text span size="xs" c="dimmed" className={classes.mono}>
                  {card.lastDownloadAt ? formatInstantShort(card.lastDownloadAt, lang, tz) : '—'}
                </Text>
              </Text>
              <NextDownloadChip lastDownloadAt={card.lastDownloadAt} everyDays={CARD_DOWNLOAD_DAYS} remindDays={7} />
            </div>
          </>
        )}
      </Card>
      <Card title={t('drivers.tab.documents')}>
        {documents.isPending ? (
          <Text size="sm" c="dimmed">…</Text>
        ) : documents.isError ? (
          <PartError error={documents.error} />
        ) : documents.data.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('driver_app.no_documents')}
          </Text>
        ) : (
          documents.data.map((d) => (
            <div key={d.id} className={classes.row}>
              <div style={{ minWidth: 0 }}>
                <Text size="sm" fw={600}>
                  {t(`driver.document.${d.kind}`)}
                </Text>
                <Text size="xs" c="dimmed" className={classes.mono}>
                  {[d.number, d.validTo && formatDate(d.validTo, lang)].filter(Boolean).join(' · ') || '—'}
                </Text>
              </div>
              {d.validTo && <DeadlineChip validTo={d.validTo} remindDays={d.remindDays} />}
            </div>
          ))
        )}
      </Card>
    </Stack>
  );
}
