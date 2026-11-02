import type { ReactNode } from 'react';
import { Outlet } from 'react-router';
import { canSee, SCREENS } from '@/app/navigation';
import type { ScreenKey } from '@/app/types';
import { AccessDeniedPage } from '@/pages/AccessDeniedPage';
import { hasAnyPrivilege } from './privileges';
import { useUser } from './useAuth';

/** Layout route: renders child routes only when the user may see the screen (same rule as the menu). */
export function ScreenGuard({ screen }: { screen: ScreenKey }) {
  const user = useUser();
  return canSee(SCREENS[screen], user) ? <Outlet /> : <AccessDeniedPage />;
}

/**
 * Layout route: renders child routes only when the user holds the privilege (any of them, for a list);
 * otherwise `fallback`, by default the access-denied page.
 */
export function PrivilegeGuard({ privilege, fallback }: { privilege: string | readonly string[]; fallback?: ReactNode }) {
  const user = useUser();
  const ok = hasAnyPrivilege(user, typeof privilege === 'string' ? [privilege] : privilege);
  return ok ? <Outlet /> : (fallback ?? <AccessDeniedPage />);
}
