import { FULL_VIN_RE, type VinDecode, type VinSuggestion } from '@/api/fleet';

/* A small copy of the backend VinDecoder for the in-browser mock: a few WMIs, the Scania series letter and wheel
 * formula, Mercedes-Benz 963/964, the Volvo model year and the Euro class presumed from a year. The backend has the
 * full tables and the model catalogue. */

const MAKERS: Record<string, { make: string; year: boolean; country: string; trailer?: boolean }> = {
  XLR: { make: 'DAF', year: false, country: 'NL' },
  YS2: { make: 'Scania', year: false, country: 'SE' },
  YV2: { make: 'Volvo', year: true, country: 'SE' },
  WMA: { make: 'MAN', year: false, country: 'DE' },
  VF6: { make: 'Renault Trucks', year: false, country: 'FR' },
  ZCF: { make: 'Iveco', year: false, country: 'IT' },
  WDB: { make: 'Mercedes-Benz', year: false, country: 'DE' },
  W1T: { make: 'Mercedes-Benz', year: false, country: 'DE' },
  WSM: { make: 'Schmitz Cargobull', year: false, country: 'DE', trailer: true },
  WKE: { make: 'Krone', year: false, country: 'DE', trailer: true },
};

const YEAR_CODES = 'ABCDEFGHJKLMNPRSTVWXY123456789';

function presumed(year: number): string[] {
  if (year <= 2000) return [];
  if (year <= 2005) return ['EURO_3'];
  if (year === 2006) return ['EURO_3', 'EURO_4'];
  if (year <= 2008) return ['EURO_4'];
  if (year === 2009) return ['EURO_4', 'EURO_5'];
  if (year <= 2013) return ['EURO_5'];
  return ['EURO_6'];
}

export function decodeVin(raw: string, registrationYear: number | null): VinDecode {
  const vin = raw.replace(/[\s-]+/g, '').toUpperCase().slice(0, 32);
  const empty: VinDecode = {
    vin,
    status: 'OK',
    wmi: null,
    manufacturer: null,
    country: null,
    checkDigit: null,
    checkDigitRequired: false,
    modelYear: null,
    modelYearConfidence: null,
    yearNotEncoded: false,
    year: null,
    yearSource: null,
    wheels: null,
    suggestions: [],
    engines: [],
  };
  if (vin.length !== 17) return { ...empty, status: 'INVALID_LENGTH' };
  if (!FULL_VIN_RE.test(vin)) return { ...empty, status: 'INVALID_CHARS' };

  const wmi = vin.slice(0, 3);
  const maker = MAKERS[wmi];
  const out: VinDecode = { ...empty, wmi, checkDigit: 'INVALID', country: maker?.country ?? null };
  if (!maker) return out;

  out.manufacturer = maker.make;
  out.yearNotEncoded = !maker.year;
  if (maker.year) {
    const i = YEAR_CODES.indexOf(vin[9]);
    if (i >= 0) {
      let y = 1980 + i;
      while (y + 30 <= new Date().getFullYear() + 1) y += 30;
      out.modelYear = y;
      out.modelYearConfidence = 'MEDIUM';
    }
  }
  if (registrationYear) {
    out.year = registrationYear;
    out.yearSource = 'REGISTRATION';
  } else if (out.modelYear) {
    out.year = out.modelYear;
    out.yearSource = 'MODEL_YEAR';
  }

  const s: VinSuggestion[] = [{ field: 'make', value: maker.make, options: [], confidence: 'HIGH', basis: 'WMI' }];
  if (maker.trailer) {
    s.push({ field: 'kind', value: 'SEMI_TRAILER', options: ['SEMI_TRAILER', 'TRAILER'], confidence: 'LOW', basis: 'WMI' });
  } else {
    if (maker.make === 'Scania' && 'PGRS'.includes(vin[3])) {
      s.push({ field: 'model', value: vin[3], options: [], confidence: 'MEDIUM', basis: 'VDS' });
      const w = /([468])X([24])/.exec(vin.slice(4, 9));
      if (w) {
        out.wheels = w[0];
        s.push({ field: 'axles', value: String(Number(w[1]) / 2), options: [], confidence: 'MEDIUM', basis: 'WHEELS' });
      }
    }
    const bm = vin.slice(3, 6);
    if (maker.make === 'Mercedes-Benz' && (bm === '963' || bm === '964')) {
      s.push({ field: 'model', value: null, options: ['Actros', 'Arocs', 'Antos'], confidence: 'LOW', basis: 'VDS' });
      s.push({ field: 'kind', value: bm === '963' ? 'TRACTOR' : 'TRUCK', options: [], confidence: 'LOW', basis: 'VDS' });
    }
    s.push({ field: 'fuelType', value: 'DIESEL', options: ['DIESEL', 'LNG', 'ELECTRIC'], confidence: 'LOW', basis: 'MODELS' });
    if (out.year) {
      const p = presumed(out.year);
      if (p.length) {
        s.push({
          field: 'euroClass',
          value: p[0],
          options: p.length > 1 ? p : [],
          confidence: p.length > 1 ? 'LOW' : 'MEDIUM',
          basis: 'YEAR',
        });
      }
    }
  }
  out.suggestions = s;
  return out;
}
