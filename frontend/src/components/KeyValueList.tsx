import type { ReactNode } from 'react';
import classes from './KeyValueList.module.css';

export interface KeyValueItem {
  label: ReactNode;
  value: ReactNode;
}

/** Two-column label / value list used in detail cards (same shape for vehicle, driver and trip). */
export function KeyValueList({ items }: { items: KeyValueItem[] }) {
  return (
    <dl className={classes.kv}>
      {items.map((item, i) => (
        <div key={i} className={classes.row}>
          <dt className={classes.label}>{item.label}</dt>
          <dd className={classes.value}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
