/*
 * Additions to the generated service worker (vite.config.ts -> workbox.importScripts). Plain JS, not bundled.
 *
 * 1. Background Sync "rutera-outbox": sends the offline outbox of the driver cabinet (trip files, absence requests)
 *    when the connection is back, also with the app closed. Same IndexedDB database, item shape and error rules as
 *    src/app/outbox.ts; keep both in sync. Only items of the user of the current session are sent; without a session,
 *    or without a readable CSRF cookie (no cookieStore), the page sends them later.
 * 2. Web Push: shows {title, body, url, tag} sent by the backend and opens or focuses RUTERA on a click.
 */

const OUTBOX_DB = 'rutera-outbox';
const OUTBOX_STORE = 'items';
const OUTBOX_CHANNEL = 'rutera-outbox';
const OUTBOX_SYNC_TAG = 'rutera-outbox';
const STALE_CLAIM_MS = 2 * 60 * 1000;

function openOutbox() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(OUTBOX_DB, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(OUTBOX_STORE)) req.result.createObjectStore(OUTBOX_STORE, { keyPath: 'id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function inTx(db, mode, work) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(OUTBOX_STORE, mode);
    let result;
    work(tx.objectStore(OUTBOX_STORE), (value) => (result = value));
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

const allItems = (db) =>
  inTx(db, 'readonly', (store, done) => {
    const req = store.getAll();
    req.onsuccess = () => done(req.result.sort((a, b) => a.createdAt - b.createdAt));
  });

const putItem = (db, item) =>
  inTx(db, 'readwrite', (store, done) => {
    store.put(item);
    done(undefined);
  });

const deleteItem = (db, id) =>
  inTx(db, 'readwrite', (store, done) => {
    store.delete(id);
    done(undefined);
  });

/** Marks the item as being sent unless someone else is sending it right now (same rule as the page). */
const claim = (db, id) =>
  inTx(db, 'readwrite', (store, done) => {
    const req = store.get(id);
    req.onsuccess = () => {
      const item = req.result;
      const now = Date.now();
      const free = item && (item.state === 'queued' || (item.state === 'sending' && (item.claimedAt || 0) < now - STALE_CLAIM_MS));
      if (!free) return done(null);
      const claimed = { ...item, state: 'sending', claimedAt: now };
      store.put(claimed);
      done(claimed);
    };
  });

function broadcast(message) {
  try {
    const channel = new BroadcastChannel(OUTBOX_CHANNEL);
    channel.postMessage(message);
    channel.close();
  } catch {
    // no BroadcastChannel: open pages reload the outbox on their next look
  }
}

function request(item, csrf) {
  const headers = { Accept: 'application/json', 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': item.id };
  if (item.type === 'trip_file') {
    const form = new FormData();
    form.append('file', new File([item.file], item.fileName, { type: item.fileType }));
    form.append('kind', item.kind);
    if (item.note) form.append('note', item.note);
    if (item.stopId != null) form.append('stopId', String(item.stopId));
    return fetch(`/app/trips/${item.tripId}/files`, { method: 'POST', credentials: 'same-origin', headers, body: form });
  }
  return fetch(`/app/drivers/${item.driverId}/absences`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify(item.body),
  });
}

async function errorCode(res) {
  try {
    const body = await res.json();
    if (body && body.details) return Object.values(body.details)[0];
    return (body && body.code) || `HTTP ${res.status}`;
  } catch {
    return `HTTP ${res.status}`;
  }
}

/** Sends what waits; throws when something has to be tried again later, so the browser repeats the sync. */
async function replayOutbox() {
  const db = await openOutbox();
  const waiting = (await allItems(db)).filter((i) => i.state !== 'failed');
  if (waiting.length === 0) return;

  const me = await fetch('/app/auth/me', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  if (me.status !== 200) return; // signed out: the page sends after the next sign-in of that user
  const user = await me.json();
  const cookie = self.cookieStore ? await self.cookieStore.get('XSRF-TOKEN') : null;
  if (!user || !user.id || !cookie) return; // the page has the CSRF cookie and sends on its next start
  const csrf = decodeURIComponent(cookie.value);

  let retryLater = false;
  for (const item of waiting.filter((i) => i.userId === user.id)) {
    const claimed = await claim(db, item.id);
    if (!claimed) continue;
    let res;
    try {
      res = await request(claimed, csrf);
    } catch {
      await putItem(db, { ...claimed, state: 'queued', claimedAt: null, attempts: claimed.attempts + 1, error: 'error.network' });
      retryLater = true;
      break;
    }
    if (res.ok) {
      await deleteItem(db, claimed.id);
      broadcast({ type: 'sent', item: { type: claimed.type, tripId: claimed.tripId, driverId: claimed.driverId } });
      continue;
    }
    const code = await errorCode(res);
    const transient = res.status === 401 || res.status === 408 || res.status === 429 || res.status >= 500 || (res.status === 403 && code === 'auth.csrf_invalid');
    await putItem(db, { ...claimed, state: transient ? 'queued' : 'failed', claimedAt: null, attempts: claimed.attempts + 1, error: code });
    broadcast({ type: 'changed' });
    if (transient) {
      retryLater = res.status !== 401;
      break;
    }
  }
  broadcast({ type: 'changed' });
  if (retryLater) throw new Error('outbox: try again later');
}

self.addEventListener('sync', (event) => {
  if (event.tag === OUTBOX_SYNC_TAG) event.waitUntil(replayOutbox());
});

// --- Web Push ------------------------------------------------------------------------------------------

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { body: event.data ? event.data.text() : '' };
  }
  const title = data.title || 'RUTERA';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || '',
      tag: data.tag || undefined,
      renotify: Boolean(data.tag),
      icon: '/pwa-192x192.png',
      badge: '/pwa-192x192.png',
      data: { url: typeof data.url === 'string' && data.url.startsWith('/') ? data.url : '/' },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => new URL(w.url).origin === self.location.origin);
      if (open) return open.focus().then((w) => (w && 'navigate' in w ? w.navigate(url) : w));
      return self.clients.openWindow(url);
    }),
  );
});
