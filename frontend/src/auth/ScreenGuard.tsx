import { Outlet } from 'react-router';
import { canSee, SCREENS } from '@/app/navigation';
import type { ScreenKey } from '@/app/types';
import { AccessDeniedPage } from '@/pages/AccessDeniedPage';
import { useUser } from './useAuth';

/** Layout route: renders child routes only when the role may see the screen. */
export function ScreenGuard({ screen }: { screen: ScreenKey }) {
  const user = useUser();
  return canSee(SCREENS[screen], user.role) ? <Outlet /> : <AccessDeniedPage />;
}
