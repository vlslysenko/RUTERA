import { NavLink } from 'react-router';
import { Tooltip } from '@mantine/core';
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { SETTINGS_ITEM, visibleGroups, type NavItem } from '@/app/navigation';
import { useUser } from '@/auth/useAuth';
import { cx } from '@/utils/cx';
import classes from './Sidebar.module.css';

interface Props {
  collapsed: boolean;
  onToggleCollapsed: () => void;
}

/** Dark grouped menu; shows only the modules the role may use; collapses to icons. */
export function Sidebar({ collapsed, onToggleCollapsed }: Props) {
  const { t } = useTranslation();
  const user = useUser();
  const groups = visibleGroups(user.role);

  return (
    <nav className={cx(classes.side, collapsed && classes.collapsed)} aria-label={t('nav.menu')}>
      <div className={classes.items}>
        {groups.map((g) => (
          <div key={g.key} className={classes.group}>
            {g.labelKey &&
              (collapsed ? (
                <div className={classes.groupDivider} />
              ) : (
                <div className={classes.groupLabel}>{t(g.labelKey)}</div>
              ))}
            {g.items.map((item) => (
              <NavEntry key={item.key} item={item} collapsed={collapsed} />
            ))}
          </div>
        ))}
      </div>
      <div className={classes.bottom}>
        <NavEntry item={SETTINGS_ITEM} collapsed={collapsed} />
        <button
          type="button"
          className={classes.toggle}
          onClick={onToggleCollapsed}
          aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
          title={collapsed ? t('nav.expand') : t('nav.collapse')}
        >
          {collapsed ? (
            <IconLayoutSidebarLeftExpand size={18} stroke={1.8} />
          ) : (
            <IconLayoutSidebarLeftCollapse size={18} stroke={1.8} />
          )}
          <span className={classes.label}>{t('nav.collapse')}</span>
        </button>
      </div>
    </nav>
  );
}

function NavEntry({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const { t } = useTranslation();
  const Icon = item.icon;
  const label = t(item.labelKey);
  const link = (
    <NavLink
      to={item.path}
      className={({ isActive }) => cx(classes.nav, isActive && classes.active)}
      aria-label={label}
    >
      <Icon size={18} stroke={1.8} />
      <span className={classes.label}>{label}</span>
    </NavLink>
  );
  return collapsed ? (
    <Tooltip label={label} position="right">
      {link}
    </Tooltip>
  ) : (
    link
  );
}
