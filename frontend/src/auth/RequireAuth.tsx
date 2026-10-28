import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { currentLanguage, setLanguage } from '@/i18n';
import { FullScreenLoader } from '@/components/FullScreenLoader';
import { ErrorState } from '@/components/ErrorState';
import { useMe } from './useAuth';

/** Loads the session once; redirects to /login when there is none. Applies the profile language. */
export function RequireAuth() {
  const { data: user, isPending, isError, error, refetch } = useMe();
  const location = useLocation();
  const { t } = useTranslation();

  const profileLanguage = user?.language;
  useEffect(() => {
    if (profileLanguage && profileLanguage !== currentLanguage()) void setLanguage(profileLanguage);
  }, [profileLanguage]);

  if (isPending) return <FullScreenLoader />;
  if (isError) return <ErrorState fullScreen message={t(errorCode(error))} onRetry={() => void refetch()} />;
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
