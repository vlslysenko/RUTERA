import type {
  Contractor,
  ContractorInput,
  Order,
  OrderFilter,
  OrderInput,
  OrderStatus,
  Trip,
  TripFilter,
  TripFromOrder,
  TripInput,
  TripStatus,
  TripStop,
} from '@/api/trips';

/*
 * Contractors, orders and trips for the in-browser mock API, with the rules of OrderService / TripService:
 * numbers O-YYYY-NNNN / T-YYYY-NNNN, the order status follows its trip, stops are kept by id on a PUT.
 */

export interface MockReply {
  status: number;
  body?: unknown;
}

export interface CrewLookup {
  vehicle(id: number): { id: number; plate: string; trailer: boolean } | undefined;
  driverName(id: number): string | null;
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({
  status,
  body: details ? { code, details } : { code },
});
const invalid = (details: Record<string, string>) => fail(422, 'error.validation', details);
const notFound = () => fail(404, 'error.not_found');

function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
const at = (date: string, hour: number) => new Date(`${date}T${String(hour).padStart(2, '0')}:00:00Z`).toISOString();
const T0 = '2026-10-01T08:00:00Z';
const blank = (s: string | null | undefined) => (s ?? '').trim() || null;
const vatKey = (v: string) => v.replace(/[\s.-]+/g, '').toUpperCase();
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const NUMBER_RE = /^[\p{L}\p{N}][\p{L}\p{N} ./_-]*$/u;

// --- data --------------------------------------------------------------------------------------------

interface MockContractor extends Contractor {
  deleted: boolean;
}

const contractor = (c: Partial<Contractor> & Pick<Contractor, 'id' | 'name' | 'country'>): MockContractor => ({
  vatId: null,
  address: null,
  phone: null,
  invoiceEmails: [],
  paymentDays: 30,
  lang: null,
  isCustomer: true,
  isCarrier: false,
  notes: null,
  createdAt: T0,
  updatedAt: T0,
  deleted: false,
  ...c,
});

const contractors: MockContractor[] = [
  contractor({ id: 1, name: 'Spedpol Sp. z o.o.', vatId: 'PL8971234567', country: 'PL', address: 'ul. Legnicka 55, 54-203 Wrocław', phone: '+48 71 300 20 10', invoiceEmails: ['faktury@spedpol.example'], lang: 'pl' }),
  contractor({ id: 2, name: 'Nordfracht GmbH', vatId: 'DE298765432', country: 'DE', address: 'Hafenstraße 12, 28217 Bremen', invoiceEmails: ['ap@nordfracht.example', 'dispo@nordfracht.example'], paymentDays: 45, lang: 'de' }),
  contractor({ id: 3, name: 'Logistica Iberia S.L.', vatId: 'ESB12345678', country: 'ES', paymentDays: 60, lang: 'es', isCarrier: true }),
  contractor({ id: 4, name: 'Trans-Kowal', vatId: 'PL6112223344', country: 'PL', isCustomer: false, isCarrier: true, paymentDays: 14 }),
];

interface MockOrder extends Omit<Order, 'customer' | 'trip'> {
  customerId: number;
  tripId: number | null;
}

const order = (o: Partial<MockOrder> & Pick<MockOrder, 'id' | 'number' | 'customerId' | 'loading' | 'unloading'>): MockOrder => ({
  orderDate: inDays(-7),
  customerRef: null,
  price: null,
  currency: null,
  cargo: null,
  weightKg: null,
  status: 'NEW',
  tripId: null,
  notes: null,
  createdAt: T0,
  updatedAt: T0,
  ...o,
});

const Y = new Date().getFullYear();
const orders: MockOrder[] = [
  order({ id: 1, number: `O-${Y}-0001`, customerId: 1, customerRef: 'SP/4471', price: 4200, currency: 'EUR', cargo: 'Palety, AGD', weightKg: 18_500, loading: { place: 'Wrocław', country: 'PL', date: inDays(-3) }, unloading: { place: 'Lyon', country: 'FR', date: inDays(-1) }, status: 'DONE', tripId: 1 }),
  order({ id: 2, number: `O-${Y}-0002`, customerId: 2, customerRef: 'NF-22817', price: 3150, currency: 'EUR', cargo: 'Stal w kręgach', weightKg: 24_000, loading: { place: 'Bremen', country: 'DE', date: inDays(0) }, unloading: { place: 'Poznań', country: 'PL', date: inDays(1) }, status: 'IN_PROGRESS', tripId: 2 }),
  order({ id: 3, number: `O-${Y}-0003`, customerId: 1, price: 9800, currency: 'PLN', cargo: 'Mrożonki', weightKg: 21_000, loading: { place: 'Kąty Wrocławskie', country: 'PL', date: inDays(2) }, unloading: { place: 'Gdańsk', country: 'PL', date: inDays(2) }, status: 'PLANNED', tripId: 3 }),
  order({ id: 4, number: `O-${Y}-0004`, customerId: 3, customerRef: 'LI-5521', price: 3600, currency: 'EUR', cargo: 'Owoce', weightKg: 20_000, loading: { place: 'Valencia', country: 'ES', date: inDays(4) }, unloading: { place: 'Wrocław', country: 'PL', date: inDays(7) } }),
  order({ id: 5, number: `O-${Y}-0005`, customerId: 2, price: 2900, currency: 'EUR', loading: { place: 'Hamburg', country: 'DE', date: inDays(5) }, unloading: { place: 'Praha', country: 'CZ', date: inDays(6) } }),
  order({ id: 6, number: `O-${Y}-0006`, customerId: 1, loading: { place: 'Legnica', country: 'PL', date: inDays(-10) }, unloading: { place: 'Berlin', country: 'DE', date: inDays(-9) }, status: 'CANCELLED', notes: 'Klient odwołał.' }),
];

interface MockTrip extends Omit<Trip, 'vehicle' | 'trailer' | 'drivers' | 'orders'> {
  vehicleId: number;
  trailerId: number | null;
  mainDriverId: number | null;
  secondDriverId: number | null;
}

let stopId = 100;
const stop = (s: Omit<TripStop, 'id' | 'seq'>): TripStop => ({ ...s, id: ++stopId, seq: 0 });
const numbered = (stops: TripStop[]) => stops.map((s, i) => ({ ...s, seq: i + 1 }));

const trips: MockTrip[] = [
  {
    id: 1, number: `T-${Y}-0001`, status: 'DONE', vehicleId: 1, trailerId: 6, mainDriverId: 1, secondDriverId: null,
    plannedStart: at(inDays(-3), 6), plannedEnd: at(inDays(-1), 18), actualStart: at(inDays(-3), 6), actualEnd: at(inDays(-1), 15),
    kmPlanned: 1250, kmActual: 1287, notes: null, createdAt: T0, updatedAt: T0,
    stops: numbered([
      stop({ kind: 'LOADING', orderId: 1, name: 'Spedpol magazyn', address: 'Wrocław', country: 'PL', plannedAt: at(inDays(-3), 7), actualAt: at(inDays(-3), 7), status: 'DONE' }),
      stop({ kind: 'BORDER', orderId: null, name: null, address: 'Jędrzychowice / Ludwigsdorf', country: 'DE', plannedAt: null, actualAt: at(inDays(-3), 10), status: 'DONE' }),
      stop({ kind: 'UNLOADING', orderId: 1, name: null, address: 'Lyon', country: 'FR', plannedAt: at(inDays(-1), 9), actualAt: at(inDays(-1), 11), status: 'DONE' }),
    ]),
  },
  {
    id: 2, number: `T-${Y}-0002`, status: 'RUNNING', vehicleId: 2, trailerId: 7, mainDriverId: 2, secondDriverId: 3,
    plannedStart: at(inDays(0), 5), plannedEnd: at(inDays(1), 16), actualStart: at(inDays(0), 5), actualEnd: null,
    kmPlanned: 560, kmActual: null, notes: 'Podwójna obsada.', createdAt: T0, updatedAt: T0,
    stops: numbered([
      stop({ kind: 'LOADING', orderId: 2, name: 'Nordfracht Terminal 3', address: 'Bremen', country: 'DE', plannedAt: at(inDays(0), 8), actualAt: at(inDays(0), 8), status: 'DONE' }),
      stop({ kind: 'FUEL', orderId: null, name: 'DKV', address: 'A2, Bad Eilsen', country: 'DE', plannedAt: null, actualAt: null, status: 'PLANNED' }),
      stop({ kind: 'UNLOADING', orderId: 2, name: null, address: 'Poznań', country: 'PL', plannedAt: at(inDays(1), 10), actualAt: null, status: 'PLANNED' }),
    ]),
  },
  {
    id: 3, number: `T-${Y}-0003`, status: 'PLANNED', vehicleId: 3, trailerId: null, mainDriverId: 4, secondDriverId: null,
    plannedStart: at(inDays(2), 4), plannedEnd: at(inDays(2), 18), actualStart: null, actualEnd: null,
    kmPlanned: 480, kmActual: null, notes: null, createdAt: T0, updatedAt: T0,
    stops: numbered([
      stop({ kind: 'LOADING', orderId: 3, name: null, address: 'Kąty Wrocławskie', country: 'PL', plannedAt: at(inDays(2), 6), actualAt: null, status: 'PLANNED' }),
      stop({ kind: 'UNLOADING', orderId: 3, name: null, address: 'Gdańsk', country: 'PL', plannedAt: at(inDays(2), 15), actualAt: null, status: 'PLANNED' }),
    ]),
  },
];

// --- shared ------------------------------------------------------------------------------------------

const nextId = (items: { id: number }[]) => Math.max(0, ...items.map((x) => x.id)) + 1;

function nextNumber(prefix: string, taken: string[]): string {
  const n = Math.max(0, ...taken.filter((x) => x.startsWith(prefix)).map((x) => Number(x.slice(prefix.length)) || 0)) + 1;
  return `${prefix}${String(n).padStart(4, '0')}`;
}

function page<T>(items: T[], p?: string, size?: string) {
  const s = Math.min(Math.max(Number(size ?? 50) || 50, 1), 200);
  const from = (Number(p ?? 0) || 0) * s;
  return { items: items.slice(from, from + s), total: items.length };
}

const ref = (c: MockContractor) => ({ id: c.id, name: c.name });
const liveContractor = (id: number) => contractors.find((c) => c.id === id && !c.deleted);

/** Order status that follows its trip (CANCELLED and INVOICED stay). */
function followTrip(o: MockOrder): void {
  if (o.status === 'CANCELLED' || o.status === 'INVOICED') return;
  const t = o.tripId == null ? undefined : trips.find((x) => x.id === o.tripId);
  const byTrip: Record<TripStatus, OrderStatus> = { PLANNED: 'PLANNED', RUNNING: 'IN_PROGRESS', DONE: 'DONE', CANCELLED: 'NEW' };
  o.status = t ? byTrip[t.status] : 'NEW';
  if (t?.status === 'CANCELLED') o.tripId = null;
}

function toOrder(o: MockOrder): Order {
  const c = contractors.find((x) => x.id === o.customerId)!;
  const t = o.tripId == null ? undefined : trips.find((x) => x.id === o.tripId);
  const { customerId: _c, tripId: _t, ...rest } = o;
  return { ...rest, customer: ref(c), trip: t ? { id: t.id, number: t.number, status: t.status } : null };
}

function toTrip(t: MockTrip, crew: CrewLookup): Trip {
  const v = crew.vehicle(t.vehicleId);
  const tr = t.trailerId == null ? undefined : crew.vehicle(t.trailerId);
  const drivers: Trip['drivers'] = [];
  if (t.mainDriverId != null) drivers.push({ id: t.mainDriverId, name: crew.driverName(t.mainDriverId) ?? '?', role: 'MAIN' });
  if (t.secondDriverId != null) drivers.push({ id: t.secondDriverId, name: crew.driverName(t.secondDriverId) ?? '?', role: 'SECOND' });
  const { vehicleId: _v, trailerId: _tr, mainDriverId: _m, secondDriverId: _s, ...rest } = t;
  return {
    ...rest,
    vehicle: { id: t.vehicleId, plate: v?.plate ?? '?' },
    trailer: tr ? { id: tr.id, plate: tr.plate } : null,
    drivers,
    orders: orders
      .filter((o) => o.tripId === t.id)
      .map((o) => ({ id: o.id, number: o.number, customer: ref(contractors.find((c) => c.id === o.customerId)!), price: o.price, currency: o.currency })),
  };
}

function crewErrors(b: Partial<TripInput>, crew: CrewLookup, errors: Record<string, string>): void {
  const v = b.vehicleId == null ? undefined : crew.vehicle(b.vehicleId);
  if (b.vehicleId == null) errors.vehicleId = 'validation.required';
  else if (!v || v.trailer) errors.vehicleId = 'validation.invalid_value';
  if (b.trailerId != null && !crew.vehicle(b.trailerId)?.trailer) errors.trailerId = 'validation.invalid_value';
  if (b.mainDriverId != null && !crew.driverName(b.mainDriverId)) errors.mainDriverId = 'validation.invalid_value';
  if (b.secondDriverId != null && (b.mainDriverId == null || b.secondDriverId === b.mainDriverId || !crew.driverName(b.secondDriverId))) {
    errors.secondDriverId = 'validation.invalid_value';
  }
}

// --- contractors -------------------------------------------------------------------------------------

export function listContractors(q?: string, role?: string): MockReply {
  const needle = (q ?? '').trim().toLowerCase();
  const items = contractors
    .filter((c) => !c.deleted)
    .filter((c) => !needle || c.name.toLowerCase().includes(needle) || (c.vatId ?? '').toLowerCase().includes(needle))
    .filter((c) => (role === 'CUSTOMER' ? c.isCustomer : role === 'CARRIER' ? c.isCarrier : true))
    .sort((a, b) => a.name.localeCompare(b.name));
  return { status: 200, body: items.map(({ deleted: _d, ...c }) => c) };
}

export function getContractor(id: string): MockReply {
  const c = liveContractor(Number(id));
  if (!c) return notFound();
  const { deleted: _d, ...rest } = c;
  return { status: 200, body: rest };
}

export function saveContractor(b: ContractorInput, id: string | null): MockReply {
  const target = id == null ? undefined : liveContractor(Number(id));
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  if (!blank(b.name)) errors.name = 'validation.required';
  if (!b.country) errors.country = 'validation.required';
  const vat = b.vatId ? vatKey(b.vatId) : null;
  if (vat && contractors.some((c) => !c.deleted && c.vatId === vat && c.id !== target?.id)) errors.vatId = 'trip.contractor.vat_taken';
  if ((b.invoiceEmails ?? []).length > 5) errors.invoiceEmails = 'validation.too_long';
  else if ((b.invoiceEmails ?? []).some((e) => !EMAIL_RE.test(e))) errors.invoiceEmails = 'validation.invalid_format';
  if (b.paymentDays != null && (b.paymentDays < 0 || b.paymentDays > 365)) errors.paymentDays = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  const value = { ...b, name: b.name.trim(), vatId: vat, isCustomer: b.isCustomer ?? true, isCarrier: b.isCarrier ?? false, updatedAt: now };
  if (target) {
    Object.assign(target, value);
    return getContractor(String(target.id));
  }
  const created: MockContractor = { ...value, id: nextId(contractors), createdAt: now, deleted: false };
  contractors.push(created);
  return { ...getContractor(String(created.id)), status: 201 };
}

export function removeContractor(id: string): MockReply {
  const c = liveContractor(Number(id));
  if (!c) return notFound();
  c.deleted = true;
  return { status: 204 };
}

// --- orders ------------------------------------------------------------------------------------------

export function listOrders(query: Record<string, string>): MockReply {
  const f = query as Partial<Record<keyof OrderFilter, string>>;
  const needle = (f.q ?? '').trim().toLowerCase();
  const items = orders
    .filter((o) => !f.status || o.status === f.status)
    .filter((o) => !f.customerId || String(o.customerId) === f.customerId)
    .filter((o) => !f.from || o.orderDate >= f.from)
    .filter((o) => !f.to || o.orderDate <= f.to)
    .filter((o) => {
      if (!needle) return true;
      const c = contractors.find((x) => x.id === o.customerId);
      return [o.number, o.customerRef ?? '', c?.name ?? ''].some((s) => s.toLowerCase().includes(needle));
    })
    .sort((a, b) => b.orderDate.localeCompare(a.orderDate) || b.id - a.id)
    .map(toOrder);
  return { status: 200, body: page(items, f.page, f.size) };
}

export function getOrder(id: string): MockReply {
  const o = orders.find((x) => String(x.id) === id);
  return o ? { status: 200, body: toOrder(o) } : notFound();
}

export function saveOrder(b: OrderInput, id: string | null): MockReply {
  const target = id == null ? undefined : orders.find((x) => String(x.id) === id);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  const number = blank(b.number)?.toUpperCase() ?? null;
  if (number && !NUMBER_RE.test(number)) errors.number = 'validation.invalid_format';
  else if (number && orders.some((o) => o.number === number && o.id !== target?.id)) errors.number = 'trip.order.number_taken';
  if (!b.orderDate) errors.orderDate = 'validation.required';
  if (b.customerId == null) errors.customerId = 'validation.required';
  else if (!liveContractor(b.customerId)) errors.customerId = 'validation.invalid_value';
  if (b.price != null && !blank(b.currency)) errors.currency = 'validation.required';
  if (!blank(b.loadingPlace)) errors.loadingPlace = 'validation.required';
  if (!b.loadingCountry) errors.loadingCountry = 'validation.required';
  if (!blank(b.unloadingPlace)) errors.unloadingPlace = 'validation.required';
  if (!b.unloadingCountry) errors.unloadingCountry = 'validation.required';
  if (b.loadingDate && b.unloadingDate && b.unloadingDate < b.loadingDate) errors.unloadingDate = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);

  const now = new Date().toISOString();
  const o: MockOrder =
    target ??
    order({
      id: nextId(orders),
      number: number ?? nextNumber(`O-${b.orderDate.slice(0, 4)}-`, orders.map((x) => x.number)),
      customerId: b.customerId,
      loading: { place: '', country: '', date: null },
      unloading: { place: '', country: '', date: null },
      createdAt: now,
    });
  if (number) o.number = number;
  Object.assign(o, {
    orderDate: b.orderDate,
    customerId: b.customerId,
    customerRef: blank(b.customerRef),
    price: b.price,
    currency: b.price == null ? null : (b.currency ?? '').toUpperCase(),
    cargo: blank(b.cargo),
    weightKg: b.weightKg,
    loading: { place: b.loadingPlace.trim(), country: b.loadingCountry, date: b.loadingDate },
    unloading: { place: b.unloadingPlace.trim(), country: b.unloadingCountry, date: b.unloadingDate },
    notes: blank(b.notes),
    updatedAt: now,
  });
  if (b.cancelled && o.status !== 'CANCELLED') {
    const t = trips.find((x) => x.id === o.tripId);
    if (t) t.stops = numbered(t.stops.filter((s) => s.orderId !== o.id));
    o.tripId = null;
    o.status = 'CANCELLED';
  } else if (!b.cancelled && o.status === 'CANCELLED') {
    o.status = 'NEW';
  }
  if (!target) orders.push(o);
  return { status: target ? 200 : 201, body: toOrder(o) };
}

export function removeOrder(id: string): MockReply {
  const i = orders.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  if (orders[i].tripId != null || orders[i].status === 'INVOICED') return fail(409, 'error.conflict');
  orders.splice(i, 1);
  return { status: 204 };
}

export function createTripForOrder(id: string, b: TripFromOrder, crew: CrewLookup): MockReply {
  const o = orders.find((x) => String(x.id) === id);
  if (!o) return notFound();
  if (o.tripId != null || o.status !== 'NEW') return fail(409, 'error.conflict');
  const errors: Record<string, string> = {};
  crewErrors(b, crew, errors);
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  const start = b.plannedStart ?? (o.loading.date ? at(o.loading.date, 0) : null);
  const end = o.unloading.date ? at(o.unloading.date, 0) : null;
  const t: MockTrip = {
    id: nextId(trips),
    number: nextNumber(`T-${new Date().getUTCFullYear()}-`, trips.map((x) => x.number)),
    status: 'PLANNED',
    vehicleId: b.vehicleId,
    trailerId: b.trailerId,
    mainDriverId: b.mainDriverId,
    secondDriverId: b.secondDriverId,
    plannedStart: start,
    plannedEnd: end,
    actualStart: null,
    actualEnd: null,
    kmPlanned: null,
    kmActual: null,
    notes: null,
    createdAt: now,
    updatedAt: now,
    stops: numbered([
      stop({ kind: 'LOADING', orderId: o.id, name: null, address: o.loading.place, country: o.loading.country, plannedAt: o.loading.date ? at(o.loading.date, 0) : null, actualAt: null, status: 'PLANNED' }),
      stop({ kind: 'UNLOADING', orderId: o.id, name: null, address: o.unloading.place, country: o.unloading.country, plannedAt: o.unloading.date ? at(o.unloading.date, 0) : null, actualAt: null, status: 'PLANNED' }),
    ]),
  };
  trips.push(t);
  o.tripId = t.id;
  followTrip(o);
  return { status: 201, body: toTrip(t, crew) };
}

// --- trips -------------------------------------------------------------------------------------------

export function listTrips(query: Record<string, string>, crew: CrewLookup): MockReply {
  const f = query as Partial<Record<keyof TripFilter, string>>;
  const needle = (f.q ?? '').trim().toLowerCase();
  const items = trips
    .filter((t) => !f.status || t.status === f.status)
    .filter((t) => !f.vehicleId || String(t.vehicleId) === f.vehicleId || String(t.trailerId) === f.vehicleId)
    .filter((t) => !f.driverId || String(t.mainDriverId) === f.driverId || String(t.secondDriverId) === f.driverId)
    .filter((t) => !f.from || (t.plannedEnd ?? t.plannedStart ?? '9999') >= f.from)
    .filter((t) => !f.to || (t.plannedStart ?? '0000') <= `${f.to}T23:59:59Z`)
    .map((t) => toTrip(t, crew))
    .filter((t) => !needle || [t.number, t.vehicle.plate, ...t.orders.map((o) => o.number)].some((s) => s.toLowerCase().includes(needle)))
    .sort((a, b) => (b.plannedStart ?? '').localeCompare(a.plannedStart ?? '') || b.id - a.id);
  return { status: 200, body: page(items, f.page, f.size) };
}

export function getTrip(id: string, crew: CrewLookup): MockReply {
  const t = trips.find((x) => String(x.id) === id);
  return t ? { status: 200, body: toTrip(t, crew) } : notFound();
}

export function saveTrip(b: TripInput, id: string | null, crew: CrewLookup): MockReply {
  const target = id == null ? undefined : trips.find((x) => String(x.id) === id);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  const number = blank(b.number)?.toUpperCase() ?? null;
  if (number && !NUMBER_RE.test(number)) errors.number = 'validation.invalid_format';
  else if (number && trips.some((t) => t.number === number && t.id !== target?.id)) errors.number = 'trip.trip.number_taken';
  crewErrors(b, crew, errors);
  const orderIds = b.orderIds ?? [];
  if (orderIds.some((oid) => {
    const o = orders.find((x) => x.id === oid);
    return !o || o.status === 'CANCELLED' || (o.tripId != null && o.tripId !== target?.id);
  })) errors.orderIds = 'validation.invalid_value';
  const stops = b.stops ?? [];
  if (stops.length > 100) errors.stops = 'validation.too_long';
  stops.forEach((s, i) => {
    if (!blank(s.address)) errors[`stops[${i}].address`] = 'validation.required';
    if (!s.country) errors[`stops[${i}].country`] = 'validation.required';
    if (s.id != null && !target?.stops.some((x) => x.id === s.id)) errors[`stops[${i}].id`] = 'validation.invalid_value';
    if (s.orderId != null && !orderIds.includes(s.orderId)) errors[`stops[${i}].orderId`] = 'validation.invalid_value';
  });
  if (Object.keys(errors).length) return invalid(errors);

  const now = new Date().toISOString();
  const status = b.status ?? 'PLANNED';
  const t: MockTrip = target ?? {
    id: nextId(trips),
    number: number ?? nextNumber(`T-${new Date().getUTCFullYear()}-`, trips.map((x) => x.number)),
    status,
    vehicleId: b.vehicleId,
    trailerId: null,
    mainDriverId: null,
    secondDriverId: null,
    plannedStart: null,
    plannedEnd: null,
    actualStart: null,
    actualEnd: null,
    kmPlanned: null,
    kmActual: null,
    stops: [],
    notes: null,
    createdAt: now,
    updatedAt: now,
  };
  if (number) t.number = number;
  Object.assign(t, {
    status,
    vehicleId: b.vehicleId,
    trailerId: b.trailerId,
    mainDriverId: b.mainDriverId,
    secondDriverId: b.secondDriverId,
    plannedStart: b.plannedStart,
    plannedEnd: b.plannedEnd,
    actualStart: b.actualStart ?? (status === 'RUNNING' || status === 'DONE' ? now : null),
    actualEnd: b.actualEnd ?? (status === 'DONE' ? now : null),
    kmPlanned: b.kmPlanned,
    kmActual: b.kmActual,
    notes: blank(b.notes),
    updatedAt: now,
    stops: numbered(
      stops.map((s) => ({
        id: s.id ?? ++stopId,
        seq: 0,
        kind: s.kind,
        orderId: s.orderId,
        name: blank(s.name),
        address: s.address.trim(),
        country: s.country,
        plannedAt: s.plannedAt,
        actualAt: s.status === 'DONE' ? (s.actualAt ?? now) : s.status === 'PLANNED' ? null : s.actualAt,
        status: s.status ?? 'PLANNED',
      })),
    ),
  });
  if (!target) trips.push(t);
  for (const o of orders) {
    if (o.tripId === t.id && !orderIds.includes(o.id)) o.tripId = null;
    if (orderIds.includes(o.id)) o.tripId = t.id;
  }
  for (const o of orders) if (o.tripId === t.id || orderIds.includes(o.id)) followTrip(o);
  for (const o of orders) if (o.tripId == null && o.status !== 'CANCELLED' && o.status !== 'INVOICED') o.status = 'NEW';
  return { status: target ? 200 : 201, body: toTrip(t, crew) };
}

export function removeTrip(id: string): MockReply {
  const i = trips.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  if (trips[i].status !== 'PLANNED' && trips[i].status !== 'CANCELLED') return fail(409, 'error.conflict');
  for (const o of orders) {
    if (o.tripId === trips[i].id) {
      o.tripId = null;
      if (o.status !== 'CANCELLED' && o.status !== 'INVOICED') o.status = 'NEW';
    }
  }
  trips.splice(i, 1);
  return { status: 204 };
}

export function markStop(id: string, sid: string, b: { status?: string; actualAt?: string | null }): MockReply {
  const t = trips.find((x) => String(x.id) === id);
  const s = t?.stops.find((x) => String(x.id) === sid);
  if (!t || !s) return notFound();
  if (b.status !== 'PLANNED' && b.status !== 'DONE' && b.status !== 'SKIPPED') return invalid({ status: 'validation.invalid_value' });
  s.status = b.status;
  s.actualAt = b.status === 'PLANNED' ? null : (b.actualAt ?? (b.status === 'DONE' ? new Date().toISOString() : s.actualAt));
  t.updatedAt = new Date().toISOString();
  return { status: 200, body: s };
}

// --- for the finance mock ----------------------------------------------------------------------------

/** What an invoice needs to know about a contractor. */
export function contractorForInvoice(id: number): { id: number; name: string; paymentDays: number | null; invoiceEmails: string[] } | undefined {
  const c = liveContractor(id);
  return c && { id: c.id, name: c.name, paymentDays: c.paymentDays, invoiceEmails: c.invoiceEmails };
}

export function orderForInvoice(id: number): { id: number; number: string; customerId: number; status: OrderStatus } | undefined {
  const o = orders.find((x) => x.id === id);
  return o && { id: o.id, number: o.number, customerId: o.customerId, status: o.status };
}

export function tripForInvoice(id: number): { id: number; number: string } | undefined {
  const t = trips.find((x) => x.id === id);
  return t && { id: t.id, number: t.number };
}

/** Linked orders become INVOICED; unlinked ones go back to the status their trip gives them. */
export function setOrdersInvoiced(ids: number[], invoiced: boolean): void {
  for (const o of orders.filter((x) => ids.includes(x.id))) {
    if (invoiced) o.status = 'INVOICED';
    else if (o.status === 'INVOICED') {
      o.status = 'NEW';
      followTrip(o);
    }
  }
}

/** For the fuel mock: the trip of a vehicle whose actual (else planned) period contains the instant, with its main driver. */
export function tripAtForFuel(vehicleId: number, at: string): { id: number; number: string; mainDriverId: number | null } | undefined {
  const t = trips.find((x) => {
    if (x.vehicleId !== vehicleId || x.status === 'CANCELLED') return false;
    const from = x.actualStart ?? x.plannedStart;
    const to = x.actualEnd ?? x.plannedEnd ?? (x.status === 'RUNNING' ? new Date().toISOString() : null);
    return !!from && !!to && from <= at && at <= to;
  });
  return t && { id: t.id, number: t.number, mainDriverId: t.mainDriverId };
}
