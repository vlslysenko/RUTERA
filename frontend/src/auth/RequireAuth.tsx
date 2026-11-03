import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { currentLanguage, setLanguage } from '@/i18n';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { ErrorState } from '@/components/ErrorState';
import { OfflineScreen } from '@/components/Offline';
import { useMe } from './useAuth';

/** Loads the session once; redirects to /login when there is none. Applies the profile language. */
export function RequireAuth() {
  const { data: user, isPending, isError, error, refetch, fetchStatus } = useMe();
  const location = useLocation();
  const { t } = useTranslation();

  const profileLanguage = user?.language;
  useEffect(() => {
    if (profileLanguage && profileLanguage !== currentLanguage()) void setLanguage(profileLanguage);
  }, [profileLanguage]);

  // Offline before anything is saved on this device: say so instead of an endless loader (resumes by itself).
  if (isPending && fetchStatus === 'paused') return <OfflineScreen />;
  if (isPending) return <FullScreenLoader />;
  // A failed re-check keeps the session it already knows (restored offline data, weak signal on the road).
  if (isError && user === undefined) return <ErrorState fullScreen message={t(errorCode(error))} onRetry={() => void refetch()} />;
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
