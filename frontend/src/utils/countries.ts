/**
 * ISO 3166 alpha-2 codes offered in country pickers: EU and EEA, the UK and Switzerland, and the countries most
 * drivers of Polish and Baltic carriers come from. Names come from Intl.DisplayNames in the UI language.
 */
export const COUNTRY_CODES = [
  'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT', 'LV', 'LT', 'LU', 'MT',
  'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE', 'IS', 'LI', 'NO', 'CH', 'GB',
  'UA', 'BY', 'MD', 'GE', 'AM', 'AZ', 'KZ', 'UZ', 'KG', 'TJ', 'TM', 'TR', 'RS', 'BA', 'ME', 'MK', 'AL', 'XK', 'RU',
  'IN', 'PH', 'NP', 'BD', 'LK', 'ID', 'VN', 'CO',
] as const;

export function countryName(code: string, lang: string): string {
  try {
    return new Intl.DisplayNames([lang], { type: 'region' }).of(code) ?? code;
  } catch {
    return code;
  }
}

/** Select options sorted by the localized name; a code missing from the list (e.g. from the backend) is kept. */
export function countryOptions(lang: string, extra?: string | null): { value: string; label: string }[] {
  const codes: string[] = [...COUNTRY_CODES];
  if (extra && !codes.includes(extra)) codes.push(extra);
  return codes
    .map((code) => ({ value: code, label: `${countryName(code, lang)} (${code})` }))
    .sort((a, b) => a.label.localeCompare(b.label, lang));
}
