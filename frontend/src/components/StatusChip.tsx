import type { ReactNode } from 'react';
import { cx } from '@/utils/cx';
import classes from './StatusChip.module.css';

export type StatusTone = 'good' | 'warn' | 'crit' | 'info' | 'neutral';

interface Props {
  tone: StatusTone;
  /** Status = shape + color + word: pass an icon so color is never the only signal. */
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function StatusChip({ tone, icon, children, className }: Props) {
  return (
    <span className={cx(classes.chip, classes[tone], className)}>
      {icon}
      <span>{children}</span>
    </span>
  );
}
