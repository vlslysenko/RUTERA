import { dehydrate, hydrate, type DehydratedState, type QueryClient } from '@tanstack/react-query';
import type { User } from './types';
import { usesDriverApp } from './navigation';

/*
 * Offline data of the driver cabinet: the last answers of the session and the cabinet's queries are kept in
 * localStorage, so the phone shows the trip, hours and documents without a connection (and after a reload on the
 * road). Only for drivers who use the cabinet; office users keep nothing. Cleared on sign-out and on a new sign-in.
 * Stale data refetches as soon as the connection is back (TanStack Query refetchOnReconnect).
 */

const STORAGE_KEY = 'rutera.offline.v1';
const MAX_AGE_MS = 7 * 86_400_000;
/** First segments of the query keys the cabinet reads: session, trips, tachograph, the driver, HR. */
const ROOTS = new Set(['auth', 'trips', 'tacho', 'drivers', 'hr']);

interface Saved {
  userId: number;
  savedAt: number;
  state: DehydratedState;
}

function read(): Saved | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Saved) : null;
  } catch {
    return null;
  }
}

export function clearOfflineCache(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // storage unavailable: nothing was kept
  }
}

function save(client: QueryClient, meKey: readonly unknown[]): void {
  const me = client.getQueryData<User | null>(meKey);
  if (!me || !usesDriverApp(me)) {
    clearOfflineCache();
    return;
  }
  const state = dehydrate(client, {
    shouldDehydrateQuery: (q) => q.state.status === 'success' && ROOTS.has(String(q.queryKey[0])),
  });
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ userId: me.id, savedAt: Date.now(), state } satisfies Saved));
  } catch {
    // quota or private mode: the cabinet still works online
  }
}

let started = false;

/** Restores the saved data (before the first render) and keeps it up to date while the app runs. */
export function startOfflineCache(client: QueryClient, meKey: readonly unknown[]): void {
  if (started) return;
  started = true;
  const saved = read();
  if (saved && Date.now() - saved.savedAt < MAX_AGE_MS) hydrate(client, saved.state);
  else if (saved) clearOfflineCache();

  let timer: ReturnType<typeof setTimeout> | undefined;
  client.getQueryCache().subscribe((event) => {
    if (event.type !== 'updated' && event.type !== 'removed') return;
    if (event.type === 'updated' && event.action.type !== 'success') return;
    clearTimeout(timer);
    timer = setTimeout(() => save(client, meKey), 1000);
  });
}
