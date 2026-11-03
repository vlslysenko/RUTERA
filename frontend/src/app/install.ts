import { useSyncExternalStore } from 'react';

/*
 * "Install the app" for the PWA. Chrome and Edge fire beforeinstallprompt once the app qualifies; it is kept here
 * (this module is imported at start, before the event can fire) and offered later from the driver cabinet.
 * Safari on iPhone has no prompt: the cabinet shows the "Share -> Add to Home Screen" hint instead.
 */

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: BeforeInstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferred = e as BeforeInstallPromptEvent;
    notify();
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    notify();
  });
}

function isStandalone(): boolean {
  return (
    window.matchMedia?.('(display-mode: standalone)').matches === true ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

function isIos(): boolean {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

export interface InstallState {
  /** Running as the installed app already. */
  standalone: boolean;
  /** The browser offered installation (Chrome, Edge). */
  canPrompt: boolean;
  /** iPhone / iPad Safari: installation only through Share -> Add to Home Screen. */
  ios: boolean;
}

export function useInstall(): InstallState & { install: () => Promise<void> } {
  const canPrompt = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      return () => listeners.delete(onChange);
    },
    () => deferred !== null,
  );
  const install = async () => {
    const event = deferred;
    if (!event) return;
    await event.prompt();
    await event.userChoice;
    deferred = null;
    notify();
  };
  return { standalone: isStandalone(), canPrompt, ios: isIos(), install };
}
