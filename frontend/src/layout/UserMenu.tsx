import { Link, useNavigate } from 'react-router';
import { Avatar, Menu, Text, UnstyledButton } from '@mantine/core';
import { IconLogout, IconSettings, IconSteeringWheel } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DRIVER_APP_PATH } from '@/app/navigation';
import { hasPrivilege, PRIV } from '@/auth/privileges';
import { useLogout, useUser } from '@/auth/useAuth';
import classes from './UserMenu.module.css';

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

export function UserMenu() {
  const { t } = useTranslation();
  const user = useUser();
  const logout = useLogout();
  const navigate = useNavigate();

  const onLogout = () => {
    logout.mutate(undefined, { onSettled: () => void navigate('/login', { replace: true }) });
  };

  return (
    <Menu width={200} position="bottom-end">
      <Menu.Target>
        <UnstyledButton className={classes.user} aria-label={user.name}>
          <Avatar color="blue" variant="filled" radius="xl" size={28} fz="xs">
            {initials(user.name)}
          </Avatar>
          <div className={classes.text}>
            <Text size="sm" fw={500} lh={1.15} truncate>
              {user.name}
            </Text>
            <Text size="xs" c="dimmed" lh={1.15} truncate>
              {t(`role.${user.role}`)}
            </Text>
          </div>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{user.login}</Menu.Label>
        <Menu.Item component={Link} to="/settings" leftSection={<IconSettings size={16} />}>
          {t('user.settings')}
        </Menu.Item>
        {user.driver && hasPrivilege(user, PRIV.DRIVER_APP_SELF_USE) && (
          <Menu.Item component={Link} to={DRIVER_APP_PATH} leftSection={<IconSteeringWheel size={16} />}>
            {t('driver_app.title')}
          </Menu.Item>
        )}
        <Menu.Divider />
        <Menu.Item color="red" leftSection={<IconLogout size={16} />} onClick={onLogout}>
          {t('user.logout')}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
