import { Link } from 'react-router';
import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <Center mih={320}>
      <Stack align="center" gap="xs">
        <Title order={2}>{t('notfound.title')}</Title>
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
