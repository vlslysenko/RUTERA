/** Quantity with its unit in the UI language ("410,5 l"). */
export function formatQty(qty: number | null, unit: string | null, lang: string, t: (k: string) => string): string {
  if (qty == null) return '—';
  return `${new Intl.NumberFormat(lang, { maximumFractionDigits: 2 }).format(qty)} ${unit ? t(`fuel.unit.${unit}`) : ''}`.trim();
}
