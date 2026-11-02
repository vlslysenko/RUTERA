/*
 * Times in the firma's time zone (User.firma.timezone): fuel receipts and tachograph days are local times of the
 * firma, not of the browser. Without a zone everything falls back to the browser's.
 */

/** Offset of a time zone from UTC at an instant, in milliseconds (Europe/Warsaw in summer: +2 h). */
export function zoneOffset(ms: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(ms));
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'), n('second')) - Math.floor(ms / 1000) * 1000;
}

/** ISO instant -> "yyyy-MM-ddTHH:mm" of <input type="datetime-local"> in the zone. */
export function toZonedInput(iso: string | null, timeZone?: string): string {
  if (!iso) return '';
  const ms = Date.parse(iso);
  const d = new Date(timeZone ? ms + zoneOffset(ms, timeZone) : ms - new Date(ms).getTimezoneOffset() * 60_000);
  return d.toISOString().slice(0, 16);
}

/** "yyyy-MM-ddTHH:mm" read in the zone -> ISO instant (null when empty). */
export function fromZonedInput(v: string, timeZone?: string): string | null {
  if (!v) return null;
  if (!timeZone) return new Date(v).toISOString();
  const [date, time] = v.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [h, mi] = (time ?? '00:00').split(':').map(Number);
  const guess = Date.UTC(y, m - 1, d, h, mi);
  const first = guess - zoneOffset(guess, timeZone);
  return new Date(guess - zoneOffset(first, timeZone)).toISOString();
}
