import { Button, Stack, Text } from '@mantine/core';
import { IconDownload } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useInstall } from '@/app/install';
import { Card } from './DriverParts';

/** Offer to install the cabinet on the phone: a button where the browser allows it, the iPhone hint otherwise. */
export function InstallCard() {
  const { t } = useTranslation();
  const { standalone, canPrompt, ios, install } = useInstall();
  if (standalone || (!canPrompt && !ios)) return null;
  return (
    <Card title={t('install.title')}>
      <Stack gap="sm">
        <Text size="sm">{t('install.text')}</Text>
        {canPrompt ? (
          <Button size="md" leftSection={<IconDownload size={18} />} onClick={() => void install()}>
            {t('install.button')}
          </Button>
        ) : (
          <Text size="sm" c="dimmed">
            {t('install.ios')}
          </Text>
        )}
      </Stack>
    </Card>
  );
}
