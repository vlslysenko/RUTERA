import { Navigate } from 'react-router';
import { useUser } from '@/auth/useAuth';
import { startScreenPath } from './navigation';

/** "/" sends the user to the start screen from the profile, or the role default. */
export function StartRedirect() {
  const user = useUser();
  return <Navigate to={startScreenPath(user)} replace />;
}
