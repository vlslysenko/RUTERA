import { useState } from 'react';
import { Button, Group, Paper, Select, Stack, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { visibleScreens } from '@/app/navigation';
import type { ScreenKey } from '@/app/types';
import { useUpdateSettings, useUser } from '@/auth/useAuth';
import { setLanguage } from '@/i18n';
import { isLanguageCode, LANGUAGES, type LanguageCode } from '@/i18n/languages';
import { PageHeader } from '@/components/PageHeader';

const DEFAULT_SCREEN = '__default__';

/** Profile settings: interface language and the start screen after sign-in (REQUIREMENTS.md, section 15). */
export function SettingsPage() {
  const { t } = useTranslation();
  const user = useUser();
  const update = useUpdateSettings();

  const [language, setLanguageValue] = useState<LanguageCode>(user.language);
  const [startScreen, setStartScreen] = useState<string>(user.startScreen ?? DEFAULT_SCREEN);

  const screens = visibleScreens(user.role);
  const chosenScreen = startScreen === DEFAULT_SCREEN ? null : (startScreen as ScreenKey);
  const dirty = language !== user.language || chosenScreen !== user.startScreen;

  const save = async () => {
    try {
      await update.mutateAsync({ language, startScreen: chosenScreen });
      await setLanguage(language);
      notifications.show({ color: 'green', message: t('common.saved') });
    } catch (e) {
      notifications.show({ color: 'red', message: t(errorCode(e)) });
    }
  };

  return (
    <>
      <PageHeader title={t('settings.title')} />
      <Paper p="lg" maw={520}>
        <Stack gap="md">
          <Title order={3}>{t('settings.profile')}</Title>
          <Select
            label={t('settings.language')}
            data={LANGUAGES.map((l) => ({ value: l.code, label: l.name }))}
            value={language}
            onChange={(v) => {
              if (isLanguageCode(v)) setLanguageValue(v);
            }}
            allowDeselect={false}
            searchable
            maxDropdownHeight={300}
          />
          <Select
            label={t('settings.start_screen')}
            description={t('settings.start_screen.hint')}
            data={[
              { value: DEFAULT_SCREEN, label: t('common.default') },
              ...screens.map((s) => ({ value: s.key, label: t(s.labelKey) })),
            ]}
            value={startScreen}
            onChange={(v) => {
              if (v) setStartScreen(v);
            }}
            allowDeselect={false}
          />
          <Group justify="flex-end">
            <Button onClick={() => void save()} loading={update.isPending} disabled={!dirty}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </Paper>
    </>
  );
}
