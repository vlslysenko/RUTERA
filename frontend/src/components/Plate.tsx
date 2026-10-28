import type { ReactNode } from 'react';
import { cx } from '@/utils/cx';
import classes from './Plate.module.css';

/** Registration number: monospace, framed, never wraps. */
export function Plate({ children, size = 'md' }: { children: ReactNode; size?: 'md' | 'lg' }) {
  return <span className={cx(classes.plate, size === 'lg' && classes.lg)}>{children}</span>;
}
