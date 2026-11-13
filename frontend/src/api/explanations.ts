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
 * POST /app/explanations/{id}/sign  {reason, reasonText?, place?, signature: data:image/png;base64,... <= 300 KB,
 *      art12?, art12Place?, art12BreakFrom?, art12BreakTo?, printoutNote?, printoutNoteAt?}
 *      (only the driver it belongs to; not PENDING: 409 explanation.not_pending)
 * GET  /app/explanations/reasons?ruleCode=  -> [{code, art12Safety, art12Home, textRequired}]  (backend c46ba8f)
 * GET  /app/explanations/{id}/printout-note?reason=&art12=&place=&breakFrom=&breakTo=  -> {lang, instruction, text}
 */

export const EXPLANATION_STATUSES = ['PENDING', 'SIGNED', 'CANCELLED'] as const;
export type ExplanationStatus = (typeof EXPLANATION_STATUSES)[number];

/**
 * Why the driver departed from the rules (the research list of the backend's templates). Which of them allow an
 * Article 12 claim comes from GET /app/explanations/reasons: inspectors do not accept long loading, a dispatcher's
 * instruction, an own planning error or a recurring jam as Art. 12 grounds.
 */
export const EXPLANATION_REASONS = [
  'TRAFFIC_ACCIDENT',
  'ROAD_CLOSURE_DETOUR',
  'EXTREME_WEATHER',
  'NO_PARKING_SPACE',
  'SECURITY_THREAT',
  'VEHICLE_BREAKDOWN',
  'LOAD_SAFETY',
  'MEDICAL_EMERGENCY',
  'AUTHORITY_ORDER',
  'FERRY_TRAIN_DELAY',
  'REACH_HOME_OR_BASE',
  'TRAFFIC_JAM',
  'LOADING_DELAY',
  'DISPATCH_INSTRUCTION',
  'OWN_PLANNING_ERROR',
  'RECORDING_ERROR',
  'CARD_OR_TACHOGRAPH_FAULT',
  'DATA_DISPUTE',
  'OTHER',
] as const;
export type ExplanationReason = (typeof EXPLANATION_REASONS)[number];

/**
 * The Article 12 claim: none; paragraph 1 (to reach a suitable stopping place, for the safety of persons, vehicle or
 * load); paragraphs 2 and 3 as amended by 2020/1054 (up to 1 h, or 2 h after a 30-minute break, more driving to reach
 * the operational centre or home for the weekly rest).
 */
export const ART12_CLAIMS = ['NONE', 'SAFETY', 'HOME_1H', 'HOME_2H'] as const;
export type Art12Claim = (typeof ART12_CLAIMS)[number];

/** A reason for an infringement: the Art. 12 claims it supports and whether the driver must describe it. */
export interface ReasonOption {
  code: ExplanationReason;
  art12Safety: boolean;
  art12Home: boolean;
  textRequired: boolean;
}

/** The note the driver copies by hand onto the tachograph printout, in the firma's language. */
export interface PrintoutNote {
  lang: string;
  instruction: string;
  text: string;
}

export interface Explanation {
  id: number;
  status: ExplanationStatus;
  /** AUTO: asked for by the import of tachograph data; MANUAL: by the office. */
  source: 'AUTO' | 'MANUAL';
  driver: { id: number; name: string };
  vehicle: { id: number | null; plate: string } | null;
  violationId: number | null;
  ruleCode: ViolationRule;
  /** The infringement's code in the document templates. */
  infringementCode: string | null;
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
  art12: Art12Claim | null;
  art12Place: string | null;
  /** HH:mm, the break before a 2 h extension. */
  art12BreakFrom: string | null;
  art12BreakTo: string | null;
  /** The handwritten note on the printout was made, and when. */
  printoutNote: boolean | null;
  printoutNoteAt: string | null;
  /** Which claims this infringement allows: paragraph 1 (safety), paragraphs 2 and 3 (home). */
  art12Allowed: { safety: boolean; home: boolean } | null;
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
  art12?: Art12Claim;
  art12Place?: string | null;
  art12BreakFrom?: string | null;
  art12BreakTo?: string | null;
  printoutNote?: boolean;
  printoutNoteAt?: string | null;
}

export const explanationsApi = {
  list: (filter: ExplanationFilter) => api<{ items: Explanation[]; total: number }>('/explanations', { query: { ...filter } }),
  get: (id: number | string) => api<Explanation>(`/explanations/${id}`),
  create: (body: ExplanationInput) => api<Explanation>('/explanations', { method: 'POST', body }),
  sign: (id: number | string, body: SignInput) => api<Explanation>(`/explanations/${id}/sign`, { method: 'POST', body }),
  pdf: (id: number | string) => apiDownload(`/explanations/${id}/pdf`),
  reasons: (ruleCode?: string) => api<ReasonOption[]>('/explanations/reasons', { query: { ruleCode } }),
  printoutNote: (id: number | string, q: { reason?: string; art12?: string; place?: string; breakFrom?: string; breakTo?: string }) =>
    api<PrintoutNote>(`/explanations/${id}/printout-note`, { query: q }),
  remind: (id: number | string) => api<Explanation>(`/explanations/${id}/remind`, { method: 'POST' }),
  cancel: (id: number | string) => api<Explanation>(`/explanations/${id}/cancel`, { method: 'POST' }),
};

export const EXPLANATIONS_KEY = ['explanations'] as const;

/** The Art. 12 claims possible for this explanation with this reason (NONE always). */
export function art12Choices(e: Pick<Explanation, 'art12Allowed' | 'exceededMin'>, option: ReasonOption | undefined): Art12Claim[] {
  const out: Art12Claim[] = ['NONE'];
  if (!option) return out;
  if (e.art12Allowed?.safety && option.art12Safety) out.push('SAFETY');
  if (e.art12Allowed?.home && option.art12Home) {
    const over = e.exceededMin ?? 0;
    if (over <= 60) out.push('HOME_1H');
    if (over <= 120) out.push('HOME_2H');
  }
  return out;
}
