import type { ReactNode } from 'react';
import { Paper, Stack, Text } from '@mantine/core';

export function EmptyState({ icon, text }: { icon?: ReactNode; text: ReactNode }) {
  return (
    <Paper p="xl">
      <Stack align="center" gap="xs" c="dimmed" py="lg">
        {icon}
        <Text ta="center" maw={480}>
          {text}
        </Text>
      </Stack>
    </Paper>
  );
}
