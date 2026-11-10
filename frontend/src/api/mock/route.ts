import type { CalcRoute, RoutePoint, RouteRequest } from '@/api/calc';
import { encodePolyline } from '@/utils/polyline';
import type { MockReply } from './trips';

/*
 * Truck routes for the in-browser mock API (shape of POST /app/calc/route): a small gazetteer instead of Nominatim,
 * road km = great circle x 1.25 at 70 km/h, a leg between two countries split half and half. sessionStorage
 * "mock.routing" = "off" answers 503 calc.routing.disabled like a backend without a routing server.
 */

const PLACES: Record<string, [number, number, string]> = {
  wroclaw: [51.1079, 17.0385, 'PL'], warszawa: [52.2297, 21.0122, 'PL'], krakow: [50.0647, 19.945, 'PL'], poznan: [52.4064, 16.9252, 'PL'],
  gdansk: [54.352, 18.6466, 'PL'], legnica: [51.207, 16.1553, 'PL'], opole: [50.6751, 17.9213, 'PL'], katowice: [50.2649, 19.0238, 'PL'],
  lodz: [51.7592, 19.456, 'PL'], szczecin: [53.4285, 14.5528, 'PL'], berlin: [52.52, 13.405, 'DE'], hamburg: [53.5511, 9.9937, 'DE'],
  bremen: [53.0793, 8.8017, 'DE'], munchen: [48.1351, 11.582, 'DE'], dresden: [51.0504, 13.7373, 'DE'], koln: [50.9375, 6.9603, 'DE'],
  frankfurt: [50.1109, 8.6821, 'DE'], praha: [50.0755, 14.4378, 'CZ'], brno: [49.1951, 16.6068, 'CZ'], wien: [48.2082, 16.3738, 'AT'],
  bratislava: [48.1486, 17.1077, 'SK'], budapest: [47.4979, 19.0402, 'HU'], paris: [48.8566, 2.3522, 'FR'], lyon: [45.764, 4.8357, 'FR'],
  amsterdam: [52.3676, 4.9041, 'NL'], rotterdam: [51.9244, 4.4777, 'NL'], bruxelles: [50.8503, 4.3517, 'BE'], milano: [45.4642, 9.19, 'IT'],
  madrid: [40.4168, -3.7038, 'ES'], valencia: [39.4699, -0.3763, 'ES'], vilnius: [54.6872, 25.2797, 'LT'], riga: [56.9496, 24.1052, 'LV'],
  kyiv: [50.4501, 30.5234, 'UA'], lviv: [49.8397, 24.0297, 'UA'],
};

const key = (s: string) =>
  s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/ł/g, 'l').replace(/ß/g, 'ss').split(',')[0].trim();

/** Country of a coordinate: the one of the nearest gazetteer place. */
function countryAt(lat: number, lng: number): string {
  let best = 'PL';
  let bestD = Infinity;
  for (const [la, ln, c] of Object.values(PLACES)) {
    const d = (la - lat) ** 2 + (ln - lng) ** 2;
    if (d < bestD) [best, bestD] = [c, d];
  }
  return best;
}

function km(a: [number, number], b: [number, number]): number {
  const r = (x: number) => (x * Math.PI) / 180;
  const h = Math.sin(r(b[0] - a[0]) / 2) ** 2 + Math.cos(r(a[0])) * Math.cos(r(b[0])) * Math.sin(r(b[1] - a[1]) / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({
  status,
  body: { status, code, message: code, ...(details ? { details } : {}) },
});

/** Place suggestions from the gazetteer (GET /app/calc/places); "mock.places" = "off" answers 503 like no provider. */
export function places(q: string | undefined, limit: number): MockReply {
  if (sessionStorage.getItem('mock.places') === 'off') return fail(503, 'calc.geocode.failed');
  const k = key(q ?? '');
  if (k.length < 3) return { status: 200, body: [] };
  const found = Object.entries(PLACES)
    .filter(([name]) => name.startsWith(k))
    .slice(0, limit)
    .map(([name, [lat, lng, country]]) => ({ label: `${name[0].toUpperCase()}${name.slice(1)}, ${country}`, lat, lng, country }));
  return { status: 200, body: found };
}

export function route(b: Partial<RouteRequest>): MockReply {
  if (sessionStorage.getItem('mock.routing') === 'off') return fail(503, 'calc.routing.disabled');
  const points = b.points ?? [];
  if (points.length < 2 || points.length > 25) return fail(422, 'error.validation', { points: points.length < 2 ? 'validation.required' : 'validation.invalid_value' });
  const errors: Record<string, string> = {};
  const resolved = points.map((p: RoutePoint, i) => {
    if ('lat' in p) return { lat: p.lat, lng: p.lng, label: `${p.lat.toFixed(4)}, ${p.lng.toFixed(4)}`, country: countryAt(p.lat, p.lng) };
    const hit = PLACES[key(p.address ?? '')];
    if (!hit) {
      errors[`points[${i}]`] = 'calc.geocode.not_found';
      return null;
    }
    return { lat: hit[0], lng: hit[1], label: p.address.trim(), country: hit[2] };
  });
  if (Object.keys(errors).length) return fail(422, 'error.validation', errors);
  const ps = resolved as { lat: number; lng: number; label: string; country: string }[];
  // Ferries and tolls do not change anything in the mock; a route over 3000 km "does not exist" for a truck.
  const legs = ps.slice(1).map((to, i) => {
    const from = ps[i];
    const distanceKm = Math.round(km([from.lat, from.lng], [to.lat, to.lng]) * 1.25 * 10) / 10;
    const steps = 8;
    const line: [number, number][] = Array.from({ length: steps + 1 }, (_, s) => {
      const f = s / steps;
      // A slight bend so that the line looks like a road and not a ruler.
      const bend = Math.sin(f * Math.PI) * 0.15;
      return [from.lat + (to.lat - from.lat) * f + bend, from.lng + (to.lng - from.lng) * f];
    });
    // About 80 % of truck km run on toll roads (motorways, expressways) in the mock.
    const toll = (v: number) => Math.round(v * 0.8 * 10) / 10;
    const half = Math.round((distanceKm / 2) * 10) / 10;
    const countries =
      from.country === to.country
        ? [{ country: from.country, km: distanceKm, tollKm: toll(distanceKm) }]
        : [
            { country: from.country, km: half, tollKm: toll(half) },
            { country: to.country, km: Math.round((distanceKm - half) * 10) / 10, tollKm: toll(distanceKm - half) },
          ];
    const place = (p: { lat: number; lng: number; label: string }) => ({ lat: p.lat, lng: p.lng, label: p.label });
    return { from: place(from), to: place(to), distanceKm, durationMin: Math.round((distanceKm / 70) * 60), countries, polyline: encodePolyline(line, 6) };
  });
  if (legs.some((l) => l.distanceKm > 3000)) return fail(422, 'error.validation', { route: 'calc.route.not_found' });
  const body: CalcRoute = {
    distanceKm: Math.round(legs.reduce((s, l) => s + l.distanceKm, 0) * 10) / 10,
    durationMin: legs.reduce((s, l) => s + l.durationMin, 0),
    points: ps.map(({ lat, lng, label }) => ({ lat, lng, label })),
    legs,
    polylinePrecision: 6,
    attribution: '© OpenStreetMap contributors',
    truck: { heightM: 4, widthM: 2.55, lengthM: 16.5, weightT: 40, axleLoadT: 11.5, axles: 5, hazmat: false },
    breakdown: null,
  };
  const byCountry = new Map<string, { country: string; km: number; tollKm: number }>();
  for (const l of legs)
    for (const c of l.countries) {
      const s = byCountry.get(c.country) ?? { country: c.country, km: 0, tollKm: 0 };
      s.km = Math.round((s.km + c.km) * 10) / 10;
      s.tollKm = Math.round((s.tollKm + c.tollKm) * 10) / 10;
      byCountry.set(c.country, s);
    }
  body.breakdown = { countries: [...byCountry.values()], eta: null, days: 1, costs: { lines: [], totals: [], missing: [] }, payHidden: false };
  return { status: 200, body };
}
