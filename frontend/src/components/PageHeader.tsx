import type { ReactNode } from 'react';
import { Group, Text, Title } from '@mantine/core';

interface Props {
  title: ReactNode;
  subtitle?: ReactNode;
  /** One main action lives in the top-right corner (ergonomics rule). */
  actions?: ReactNode;
}

export function PageHeader({ title, subtitle, actions }: Props) {
  return (
    <Group justify="space-between" align="center" gap="sm" wrap="wrap" mb="md">
      <Group gap="sm" align="baseline">
        <Title order={1}>{title}</Title>
        {subtitle !== undefined && subtitle !== null && <Text c="dimmed">{subtitle}</Text>}
      </Group>
      {actions && <Group gap="xs">{actions}</Group>}
    </Group>
  );
}
