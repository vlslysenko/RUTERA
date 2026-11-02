import type { TFunction } from 'i18next';
import type { VatCode } from '@/api/finance';

/** "VAT 23 %", or the name of the exemption (zw / np / oo). */
export function vatLabel(t: TFunction, code: VatCode, rate: number | null, lang: string): string {
  return code === 'RATE' && rate != null
    ? t('invoices.vat_rate', { rate: new Intl.NumberFormat(lang, { maximumFractionDigits: 2 }).format(rate) })
    : t(`vat.code.${code}`);
}

/** Step of the reminder plan in words: "3 days before the due date", "on the due date", "7 days after"; by hand. */
export function reminderStepLabel(t: TFunction, offsetDays: number | null): string {
  if (offsetDays == null) return t('reminders.manual');
  if (offsetDays === 0) return t('reminders.on_due');
  return offsetDays < 0 ? t('reminders.before', { days: -offsetDays }) : t('reminders.after', { days: offsetDays });
}
