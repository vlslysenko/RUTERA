import type { ReactNode } from 'react';
import { Tooltip } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import classes from './LabelHint.module.css';

/**
 * A field label with an (i) that explains the field: tooltip on hover, tap and keyboard focus. The tap must not reach
 * the surrounding <label>, or the phone would focus the input and open the keyboard over the explanation.
 */
export function LabelHint({ label, hint }: { label: ReactNode; hint: string }) {
  return (
    <span className={classes.wrap}>
      {label}
      <Tooltip label={hint}>
        <span className={classes.icon} role="img" tabIndex={0} aria-label={hint} onClick={(e) => e.preventDefault()}>
          <IconInfoCircle size={14} stroke={1.8} />
        </span>
      </Tooltip>
    </span>
  );
}
