import { api } from './client';

/*
 * Trip calculator step 1 (backend contract of 2026-11-02, being built): plans a trip forward under 561/2006 from the
 * drivers' state (tachograph, fresh or typed in) over manual legs, and prices it (fuel, driver pay, tolls, other).
 * POST /app/calc/trip needs trip.trip/view; driver pay lines only with driver.payment/view (else payHidden).
 * Toll tariffs per firma: read with trip.trip/view, changed with trip.trip/manage. Instants ISO UTC.
 */

export const DRIVER_STATES = ['TACHO', 'FRESH', 'MANUAL'] as const;
export type DriverStateSource = (typeof DRIVER_STATES)[number];

/** What the drivers have already used, when typed in by hand (driverState MANUAL). */
export interface ManualState {
  drivingSinceBreakMin: number;
  drivingInShiftMin: number;
  /** ISO instant */
  shiftStart: string | null;
  weekDrivingMin: number;
  fortnightDrivingMin: number;
  extensionsLeft: number;
  reducedRestsLeft: number;
  /** ISO instant */
  lastWeeklyRestEnd: string | null;
}

/** Km of a leg (or a route) in one country; tollKm: of them on toll roads, when the route knows it. */
export interface CountryKm {
  country: string;
  km: number;
  tollKm?: number | null;
}

export interface CalcLeg {
  name: string | null;
  distanceKm: number;
  /** Empty: distance / options.avgSpeedKmh. */
  durationMin: number | null;
  /** Empty: all km in options.country or the firma country. */
  countries: CountryKm[] | null;
  /** Loading, unloading, customs... at the end of the leg. */
  serviceMin: number | null;
}

export interface CalcOtherCost {
  label: string;
  amount: number;
  currency: string;
}

export interface CalcOptions {
  avgSpeedKmh: number | null;
  extendedDriving: boolean;
  reducedRests: boolean;
  reducedWeeklyRest: boolean;
  consumptionL100km: number | null;
  fuelPrice: number | null;
  fuelCurrency: string | null;
  otherCosts: CalcOtherCost[];
}

export interface CalcRequest {
  start: string;
  vehicleId: number | null;
  /** 0..2 drivers */
  driverIds: number[];
  crewSize: 1 | 2 | null;
  driverState: DriverStateSource | null;
  manualState: ManualState | null;
  legs: CalcLeg[];
  options: CalcOptions;
}

export const TIMELINE_KINDS = ['DRIVE', 'BREAK', 'DAILY_REST', 'REDUCED_DAILY_REST', 'WEEKLY_REST', 'REDUCED_WEEKLY_REST', 'SERVICE'] as const;
export type TimelineKind = (typeof TIMELINE_KINDS)[number];

export interface TimelineItem {
  kind: TimelineKind;
  start: string;
  end: string;
  minutes: number;
  kmFrom: number;
  kmTo: number;
  legIndex: number;
  /** Which driver drives (1 or 2, the order of driverIds); null for rests and service. */
  driver: 1 | 2 | null;
}

export interface CalcDriverState {
  driverId: number | null;
  source: DriverStateSource;
  /** Tachograph data until (ISO), for TACHO. */
  dataUntil: string | null;
  drivingSinceBreakMin: number;
  drivingInShiftMin: number;
  shiftStart: string | null;
  weekDrivingMin: number;
  fortnightDrivingMin: number;
  extensionsLeft: number;
  reducedRestsLeft: number;
  weeklyRestDueAt: string | null;
}

export const COST_KINDS = ['FUEL', 'DRIVER_KM', 'DRIVER_PER_DIEM', 'DRIVER_BASE', 'TOLL', 'OTHER'] as const;
export type CostKind = (typeof COST_KINDS)[number];

/**
 * Units: FUEL L, DRIVER_KM KM, DRIVER_PER_DIEM and DRIVER_BASE DAY, TOLL KM (one line per country). OTHER lines have no
 * quantity, unit nor rate; a TOLL line of a country without a tariff has no rate nor amount (calc.missing.toll_tariff).
 */
/**
 * Where a fuel price came from, best first (backend FuelPriceProvider.Quality): typed into the calculation, the firma's
 * contract, its own fuel card purchases in that country, a reference price with the firma's discount, the reference
 * price of the country, the firma's purchases in all countries (last resort), none.
 */
export const FUEL_SOURCES = ['MANUAL', 'CONTRACT', 'OWN_TX', 'REFERENCE_ADJUSTED', 'REFERENCE', 'OWN_TX_ALL', 'MISSING'] as const;
export type FuelSource = (typeof FUEL_SOURCES)[number];

export interface CostLine {
  kind: CostKind;
  label: string | null;
  country: string | null;
  quantity: number | null;
  unit: 'L' | 'KM' | 'DAY' | null;
  rate: number | null;
  amount: number | null;
  currency: string | null;
  /** FUEL only: where the price came from, and its day (YYYY-MM-DD). */
  source?: FuelSource | null;
  priceDate?: string | null;
}

export interface CalcPlan {
  start: string;
  eta: string;
  totals: {
    distanceKm: number;
    drivingMin: number;
    breakMin: number;
    restMin: number;
    workMin: number;
    durationMin: number;
    dailyRests: number;
    weeklyRests: number;
    /** Started 24 h periods from start to ETA, at least 1 (base of per diem and base pay). */
    days: number;
  };
  timeline: TimelineItem[];
  driverStates: CalcDriverState[];
  costs: {
    lines: CostLine[];
    totals: { currency: string; amount: number }[];
    /** calc.missing.* codes: what could not be priced. */
    missing: string[];
  };
  /** Driver pay left out: the user lacks driver.payment/view. */
  payHidden: boolean;
  /** calc.warning.* codes */
  warnings: string[];
}

export interface TollTariff {
  id: number;
  country: string;
  euroClass: string | null;
  axlesMin: number | null;
  weightMinKg: number | null;
  ratePerKm: number;
  currency: string;
  validFrom: string | null;
  notes: string | null;
}

export type TollTariffInput = Omit<TollTariff, 'id'>;

// --- truck routes (POST /app/calc/route, backend 4dfa4cb) --------------------------------------------

/** A point of the route: a place picked on the map or an address the backend geocodes. */
export type RoutePoint = { lat: number; lng: number } | { address: string };

export interface RouteRequest {
  /** 2-25 points in driving order. */
  points: RoutePoint[];
  /** Height, gross weight and axles of the vehicle go to the truck costing. */
  vehicleId: number | null;
  avoidTolls: boolean;
  avoidFerries: boolean;
}

/** A point of a routed leg: where it is and what it is called. */
export interface RoutePlace {
  lat: number;
  lng: number;
  label: string;
}

export interface RouteLeg {
  from: RoutePlace;
  to: RoutePlace;
  distanceKm: number;
  durationMin: number;
  /** Km per country from the edges of the route; they add up to the distance. */
  countries: CountryKm[];
  /** Encoded polyline (Google algorithm) with `polylinePrecision` decimals. */
  polyline: string;
}

export interface CalcRoute {
  distanceKm: number;
  durationMin: number;
  /** The points as routed (geocoded addresses get their coordinates and a label). */
  points: { lat: number; lng: number; label: string }[];
  legs: RouteLeg[];
  polylinePrecision: number;
  /** "© OpenStreetMap contributors": shown with the map. */
  attribution: string;
  /** The truck the route was found for: the vehicle card, else EU limits (backend 99b9986). */
  truck?: { heightM: number; widthM: number; lengthM: number; weightT: number; axleLoadT: number | null; axles: number; hazmat: boolean } | null;
  /** Km and toll km per country of the whole route, and its costs (backend 2346082). */
  breakdown?: { countries: CountryKm[]; eta: string | null; days: number; costs: CalcPlan['costs']; payHidden: boolean } | null;
}

/** A reference fuel price (GET /app/calc/fuel-prices, shared by all firmas): per litre, net without VAT. */
export interface FuelPrice {
  id: number;
  country: string;
  product: string;
  priceDate: string;
  net: number;
  gross: number | null;
  vatRate: number | null;
  excisePer1000l: number | null;
  currency: string;
  kind: string;
  source: string | null;
  fetchedAt: string | null;
  updatedAt: string | null;
}

/** A place suggested while typing (GET /app/calc/places, backend d67dafa). */
export interface PlaceSuggestion {
  label: string;
  lat: number;
  lng: number;
  country: string | null;
}

export const calcApi = {
  /** Fewer than 3 characters answer []; 429 api.rate_limited; 502/503 calc.geocode.failed (no or down provider). */
  fuelPrices: () => api<FuelPrice[]>('/calc/fuel-prices'),
  places: (q: string, limit = 5) => api<PlaceSuggestion[]>('/calc/places', { query: { q, limit } }),
  plan: (body: CalcRequest) => api<CalcPlan>('/calc/trip', { method: 'POST', body }),
  route: (body: RouteRequest) => api<CalcRoute>('/calc/route', { method: 'POST', body }),
  tolls: () => api<TollTariff[]>('/calc/toll-tariffs'),
  createToll: (body: TollTariffInput) => api<TollTariff>('/calc/toll-tariffs', { method: 'POST', body }),
  updateToll: (id: number, body: TollTariffInput) => api<TollTariff>(`/calc/toll-tariffs/${id}`, { method: 'PUT', body }),
  removeToll: (id: number) => api<void>(`/calc/toll-tariffs/${id}`, { method: 'DELETE' }),
};

export const TOLLS_KEY = ['calc', 'tolls'] as const;
export const FUEL_PRICES_KEY = ['calc', 'fuel-prices'] as const;
