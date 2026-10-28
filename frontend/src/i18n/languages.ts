/** 24 official EU languages + Ukrainian + Russian (REQUIREMENTS.md, section 6). Native names for the switcher. */
export const LANGUAGES = [
  { code: 'bg', name: 'Български' },
  { code: 'cs', name: 'Čeština' },
  { code: 'da', name: 'Dansk' },
  { code: 'de', name: 'Deutsch' },
  { code: 'el', name: 'Ελληνικά' },
  { code: 'en', name: 'English' },
  { code: 'es', name: 'Español' },
  { code: 'et', name: 'Eesti' },
  { code: 'fi', name: 'Suomi' },
  { code: 'fr', name: 'Français' },
  { code: 'ga', name: 'Gaeilge' },
  { code: 'hr', name: 'Hrvatski' },
  { code: 'hu', name: 'Magyar' },
  { code: 'it', name: 'Italiano' },
  { code: 'lt', name: 'Lietuvių' },
  { code: 'lv', name: 'Latviešu' },
  { code: 'mt', name: 'Malti' },
  { code: 'nl', name: 'Nederlands' },
  { code: 'pl', name: 'Polski' },
  { code: 'pt', name: 'Português' },
  { code: 'ro', name: 'Română' },
  { code: 'sk', name: 'Slovenčina' },
  { code: 'sl', name: 'Slovenščina' },
  { code: 'sv', name: 'Svenska' },
  { code: 'uk', name: 'Українська' },
  { code: 'ru', name: 'Русский' },
] as const;

export type LanguageCode = (typeof LANGUAGES)[number]['code'];

export const LANGUAGE_CODES: readonly LanguageCode[] = LANGUAGES.map((l) => l.code);

export const DEFAULT_LANGUAGE: LanguageCode = 'en';

export function isLanguageCode(value: unknown): value is LanguageCode {
  return typeof value === 'string' && (LANGUAGE_CODES as readonly string[]).includes(value);
}

export function languageName(code: string): string {
  return LANGUAGES.find((l) => l.code === code)?.name ?? code;
}
