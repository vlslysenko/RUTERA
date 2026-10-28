import { createContext } from 'react';

/** Lets a detail route render its card into the AppShell aside (right panel) of AppLayout. */
export interface DetailSlot {
  asideEl: HTMLDivElement | null;
  setOpen: (open: boolean) => void;
}

export const DetailSlotContext = createContext<DetailSlot | null>(null);
