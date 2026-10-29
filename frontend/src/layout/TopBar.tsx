import { useRef } from 'react';
import { Link } from 'react-router';
import { ActionIcon, Burger, Indicator, Kbd, TextInput, Tooltip } from '@mantine/core';
import { useHotkeys } from '@mantine/hooks';
import { IconBell, IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useUser } from '@/auth/useAuth';
import { LanguageMenu } from './LanguageMenu';
import { UserMenu } from './UserMenu';
import classes from './TopBar.module.css';

interface Props {
  mobileOpened: boolean;
  onToggleMobile: () => void;
}

export function TopBar({ mobileOpened, onToggleMobile }: Props) {
  const { t } = useTranslation();
  const user = useUser();
  const searchRef = useRef<HTMLInputElement>(null);

  // Ctrl+K / Cmd+K focuses the global search (dispatcher keyboard rule).
  useHotkeys([['mod+K', () => searchRef.current?.focus()]]);

  return (
    <div className={classes.bar}>
      <Burger hiddenFrom="sm" size="sm" opened={mobileOpened} onClick={onToggleMobile} aria-label={t('nav.menu')} />
      <Link to="/" className={classes.logo} aria-label={t('app.name')}>
        <span className={classes.dot} />
        {t('app.name')}
      </Link>
      {user.firma && (
        <span className={classes.firma} title={user.firma.name}>
          {user.firma.name}
        </span>
      )}
      <TextInput
        ref={searchRef}
        className={classes.search}
        placeholder={t('topbar.search.placeholder')}
        aria-label={t('topbar.search.placeholder')}
        leftSection={<IconSearch size={16} />}
        rightSection={
          <Kbd size="xs" className={classes.kbd}>
            Ctrl K
          </Kbd>
        }
        rightSectionWidth={62}
        rightSectionPointerEvents="none"
      />
      <Tooltip label={t('topbar.notifications')}>
        {/* The counter will come from the backend (notifications endpoint); hidden until then. */}
        <Indicator color="red" size={16} offset={4} disabled>
          <ActionIcon aria-label={t('topbar.notifications')}>
            <IconBell size={18} stroke={1.8} />
          </ActionIcon>
        </Indicator>
      </Tooltip>
      <LanguageMenu saveToProfile />
      <UserMenu />
    </div>
  );
}
