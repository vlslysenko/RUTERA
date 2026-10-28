import { Button, Center, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

interface Props {
  message: string;
  onRetry?: () => void;
  fullScreen?: boolean;
}

export function ErrorState({ message, onRetry, fullScreen = false }: Props) {
  const { t } = useTranslation();
  return (
    <Center mih={fullScreen ? '100vh' : 160} p="md">
      <Stack align="center" gap="sm">
        <IconAlertTriangle size={28} color="var(--rutera-status-crit)" />
        <Text ta="center">{message}</Text>
        {onRetry && (
          <Button variant="default" onClick={onRetry}>
            {t('common.retry')}
          </Button>
        )}
      </Stack>
    </Center>
  );
}
