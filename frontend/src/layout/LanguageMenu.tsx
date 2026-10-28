import { Button, Menu, ScrollArea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { errorCode } from '@/api/errors';
import { useUpdateSettings } from '@/auth/useAuth';
import { setLanguage } from '@/i18n';
import { isLanguageCode, LANGUAGES, languageName, type LanguageCode } from '@/i18n/languages';
import classes from './LanguageMenu.module.css';

interface Props {
  /** When signed in, the choice is also stored in the user profile. */
  saveToProfile?: boolean;
}

export function LanguageMenu({ saveToProfile = false }: Props) {
  const { t, i18n } = useTranslation();
  const update = useUpdateSettings();
  const current: LanguageCode = isLanguageCode(i18n.resolvedLanguage) ? i18n.resolvedLanguage : 'en';

  const choose = async (code: LanguageCode) => {
    await setLanguage(code);
    if (saveToProfile) {
      update.mutate(
        { language: code },
        { onError: (e) => notifications.show({ color: 'red', message: t(errorCode(e)) }) },
      );
    }
  };

  return (
    <Menu width={230} position="bottom-end">
      <Menu.Target>
        <Button
          variant="default"
          size="compact-sm"
          className={classes.trigger}
          aria-label={t('topbar.language')}
          title={languageName(current)}
        >
          {current.toUpperCase()}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <ScrollArea.Autosize mah={380} type="auto">
          {LANGUAGES.map((l) => (
            <Menu.Item
              key={l.code}
              onClick={() => void choose(l.code)}
              leftSection={<span className={classes.code}>{l.code.toUpperCase()}</span>}
              rightSection={l.code === current ? <IconCheck size={14} /> : null}
            >
              {l.name}
            </Menu.Item>
          ))}
        </ScrollArea.Autosize>
      </Menu.Dropdown>
    </Menu>
  );
}
