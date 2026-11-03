import type { ReactNode } from 'react';
import type { FetchStatus } from '@tanstack/react-query';
import { Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { OfflineNote } from '@/components/Offline';
import classes from './DriverApp.module.css';

/** A white block of the driver cabinet with an optional caption. */
export function Card({ title, children, right }: { title?: string; children: ReactNode; right?: ReactNode }) {
  return (
    <section className={classes.card}>
      {(title || right) && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
          {title && <h2 className={classes.cardTitle}>{title}</h2>}
          {right}
        </div>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className={classes.stat}>
      <span className={classes.statLabel}>{label}</span>
      <span className={classes.statValue}>{value}</span>
    </div>
  );
}

/**
 * A part the driver may not see (403) or that has no data yet (404) is left out quietly; other errors are shown,
 * so a broken connection is not mistaken for "nothing there".
 */
export function PartError({ error }: { error: unknown }) {
  const { t } = useTranslation();
  // No answer at all (the phone thinks it is online, but the request does not get through).
  if (error instanceof ApiError && error.status === 0) return <OfflineNote text={t('error.network')} />;
  if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
    return (
      <Text size="sm" c="dimmed">
        {t('driver_app.no_data')}
      </Text>
    );
  }
  return (
    <Text size="sm" c="red">
      {t(errorCode(error))}
    </Text>
  );
}

/** Placeholder of a part that is loading; offline (the query waits for the connection) it says so instead. */
export function Waiting({ query }: { query: { fetchStatus: FetchStatus } }) {
  if (query.fetchStatus === 'paused') return <OfflineNote />;
  return (
    <Text size="sm" c="dimmed">
      …
    </Text>
  );
}
