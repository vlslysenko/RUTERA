import type { ViolationRule } from './hos';
import { api, apiDownload } from './client';

/*
 * Driver explanations of tachograph infringements (backend cd005a4). The import asks for one for every new violation
 * of the last explanationsDays days (firma settings), or the office asks by hand or from a violation; the driver signs
 * it in the cabinet with a reason and a finger signature; the PDF is written in the language of the firma's country of
 * registration (docLang), the screens stay in the user's language.
 *
 * GET  /app/explanations?status=&driverId=&from=&to=&page=&size=  -> {items, total}, newest first
 *      (office tacho.hos/view; a driver with driver_app.self/use gets only their own)
 * GET  /app/explanations/{id}, GET /app/explanations/{id}/pdf (unsigned: a preview with a draft watermark)
 * POST /app/explanations  {violationId} | {driverId, vehicleId?, ruleCode, startedAt, endedAt?, exceededMin?}  -> 201
 *      (tacho.violation/ack; a violation's existing one comes back, reopened if cancelled; the same by hand: 409)
 * POST /app/explanations/{id}/remind, /cancel  (tacho.violation/ack)
 * POST /app/explanations/{id}/sign  {reason, reasonText?, place?, signature: data:image/png;base64,... <= 300 KB}
 *      (only the driver it belongs to; not PENDING: 409 explanation.not_pending)
 */

export const EXPLANATION_STATUSES = ['PENDING', 'SIGNED', 'CANCELLED'] as const;
export type ExplanationStatus = (typeof EXPLANATION_STATUSES)[number];

/**
 * Why the driver departed from the rules. The first three are the grounds of Art. 12 of Regulation (EC) 561/2006 (as
 * amended by 2020/1054): reaching a suitable stopping place, the safety of persons, vehicle or load, and returning to
 * the operational centre or home for the weekly rest. The rest are the everyday causes behind them.
 */
export const EXPLANATION_REASONS = [
  'SAFE_STOPPING_PLACE',
  'SAFETY',
  'RETURN_FOR_WEEKLY_REST',
  'TRAFFIC_JAM',
  'NO_PARKING',
  'ACCIDENT',
  'ROAD_CLOSURE',
  'BREAKDOWN',
  'WEATHER',
  'LOADING_DELAY',
  'OTHER',
] as const;
export type ExplanationReason = (typeof EXPLANATION_REASONS)[number];

export interface Explanation {
  id: number;
  status: ExplanationStatus;
  /** AUTO: asked for by the import of tachograph data; MANUAL: by the office. */
  source: 'AUTO' | 'MANUAL';
  driver: { id: number; name: string };
  vehicle: { id: number | null; plate: string } | null;
  violationId: number | null;
  ruleCode: ViolationRule;
  /** ISO instants of the infringement. */
  startedAt: string;
  endedAt: string | null;
  /** Measured, allowed and by how much over, in minutes. */
  actualMin: number | null;
  limitMin: number | null;
  exceededMin: number | null;
  reason: ExplanationReason | null;
  reasonText: string | null;
  place: string | null;
  /** Language of the document (from the firma's country of registration). */
  docLang: string;
  signedAt: string | null;
  signedBy: { id: number; name: string } | null;
  cancelledAt: string | null;
  createdAt: string;
  createdBy: { id: number; name: string } | null;
  /** A signed PDF is stored. */
  hasPdf: boolean;
}

export interface ExplanationFilter {
  status?: ExplanationStatus;
  driverId?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export type ExplanationInput =
  | { violationId: number }
  | { driverId: number; vehicleId: number | null; ruleCode: ViolationRule; startedAt: string; endedAt: string | null; exceededMin: number | null };

export interface SignInput {
  reason: ExplanationReason;
  reasonText: string | null;
  place: string | null;
  /** PNG data URL of the finger signature. */
  signature: string;
}

export const explanationsApi = {
  list: (filter: ExplanationFilter) => api<{ items: Explanation[]; total: number }>('/explanations', { query: { ...filter } }),
  get: (id: number | string) => api<Explanation>(`/explanations/${id}`),
  create: (body: ExplanationInput) => api<Explanation>('/explanations', { method: 'POST', body }),
  sign: (id: number | string, body: SignInput) => api<Explanation>(`/explanations/${id}/sign`, { method: 'POST', body }),
  pdf: (id: number | string) => apiDownload(`/explanations/${id}/pdf`),
  remind: (id: number | string) => api<Explanation>(`/explanations/${id}/remind`, { method: 'POST' }),
  cancel: (id: number | string) => api<Explanation>(`/explanations/${id}/cancel`, { method: 'POST' }),
};

export const EXPLANATIONS_KEY = ['explanations'] as const;

/** A reason the driver must describe in words. */
export const needsText = (r: ExplanationReason | null) => r === 'OTHER';
