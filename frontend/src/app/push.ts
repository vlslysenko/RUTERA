import { api } from '@/api/client';

/*
 * Web Push (backend contract of item 7, /app/push; VAPID, no paid service): the driver cabinet, and the settings of
 * owners and dispatchers for reminders of vehicle documents. The service worker shows the notifications
 * (public/sw-extra.js); this module subscribes the device and binds it to the signed-in user.
 * GET /app/push/key -> {publicKey} | 404 push.disabled; POST/DELETE /app/push/subscriptions {endpoint, keys};
 * POST /app/push/test -> {sent, failed}. A subscription follows the user who sent it last (upsert by endpoint).
 */

export interface PushSubscriptionBody {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}

export const pushApi = {
  key: () => api<{ publicKey: string }>('/push/key'),
  subscribe: (body: PushSubscriptionBody) => api<void>('/push/subscriptions', { method: 'POST', body }),
  unsubscribe: (endpoint: string) => api<void>('/push/subscriptions', { method: 'DELETE', body: { endpoint } }),
  test: () => api<{ sent: number; failed: number }>('/push/test', { method: 'POST' }),
};

export const PUSH_KEY = ['push', 'key'] as const;

/** The browser can do Web Push at all (Safari on iPhone only for an installed app). */
export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

/** The active service worker registration; none in the dev server (the service worker runs in builds only). */
export async function pushRegistration(): Promise<ServiceWorkerRegistration | undefined> {
  if (!pushSupported()) return undefined;
  const reg = await navigator.serviceWorker.getRegistration();
  return reg?.active ? reg : undefined;
}

function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = (base64url + '='.repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function sameKey(a: ArrayBuffer | null, b: Uint8Array): boolean {
  if (!a || a.byteLength !== b.length) return false;
  const x = new Uint8Array(a);
  return x.every((v, i) => v === b[i]);
}

function body(sub: PushSubscription): PushSubscriptionBody {
  const json = sub.toJSON();
  return { endpoint: sub.endpoint, keys: { p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' } };
}

/** The device's subscription, if push is on for it. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  const reg = await pushRegistration();
  return reg ? reg.pushManager.getSubscription() : null;
}

/**
 * Turns push on for this device: permission, a subscription with the server's key (a subscription made with an older
 * key is replaced), then the server binds it to the signed-in user. 'denied' when the user or the browser said no.
 */
export async function enablePush(publicKey: string): Promise<'on' | 'denied'> {
  const reg = await pushRegistration();
  if (!reg) throw new Error('no service worker');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return 'denied';
  const key = keyBytes(publicKey);
  let sub = await reg.pushManager.getSubscription();
  if (sub && !sameKey(sub.options.applicationServerKey, key)) {
    await sub.unsubscribe();
    sub = null;
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await pushApi.subscribe(body(sub));
  return 'on';
}

/** Turns push off for this device (the server forgets it, the browser drops the subscription). Never throws. */
export async function disablePush(): Promise<void> {
  try {
    const sub = await currentSubscription();
    if (!sub) return;
    await pushApi.unsubscribe(sub.endpoint).catch(() => undefined);
    await sub.unsubscribe();
  } catch {
    // nothing to undo
  }
}

/**
 * On start of the cabinet or the office app: a device that already has a subscription is bound to the user signed in
 * now (someone else may have used it before). Never throws.
 */
export async function syncPush(): Promise<void> {
  try {
    if (!pushSupported() || Notification.permission !== 'granted') return;
    const sub = await currentSubscription();
    if (sub) await pushApi.subscribe(body(sub));
  } catch {
    // offline or push off on the server: next start
  }
}
