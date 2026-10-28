import { useContext, useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Link, useNavigate } from 'react-router';
import { ActionIcon, Loader, ScrollArea, Tooltip } from '@mantine/core';
import { useHotkeys } from '@mantine/hooks';
import { IconArrowLeft, IconArrowUpRight, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { DetailSlotContext } from '@/layout/detailSlot';
import classes from './DetailDrawer.module.css';

export interface DetailDrawerProps {
  /** 'drawer' = card in the right panel over the list (default); 'page' = the same card as a separate page. */
  mode?: 'drawer' | 'page';
  title: ReactNode;
  subtitle?: ReactNode;
  /** Status chip shown next to the title. */
  status?: ReactNode;
  /** Primary actions (buttons) shown under the header. */
  actions?: ReactNode;
  /** Where close / back leads, usually the list. */
  listPath: string;
  /** URL of this card as a full page; drawer mode shows the "open as page" icon when set. */
  pagePath?: string;
  loading?: boolean;
  children?: ReactNode;
}

/**
 * Generic detail card. Every card has its own URL: the drawer is a child route of the list,
 * the page variant is a sibling route (…/:id/page). Esc closes the drawer.
 */
export function DetailDrawer(props: DetailDrawerProps) {
  return props.mode === 'page' ? <DetailPage {...props} /> : <DetailAside {...props} />;
}

function DetailAside(props: DetailDrawerProps) {
  const slot = useContext(DetailSlotContext);
  const navigate = useNavigate();
  const setOpen = slot?.setOpen;

  useEffect(() => {
    setOpen?.(true);
    return () => setOpen?.(false);
  }, [setOpen]);

  const close = () => void navigate(props.listPath);
  useHotkeys([['Escape', close]]);

  if (!slot?.asideEl) return null;
  return createPortal(<DetailFrame {...props} onClose={close} />, slot.asideEl);
}

function DetailFrame({ title, subtitle, status, actions, pagePath, loading, children, onClose }: DetailDrawerProps & { onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <div className={classes.frame}>
      <div className={classes.head}>
        <div className={classes.titles}>
          <div className={classes.titleRow}>
            <span className={classes.title}>{title}</span>
            {status}
          </div>
          {subtitle && <div className={classes.subtitle}>{subtitle}</div>}
        </div>
        <div className={classes.icons}>
          {pagePath && (
            <Tooltip label={t('common.open_as_page')}>
              <ActionIcon component={Link} to={pagePath} aria-label={t('common.open_as_page')}>
                <IconArrowUpRight size={18} stroke={1.8} />
              </ActionIcon>
            </Tooltip>
          )}
          <Tooltip label={t('common.close')}>
            <ActionIcon onClick={onClose} aria-label={t('common.close')}>
              <IconX size={18} stroke={1.8} />
            </ActionIcon>
          </Tooltip>
        </div>
      </div>
      {actions && <div className={classes.actions}>{actions}</div>}
      <ScrollArea className={classes.body} type="auto">
        <div className={classes.bodyInner}>{loading ? <Loader size="sm" /> : children}</div>
      </ScrollArea>
    </div>
  );
}

function DetailPage({ title, subtitle, status, actions, listPath, loading, children }: DetailDrawerProps) {
  const { t } = useTranslation();
  return (
    <div className={classes.page}>
      <div className={classes.pageHead}>
        <Tooltip label={t('common.back')}>
          <ActionIcon component={Link} to={listPath} aria-label={t('common.back')} size="lg">
            <IconArrowLeft size={18} stroke={1.8} />
          </ActionIcon>
        </Tooltip>
        <div className={classes.titles}>
          <div className={classes.titleRow}>
            <span className={classes.pageTitle}>{title}</span>
            {status}
          </div>
          {subtitle && <div className={classes.subtitle}>{subtitle}</div>}
        </div>
        {actions && <div className={classes.pageActions}>{actions}</div>}
      </div>
      <div className={classes.pageBody}>{loading ? <Loader size="sm" /> : children}</div>
    </div>
  );
}
