import { Link } from 'react-router';
import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { IconLock } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

export function AccessDeniedPage() {
  const { t } = useTranslation();
  return (
    <Center mih={320}>
      <Stack align="center" gap="xs">
        <IconLock size={28} stroke={1.5} />
        <Title order={2}>{t('auth.access_denied')}</Title>
        <Text c="dimmed" ta="center">
          {t('notfound.text')}
        </Text>
        <Button component={Link} to="/" variant="default" mt="sm">
          {t('common.back')}
        </Button>
      </Stack>
    </Center>
  );
}
