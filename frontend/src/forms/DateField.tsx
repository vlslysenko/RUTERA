import { useRef, type Ref } from 'react';
import { useController, type Control, type FieldValues, type Path } from 'react-hook-form';
import { DateInput, useDatesContext, type DateInputProps } from '@mantine/dates';
import { IconCalendar } from '@tabler/icons-react';
import dayjs from 'dayjs';

/*
 * Date (and date + time) field on the Mantine picker, replacing <input type="date"> / "datetime-local".
 * Values stay in the shapes the forms and the API already use: "YYYY-MM-DD" for a date, local "YYYY-MM-DDTHH:mm" with
 * withTime, "" when empty. The input shows the UI language's short format (dayjs "L": 02.11.2026 in Polish,
 * 11/02/2026 in English) and accepts typing in that format, also with one-digit day/month, or as YYYY-MM-DD.
 * The calendar language follows DatesLocaleProvider.
 */

const TIME = 'HH:mm';

/** Formats accepted while typing, strictly, so a half-typed date does not jump to a wrong one. */
function typedFormats(locale: string, withTime: boolean): string[] {
  const short = (dayjs.Ls[locale]?.formats as { L?: string } | undefined)?.L ?? 'MM/DD/YYYY';
  const dates = [...new Set([short, short.replace('DD', 'D').replace('MM', 'M'), 'YYYY-MM-DD'])];
  return withTime ? dates.flatMap((d) => [`${d} ${TIME}`, `${d} H:mm`]) : dates;
}

/** "YYYY-MM-DDTHH:mm" / "YYYY-MM-DD" -> the Mantine value ("YYYY-MM-DD HH:mm:ss" with time). */
function toPicker(value: string | null | undefined, withTime: boolean): string | null {
  if (!value) return null;
  return withTime ? `${value.slice(0, 10)} ${value.slice(11, 16) || '00:00'}:00` : value.slice(0, 10);
}

/** Mantine value -> the form value. */
function fromPicker(value: string | null, withTime: boolean): string {
  if (!value) return '';
  return withTime ? `${value.slice(0, 10)}T${value.slice(11, 16) || '00:00'}` : value.slice(0, 10);
}

export type DateFieldProps = Omit<
  DateInputProps,
  'value' | 'defaultValue' | 'onChange' | 'valueFormat' | 'dateParser' | 'withTime' | 'minDate' | 'maxDate'
> & {
  value: string | null | undefined;
  onChange: (value: string) => void;
  ref?: Ref<HTMLInputElement>;
  /** Date and time ("YYYY-MM-DDTHH:mm"), like datetime-local. */
  withTime?: boolean;
  /** Earliest / latest selectable date, "YYYY-MM-DD". */
  min?: string;
  max?: string;
};

export function DateField({ value, onChange, withTime = false, min, max, clearable = true, ...rest }: DateFieldProps) {
  const ctx = useDatesContext();
  const locale = ctx.getLocale();
  // Value produced by typing; anything else reaching onChange was picked in the calendar.
  const typed = useRef<string | null>(null);

  const parse = (text: string): string | null => {
    const d = dayjs(text.trim(), typedFormats(locale, withTime), locale, true);
    typed.current = d.isValid() ? d.format(withTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD') : null;
    return typed.current;
  };

  const change = (next: string | null) => {
    let v = next;
    // A day picked in the calendar comes at midnight: keep the time that was already entered.
    if (withTime && v && v !== typed.current && value && value.length >= 16) v = `${v.slice(0, 10)} ${value.slice(11, 16)}:00`;
    typed.current = null;
    onChange(fromPicker(v, withTime));
  };

  return (
    <DateInput
      {...rest}
      value={toPicker(value, withTime)}
      onChange={change}
      valueFormat={withTime ? `L ${TIME}` : 'L'}
      dateParser={parse}
      withTime={withTime}
      minDate={min || undefined}
      maxDate={max || undefined}
      clearable={clearable && !rest.readOnly}
      leftSection={<IconCalendar size={16} stroke={1.5} />}
    />
  );
}

/** DateField bound to a react-hook-form field (in place of {...register(name)}). */
export function FormDateField<T extends FieldValues>({
  control,
  name,
  ...rest
}: Omit<DateFieldProps, 'value' | 'onChange'> & { control: Control<T>; name: Path<T> }) {
  const {
    field: { ref, value, onChange, onBlur },
  } = useController({ control, name });
  return <DateField {...rest} ref={ref} name={name} value={value} onChange={onChange} onBlur={onBlur} />;
}
