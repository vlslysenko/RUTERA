import { z } from 'zod';

/** Decimal separator of a language, so "29,8" can be typed in Polish and "29.8" in English. */
export function decimalSeparator(lang: string): string {
  return new Intl.NumberFormat(lang).formatToParts(1.5).find((p) => p.type === 'decimal')?.value ?? '.';
}

/** Value kept in the form for a Mantine NumberInput: a number, unfinished input as a string, or null when empty. */
export type NumberInputValue = number | string | null;

/**
 * Mantine NumberInput reports unfinished input ("29." while typing, "29.50" with a trailing zero) as a string with
 * "." as the separator, whatever separator is shown. Forms keep that string and parse it here, at validation.
 * Codes as on the backend: below min -> validation.invalid_value, above max -> validation.too_long.
 */
export function numberField(min: number, max: number, { integer = false } = {}) {
  let n = z.number({ error: 'validation.invalid_value' }).min(min, 'validation.invalid_value').max(max, 'validation.too_long');
  if (integer) n = n.int('validation.invalid_value');
  return z
    .union([z.number(), z.string()])
    .nullable()
    .transform((v) => (v === null || v === '' ? null : Number(v)))
    .pipe(n.nullable());
}

/** onChange for a NumberInput bound to a numberField. */
export function toNumberInputValue(v: number | string): NumberInputValue {
  return v === '' ? null : v;
}
