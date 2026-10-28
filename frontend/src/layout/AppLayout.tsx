import { useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation } from 'react-router';
import { AppShell, useMantineTheme } from '@mantine/core';
import { useDisclosure, useLocalStorage } from '@mantine/hooks';
import { TopBar } from './TopBar';
import { Sidebar } from './Sidebar';
import { DetailSlotContext, type DetailSlot } from './detailSlot';
import classes from './AppLayout.module.css';

const SIDEBAR_COLLAPSED_KEY = 'rutera.sidebar.collapsed';

/**
 * Screen frame: top bar (firma, search, notifications, language, user), dark grouped sidebar that collapses
 * to icons, main area, and a right panel (aside) where list pages open a detail card.
 */
export function AppLayout() {
  const theme = useMantineTheme();
  const [collapsed, setCollapsed] = useLocalStorage<boolean>({
    key: SIDEBAR_COLLAPSED_KEY,
    defaultValue: false,
    getInitialValueInEffect: false,
  });
  const [mobileOpened, mobile] = useDisclosure(false);
  const [asideEl, setAsideEl] = useState<HTMLDivElement | null>(null);
  const [asideOpen, setAsideOpen] = useState(false);
  const { pathname } = useLocation();

  const closeMobile = mobile.close;
  useEffect(() => {
    closeMobile();
  }, [pathname, closeMobile]);

  const slot = useMemo<DetailSlot>(() => ({ asideEl, setOpen: setAsideOpen }), [asideEl]);

  const navbarWidth = collapsed ? Number(theme.other.sideWidthCollapsed) : Number(theme.other.sideWidth);

  return (
    <DetailSlotContext.Provider value={slot}>
      <AppShell
        header={{ height: Number(theme.other.topBarHeight) }}
        navbar={{ width: navbarWidth, breakpoint: 'sm', collapsed: { mobile: !mobileOpened } }}
        aside={{
          width: Number(theme.other.asideWidth),
          breakpoint: 'md',
          collapsed: { desktop: !asideOpen, mobile: !asideOpen },
        }}
        padding="md"
        transitionDuration={150}
      >
        <AppShell.Header className={classes.header}>
          <TopBar mobileOpened={mobileOpened} onToggleMobile={mobile.toggle} />
        </AppShell.Header>
        <AppShell.Navbar className={classes.navbar}>
          <Sidebar collapsed={collapsed} onToggleCollapsed={() => setCollapsed((c) => !c)} />
        </AppShell.Navbar>
        <AppShell.Main className={classes.main}>
          <Outlet />
        </AppShell.Main>
        <AppShell.Aside className={classes.aside}>
          <div ref={setAsideEl} className={classes.asideInner} />
        </AppShell.Aside>
      </AppShell>
    </DetailSlotContext.Provider>
  );
}
