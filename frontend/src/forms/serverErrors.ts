import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiError } from '@/api/client';

/**
 * Copies the backend's per-field validation codes (422 { details: { field: 'validation.*' } }) into
 * react-hook-form errors. The message is the i18n key; render it with t(error.message).
 * Returns true when at least one field received an error. `aliases` maps an API field to the form field that edits
 * it in other units (heightMm -> heightM).
 */
export function applyServerErrors<T extends FieldValues>(
  e: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
  aliases: Partial<Record<string, Path<T>>> = {},
): boolean {
  if (!(e instanceof ApiError) || !e.details) return false;
  let applied = false;
  for (const [apiField, code] of Object.entries(e.details)) {
    const field = aliases[apiField] ?? apiField;
    if ((fields as readonly string[]).includes(field)) {
      setError(field as Path<T>, { type: 'server', message: code });
      applied = true;
    }
  }
  return applied;
}
