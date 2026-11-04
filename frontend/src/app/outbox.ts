import { useEffect, useState } from 'react';
import { ApiError } from '@/api/client';
import { hrApi, type AbsenceInput } from '@/api/hr';
import { tripsApi, type TripFileKind } from '@/api/trips';

/*
 * Offline outbox of the driver cabinet: trip files (photos) and absence requests made without a connection wait in
 * IndexedDB and are sent when it is back: by the page (start, reconnect, return to the app, "Send now") and, where the
 * browser has Background Sync, by the service worker even with the app closed (public/sw-extra.js, same database).
 *
 * An item is claimed in one IndexedDB transaction before it is sent, so the page and the service worker never send it
 * twice at the same time; a claim older than two minutes counts as lost. Every try carries the item id as
 * Idempotency-Key, so a replay after a lost answer creates nothing twice on the backend. Items belong to the user who
 * made them and are only sent in that user's session.
 *
 * Keep DB_NAME, STORE, the item shape and the error rules in sync with public/sw-extra.js.
 */

const DB_NAME = 'rutera-outbox';
const STORE = 'items';
const CHANNEL = 'rutera-outbox';
export const SYNC_TAG = 'rutera-outbox';
const STALE_CLAIM_MS = 2 * 60_000;

export type OutboxState = 'queued' | 'sending' | 'failed';

interface OutboxBase {
  /** Also the Idempotency-Key of every try. */
  id: string;
  userId: number;
  createdAt: number;
  state: OutboxState;
  attempts: number;
  /** i18n code of the last error (failed: why it was refused; queued: why it is still waiting). */
  error: string | null;
  claimedAt: number | null;
}

export interface TripFileItem extends OutboxBase {
  type: 'trip_file';
  tripId: number;
  tripNumber: string;
  kind: TripFileKind;
  note: string | null;
  stopId: number | null;
  file: Blob;
  fileName: string;
  fileType: string;
}

export interface AbsenceItem extends OutboxBase {
  type: 'absence';
  driverId: number;
  body: AbsenceInput;
}

export type OutboxItem = TripFileItem | AbsenceItem;
export type NewOutboxItem = Omit<TripFileItem, keyof OutboxBase> | Omit<AbsenceItem, keyof OutboxBase>;

/** The state to show: a claim older than two minutes was lost (closed page, crash) and waits again. */
export function shownState(item: OutboxItem, now = Date.now()): OutboxState {
  return item.state === 'sending' && (item.claimedAt ?? 0) < now - STALE_CLAIM_MS ? 'queued' : item.state;
}

// --- IndexedDB -------------------------------------------------------------------------------------------

let dbPromise: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE, { keyPath: 'id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => {
      dbPromise = null;
      reject(req.error);
    };
  });
  return dbPromise;
}

/** Runs `work` in one transaction and resolves with what it hands to `done` once the transaction has committed. */
async function inTx<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore, done: (value: T) => void) => void): Promise<T> {
  const database = await db();
  return new Promise<T>((resolve, reject) => {
    const tx = database.transaction(STORE, mode);
    let result: T;
    work(tx.objectStore(STORE), (value) => (result = value));
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

function allItems(): Promise<OutboxItem[]> {
  return inTx<OutboxItem[]>('readonly', (store, done) => {
    const req = store.getAll();
    req.onsuccess = () => done((req.result as OutboxItem[]).sort((a, b) => a.createdAt - b.createdAt));
  });
}

function putItem(item: OutboxItem): Promise<void> {
  return inTx<void>('readwrite', (store, done) => {
    store.put(item);
    done(undefined);
  });
}

function deleteItem(id: string): Promise<void> {
  return inTx<void>('readwrite', (store, done) => {
    store.delete(id);
    done(undefined);
  });
}

/** Marks the item as being sent, unless someone else is sending it right now; null when it is not to be sent. */
function claim(id: string): Promise<OutboxItem | null> {
  return inTx<OutboxItem | null>('readwrite', (store, done) => {
    const req = store.get(id);
    req.onsuccess = () => {
      const item = req.result as OutboxItem | undefined;
      const now = Date.now();
      const free = item && (item.state === 'queued' || (item.state === 'sending' && (item.claimedAt ?? 0) < now - STALE_CLAIM_MS));
      if (!item || !free) {
        done(null);
        return;
      }
      const claimed: OutboxItem = { ...item, state: 'sending', claimedAt: now };
      store.put(claimed);
      done(claimed);
    };
  });
}

// --- change notification (this tab, other tabs, the service worker) ---------------------------------------

const listeners = new Set<() => void>();
const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL) : null;
/** Called for items sent by another tab or the service worker, so the screens can refresh. */
let sentElsewhere: ((item: Pick<OutboxItem, 'type'> & { tripId?: number; driverId?: number }) => void) | null = null;

channel?.addEventListener('message', (e: MessageEvent<{ type: string; item?: Pick<OutboxItem, 'type'> & { tripId?: number; driverId?: number } }>) => {
  if (e.data?.type === 'sent' && e.data.item) sentElsewhere?.(e.data.item);
  listeners.forEach((l) => l());
});

function changed(sent?: OutboxItem) {
  listeners.forEach((l) => l());
  channel?.postMessage(sent ? { type: 'sent', item: { type: sent.type, tripId: 'tripId' in sent ? sent.tripId : undefined, driverId: 'driverId' in sent ? sent.driverId : undefined } } : { type: 'changed' });
}

export function onOutboxSentElsewhere(handler: typeof sentElsewhere) {
  sentElsewhere = handler;
}

/** Items of the user, oldest first, kept up to date. */
export function useOutbox(userId: number | undefined): OutboxItem[] {
  const [items, setItems] = useState<OutboxItem[]>([]);
  useEffect(() => {
    if (userId === undefined || typeof indexedDB === 'undefined') return;
    let alive = true;
    const load = () =>
      void allItems()
        .then((all) => alive && setItems(all.filter((i) => i.userId === userId)))
        .catch(() => alive && setItems([]));
    load();
    listeners.add(load);
    return () => {
      alive = false;
      listeners.delete(load);
    };
  }, [userId]);
  return items;
}

// --- queue, send ---------------------------------------------------------------------------------------

export function newOutboxId(): string {
  return crypto.randomUUID();
}

/** Asks the service worker to send the outbox when the connection is back (Background Sync, Chromium only). */
function requestSync() {
  if (!('serviceWorker' in navigator)) return;
  void navigator.serviceWorker.ready
    .then((reg) => (reg as ServiceWorkerRegistration & { sync?: { register(tag: string): Promise<void> } }).sync?.register(SYNC_TAG))
    .catch(() => {
      // no Background Sync: the page sends on reconnect and on return to the app
    });
}

/** Keeps a request for later; `id` is the Idempotency-Key already used by a first try, if there was one. */
export async function enqueue(userId: number, item: NewOutboxItem, id = newOutboxId()): Promise<void> {
  const full = { ...item, id, userId, createdAt: Date.now(), state: 'queued', attempts: 0, error: null, claimedAt: null } as OutboxItem;
  await putItem(full);
  changed();
  requestSync();
}

export async function removeFromOutbox(id: string): Promise<void> {
  await deleteItem(id);
  changed();
}

/** A refused item goes back to the queue (after the reason was fixed, e.g. the trip reopened). */
export async function retryOutboxItem(item: OutboxItem): Promise<void> {
  await putItem({ ...item, state: 'queued', claimedAt: null, error: null });
  changed();
  requestSync();
}

/** Drops everything the user still has waiting (sign-out on a shared phone). */
export async function clearOutbox(userId: number): Promise<void> {
  const all = await allItems().catch(() => [] as OutboxItem[]);
  await Promise.all(all.filter((i) => i.userId === userId).map((i) => deleteItem(i.id)));
  changed();
}

/** A network-level failure: the item waits for the next try. */
export function isTransient(e: unknown): boolean {
  if (!(e instanceof ApiError)) return true;
  return (
    e.status === 0 ||
    e.status === 408 ||
    e.status === 429 ||
    e.status >= 500 ||
    (e.status === 403 && e.code === 'auth.csrf_invalid') ||
    // The first try with the same Idempotency-Key is still running on the server.
    (e.status === 409 && e.code === 'request.in_progress')
  );
}

function send(item: OutboxItem) {
  if (item.type === 'trip_file') {
    const file = new File([item.file], item.fileName, { type: item.fileType });
    return tripsApi.uploadFile(item.tripId, file, item.kind, item.note, item.stopId, item.id);
  }
  return hrApi.createAbsence(item.driverId, item.body, item.id);
}

let replaying = false;

/**
 * Sends the user's waiting items, oldest first. Stops at the first network-level failure (still offline) or a 401
 * (signed out: the items wait for the next sign-in of that user). A refusal (422, 403, 404, 409...) marks the item
 * failed with its reason. Returns the items sent.
 */
export async function replayOutbox(userId: number): Promise<OutboxItem[]> {
  if (replaying || typeof indexedDB === 'undefined') return [];
  replaying = true;
  const sent: OutboxItem[] = [];
  try {
    const waiting = (await allItems()).filter((i) => i.userId === userId && i.state !== 'failed');
    for (const item of waiting) {
      const claimed = await claim(item.id);
      if (!claimed) continue;
      changed();
      try {
        await send(claimed);
        await deleteItem(claimed.id);
        sent.push(claimed);
        changed(claimed);
      } catch (e) {
        const code = e instanceof ApiError ? (e.details ? Object.values(e.details)[0] : e.code) : 'error.network';
        const transient = isTransient(e) || (e instanceof ApiError && e.status === 401);
        await putItem({ ...claimed, state: transient ? 'queued' : 'failed', claimedAt: null, attempts: claimed.attempts + 1, error: code });
        changed();
        if (transient) break;
      }
    }
  } finally {
    replaying = false;
  }
  return sent;
}
