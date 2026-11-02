import { api, apiDownload } from './client';
import type { Page } from './trips';

/*
 * CMR consignment notes (backend 7c01015, README "CMR consignment notes"). Read with trip.order/view or
 * trip.cmr/manage, everything else trip.cmr/manage. PUT / DELETE only while DRAFT (409 otherwise). Box numbers of the
 * international form in the comments. Dates YYYY-MM-DD, instants ISO UTC, countries ISO alpha-2.
 */

export const CMR_STATUSES = ['DRAFT', 'ISSUED', 'CANCELLED'] as const;
export type CmrStatus = (typeof CMR_STATUSES)[number];

export const PAYMENT_TERMS = ['PAID', 'FORWARD'] as const;
export type PaymentTerms = (typeof PAYMENT_TERMS)[number];

/** Boxes 1, 2, 16, 17. The address may hold line breaks. */
export interface CmrParty {
  name: string | null;
  address: string | null;
  country: string | null;
  vatId: string | null;
}

/** Boxes 6-12. */
export interface CmrLine {
  marks: string | null;
  packages: number | null;
  packing: string | null;
  nature: string | null;
  statNumber: string | null;
  grossWeightKg: number | null;
  volumeM3: number | null;
}

export interface CmrSignature {
  name: string | null;
  place: string | null;
  date: string | null;
}

/** Boxes 22 (sender), 23 (carrier), 24 (consignee). */
export interface CmrSignatures {
  sender: CmrSignature | null;
  carrier: CmrSignature | null;
  consignee: CmrSignature | null;
}

export interface Cmr {
  id: number;
  /** CMR/2026/0001 when left empty; frozen once issued. */
  number: string;
  status: CmrStatus;
  order: { id: number; number: string } | null;
  trip: { id: number; number: string } | null;
  sender: CmrParty | null;
  senderContractorId: number | null;
  consignee: CmrParty | null;
  consigneeContractorId: number | null;
  carrier: CmrParty | null;
  successiveCarriers: CmrParty[];
  /** Box 3 */
  deliveryPlace: string | null;
  deliveryCountry: string | null;
  /** Box 4 */
  loadingPlace: string | null;
  loadingCountry: string | null;
  loadingDate: string | null;
  /** Box 5 */
  documentsAttached: string | null;
  lines: CmrLine[];
  /** Box 13 */
  senderInstructions: string | null;
  /** Box 14 */
  paymentTerms: PaymentTerms | null;
  paymentInstructions: string | null;
  /** Box 15 */
  cashOnDelivery: { amount: number; currency: string } | null;
  /** Box 18 */
  carrierReservations: string | null;
  /** Box 19 */
  specialAgreements: string | null;
  /** Box 21 */
  establishedPlace: string | null;
  establishedDate: string | null;
  vehiclePlates: string | null;
  signatures: CmrSignatures | null;
  notes: string | null;
  /** Issued and cancelled notes keep the PDF of the four copies. */
  hasPdf: boolean;
  issuedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Body of POST / PUT: a full replacement with ids instead of order and trip. */
export interface CmrInput extends Omit<Cmr, 'id' | 'number' | 'status' | 'order' | 'trip' | 'cashOnDelivery' | 'hasPdf' | 'issuedAt' | 'createdAt' | 'updatedAt'> {
  /** Empty: the next number of the loading year. */
  number: string | null;
  orderId: number | null;
  tripId: number | null;
  cashOnDelivery: { amount: number | null; currency: string | null } | null;
}

export interface CmrFilter {
  status?: CmrStatus;
  orderId?: number;
  tripId?: number;
  q?: string;
  /** Loading date range. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export const cmrApi = {
  list: (filter: CmrFilter) => api<Page<Cmr>>('/cmr', { query: { ...filter } }),
  get: (id: number | string) => api<Cmr>(`/cmr/${id}`),
  create: (body: CmrInput) => api<Cmr>('/cmr', { method: 'POST', body }),
  update: (id: number | string, body: CmrInput) => api<Cmr>(`/cmr/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/cmr/${id}`, { method: 'DELETE' }),
  fromOrder: (orderId: number | string) => api<Cmr>(`/cmr/from-order/${orderId}`, { method: 'POST' }),
  fromTrip: (tripId: number | string) => api<Cmr[]>(`/cmr/from-trip/${tripId}`, { method: 'POST' }),
  issue: (id: number | string) => api<Cmr>(`/cmr/${id}/issue`, { method: 'POST' }),
  cancel: (id: number | string) => api<Cmr>(`/cmr/${id}/cancel`, { method: 'POST' }),
  /** A draft is drawn now with a DRAFT watermark and `copies` pages; issued and cancelled notes give the stored PDF. */
  pdf: (id: number | string, copies: number) => apiDownload(`/cmr/${id}/pdf?copies=${copies}`),
};

export const CMR_KEY = ['cmr'] as const;

