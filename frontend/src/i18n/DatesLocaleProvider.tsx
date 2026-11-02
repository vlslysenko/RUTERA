import { useEffect, useState, type ReactNode } from 'react';
import { DatesProvider } from '@mantine/dates';
import dayjs from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import localizedFormat from 'dayjs/plugin/localizedFormat';
import i18n from './index';
import { DEFAULT_LANGUAGE, isLanguageCode, type LanguageCode } from './languages';

// Typed dates are parsed in the language's short format ("L"), see forms/DateField.
dayjs.extend(customParseFormat);
dayjs.extend(localizedFormat);

/** dayjs locales of the 26 UI languages, loaded on demand (English is built into dayjs). */
const LOADERS: Record<LanguageCode, () => Promise<unknown>> = {
  bg: () => import('dayjs/locale/bg'),
  cs: () => import('dayjs/locale/cs'),
  da: () => import('dayjs/locale/da'),
  de: () => import('dayjs/locale/de'),
  el: () => import('dayjs/locale/el'),
  en: () => Promise.resolve(),
  es: () => import('dayjs/locale/es'),
  et: () => import('dayjs/locale/et'),
  fi: () => import('dayjs/locale/fi'),
  fr: () => import('dayjs/locale/fr'),
  ga: () => import('dayjs/locale/ga'),
  hr: () => import('dayjs/locale/hr'),
  hu: () => import('dayjs/locale/hu'),
  it: () => import('dayjs/locale/it'),
  lt: () => import('dayjs/locale/lt'),
  lv: () => import('dayjs/locale/lv'),
  mt: () => import('dayjs/locale/mt'),
  nl: () => import('dayjs/locale/nl'),
  pl: () => import('dayjs/locale/pl'),
  pt: () => import('dayjs/locale/pt'),
  ro: () => import('dayjs/locale/ro'),
  sk: () => import('dayjs/locale/sk'),
  sl: () => import('dayjs/locale/sl'),
  sv: () => import('dayjs/locale/sv'),
  uk: () => import('dayjs/locale/uk'),
  ru: () => import('dayjs/locale/ru'),
};

/** Date pickers (month and day names, short date format) in the UI language; weeks start on Monday. */
export function DatesLocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<LanguageCode>(DEFAULT_LANGUAGE);

  useEffect(() => {
    let latest = 0;
    const apply = (lng: string) => {
      const code = isLanguageCode(lng) ? lng : DEFAULT_LANGUAGE;
      const call = ++latest;
      LOADERS[code]().then(
        () => call === latest && setLocale(code),
        () => call === latest && setLocale(DEFAULT_LANGUAGE),
      );
    };
    apply(i18n.resolvedLanguage ?? i18n.language);
    i18n.on('languageChanged', apply);
    return () => {
      latest = -1;
      i18n.off('languageChanged', apply);
    };
  }, []);

  return <DatesProvider settings={{ locale, firstDayOfWeek: 1, weekendDays: [0, 6] }}>{children}</DatesProvider>;
}
