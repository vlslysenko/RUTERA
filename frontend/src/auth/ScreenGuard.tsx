import { Outlet } from 'react-router';
import { canSee, SCREENS } from '@/app/navigation';
import type { ScreenKey } from '@/app/types';
import { AccessDeniedPage } from '@/pages/AccessDeniedPage';
import { hasPrivilege } from './privileges';
import { useUser } from './useAuth';

/** Layout route: renders child routes only when the user may see the screen (same rule as the menu). */
export function ScreenGuard({ screen }: { screen: ScreenKey }) {
  const user = useUser();
  return canSee(SCREENS[screen], user) ? <Outlet /> : <AccessDeniedPage />;
}

/** Layout route: renders child routes only when the user holds the privilege. */
export function PrivilegeGuard({ privilege }: { privilege: string }) {
  const user = useUser();
  return hasPrivilege(user, privilege) ? <Outlet /> : <AccessDeniedPage />;
}
