import type { VehicleRef } from './fleet';
import { api } from './client';

/*
 * Contractors, orders and trips (backend b821f01, README "Contractors, orders and trips").
 * Privileges: contractors read with trip.order/view or trip.contractor/manage, changed with trip.contractor/manage;
 * orders trip.order/view / trip.order/manage; trips trip.trip/view / trip.trip/manage; POST /orders/{id}/trip needs
 * both manage keys. Dates YYYY-MM-DD, instants ISO UTC, money = number with 2 decimals + ISO currency.
 */

// --- contractors -------------------------------------------------------------------------------------

export interface Contractor {
  id: number;
  name: string;
  /** Upper case without spaces, dots and hyphens; unique in the firma (trip.contractor.vat_taken). */
  vatId: string | null;
  /** ISO 3166 alpha-2 */
  country: string;
  address: string | null;
  phone: string | null;
  /** Up to 5 addresses for invoices and reminders. */
  invoiceEmails: string[];
  paymentDays: number | null;
  /** Language of invoices and reminders (one of the 26). */
  lang: string | null;
  isCustomer: boolean;
  isCarrier: boolean;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ContractorInput = Omit<Contractor, 'id' | 'createdAt' | 'updatedAt'>;

export interface ContractorRef {
  id: number;
  name: string;
}

// --- orders ------------------------------------------------------------------------------------------

export const ORDER_STATUSES = ['NEW', 'PLANNED', 'IN_PROGRESS', 'DONE', 'INVOICED', 'CANCELLED'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export interface OrderPlace {
  place: string;
  country: string;
  /** YYYY-MM-DD */
  date: string | null;
}

export interface Order {
  id: number;
  number: string;
  orderDate: string;
  customer: ContractorRef;
  customerRef: string | null;
  price: number | null;
  currency: string | null;
  cargo: string | null;
  weightKg: number | null;
  loading: OrderPlace;
  unloading: OrderPlace;
  /** NEW without a trip; follows the trip otherwise; CANCELLED by hand; INVOICED with invoices. */
  status: OrderStatus;
  trip: { id: number; number: string; status: TripStatus } | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrderInput {
  /** Empty: the next number of the year (O-2026-0001). */
  number: string | null;
  orderDate: string;
  customerId: number;
  customerRef: string | null;
  price: number | null;
  /** Required with price. */
  currency: string | null;
  cargo: string | null;
  weightKg: number | null;
  loadingPlace: string;
  loadingCountry: string;
  loadingDate: string | null;
  unloadingPlace: string;
  unloadingCountry: string;
  unloadingDate: string | null;
  /** true: CANCELLED (and off its trip). */
  cancelled: boolean;
  notes: string | null;
}

export interface OrderFilter {
  status?: OrderStatus;
  customerId?: number;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface TripFromOrder {
  vehicleId: number;
  trailerId: number | null;
  mainDriverId: number | null;
  secondDriverId: number | null;
  /** ISO; empty: the start of the loading day (UTC). */
  plannedStart: string | null;
}

// --- trips -------------------------------------------------------------------------------------------

export const TRIP_STATUSES = ['PLANNED', 'RUNNING', 'DONE', 'CANCELLED'] as const;
export type TripStatus = (typeof TRIP_STATUSES)[number];

export const STOP_KINDS = ['LOADING', 'UNLOADING', 'BORDER', 'REST', 'FUEL', 'CUSTOMS', 'OTHER'] as const;
export type StopKind = (typeof STOP_KINDS)[number];

export const STOP_STATUSES = ['PLANNED', 'DONE', 'SKIPPED'] as const;
export type StopStatus = (typeof STOP_STATUSES)[number];

export interface TripStop {
  id: number;
  seq: number;
  kind: StopKind;
  orderId: number | null;
  name: string | null;
  address: string;
  country: string;
  plannedAt: string | null;
  actualAt: string | null;
  status: StopStatus;
}

export interface Trip {
  id: number;
  number: string;
  status: TripStatus;
  vehicle: VehicleRef;
  trailer: VehicleRef | null;
  drivers: { id: number; name: string; role: 'MAIN' | 'SECOND' }[];
  plannedStart: string | null;
  plannedEnd: string | null;
  actualStart: string | null;
  actualEnd: string | null;
  kmPlanned: number | null;
  kmActual: number | null;
  orders: { id: number; number: string; customer: ContractorRef; price: number | null; currency: string | null }[];
  stops: TripStop[];
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TripStopInput {
  /** Present: update that stop; absent: a new one. Stops left out are deleted; seq = position. */
  id?: number | null;
  kind: StopKind;
  orderId: number | null;
  name: string | null;
  address: string;
  country: string;
  plannedAt: string | null;
  actualAt: string | null;
  status: StopStatus;
}

export interface TripInput {
  number: string | null;
  vehicleId: number;
  trailerId: number | null;
  mainDriverId: number | null;
  secondDriverId: number | null;
  status: TripStatus;
  plannedStart: string | null;
  plannedEnd: string | null;
  actualStart: string | null;
  actualEnd: string | null;
  kmPlanned: number | null;
  kmActual: number | null;
  orderIds: number[];
  stops: TripStopInput[];
  notes: string | null;
}

export interface TripFilter {
  status?: TripStatus;
  vehicleId?: number;
  driverId?: number;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface Page<T> {
  items: T[];
  total: number;
}

export const contractorsApi = {
  list: (q?: string, role?: 'CUSTOMER' | 'CARRIER') => api<Contractor[]>('/contractors', { query: { q, role } }),
  get: (id: number | string) => api<Contractor>(`/contractors/${id}`),
  create: (body: ContractorInput) => api<Contractor>('/contractors', { method: 'POST', body }),
  update: (id: number | string, body: ContractorInput) => api<Contractor>(`/contractors/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/contractors/${id}`, { method: 'DELETE' }),
};

export const ordersApi = {
  list: (filter: OrderFilter) => api<Page<Order>>('/orders', { query: { ...filter } }),
  get: (id: number | string) => api<Order>(`/orders/${id}`),
  create: (body: OrderInput) => api<Order>('/orders', { method: 'POST', body }),
  update: (id: number | string, body: OrderInput) => api<Order>(`/orders/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/orders/${id}`, { method: 'DELETE' }),
  createTrip: (id: number | string, body: TripFromOrder) => api<Trip>(`/orders/${id}/trip`, { method: 'POST', body }),
};

export const tripsApi = {
  list: (filter: TripFilter) => api<Page<Trip>>('/trips', { query: { ...filter } }),
  get: (id: number | string) => api<Trip>(`/trips/${id}`),
  create: (body: TripInput) => api<Trip>('/trips', { method: 'POST', body }),
  update: (id: number | string, body: TripInput) => api<Trip>(`/trips/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/trips/${id}`, { method: 'DELETE' }),
  markStop: (id: number | string, stopId: number, status: StopStatus, actualAt?: string | null) =>
    api<TripStop>(`/trips/${id}/stops/${stopId}`, { method: 'PATCH', body: { status, actualAt: actualAt ?? null } }),
};

export const CONTRACTORS_KEY = ['contractors'] as const;
export const ORDERS_KEY = ['orders'] as const;
export const TRIPS_KEY = ['trips'] as const;

/** Full replacement body of a trip from its current state (used for status changes and the form). */
export function tripToInput(t: Trip): TripInput {
  return {
    number: t.number,
    vehicleId: t.vehicle.id,
    trailerId: t.trailer?.id ?? null,
    mainDriverId: t.drivers.find((d) => d.role === 'MAIN')?.id ?? null,
    secondDriverId: t.drivers.find((d) => d.role === 'SECOND')?.id ?? null,
    status: t.status,
    plannedStart: t.plannedStart,
    plannedEnd: t.plannedEnd,
    actualStart: t.actualStart,
    actualEnd: t.actualEnd,
    kmPlanned: t.kmPlanned,
    kmActual: t.kmActual,
    orderIds: t.orders.map((o) => o.id),
    stops: t.stops.map((s) => ({
      id: s.id,
      kind: s.kind,
      orderId: s.orderId,
      name: s.name,
      address: s.address,
      country: s.country,
      plannedAt: s.plannedAt,
      actualAt: s.actualAt,
      status: s.status,
    })),
    notes: t.notes,
  };
}
