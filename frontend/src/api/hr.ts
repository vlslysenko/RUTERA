import { api } from './client';

/*
 * Driver HR (backend e2ac78a, README "Driver HR"): absences, the monthly work-day calendar, pay rates and payments.
 * Privileges: reading with driver.driver/view; absences changed with driver.absence/manage, manual days with
 * driver.driver/manage; pay rates and payments read with driver.payment/view and changed with driver.payment/manage.
 */

export interface PersonRef {
  id: number;
  name: string;
}

// --- absences ----------------------------------------------------------------------------------------

export const ABSENCE_KINDS = ['SICK', 'VACATION', 'UNPAID', 'PARENTAL', 'OTHER'] as const;
export type AbsenceKind = (typeof ABSENCE_KINDS)[number];

export interface Absence {
  id: number;
  driver: PersonRef;
  kind: AbsenceKind;
  /** YYYY-MM-DD, both included */
  fromDate: string;
  toDate: string;
  /** Calendar days. */
  days: number;
  /** Monday to Friday (no holiday calendar yet). */
  workingDays: number;
  paid: boolean;
  note: string | null;
  /** An absence entered by a manager is approved by them. */
  approvedBy: PersonRef | null;
  approvedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** paid: empty = false for UNPAID, true otherwise. Overlap with another absence: 422 toDate: driver.absence.overlap. */
export interface AbsenceInput {
  kind: AbsenceKind;
  fromDate: string;
  toDate: string;
  paid: boolean | null;
  note: string | null;
}

// --- work days ---------------------------------------------------------------------------------------

export const DAY_KINDS = ['WORK', 'REST', 'ABSENCE', 'HOLIDAY'] as const;
export type DayKind = (typeof DAY_KINDS)[number];
export type DaySource = 'TACHO' | 'ABSENCE' | 'MANUAL' | 'NONE';

/** A manual entry wins, then an absence, then the tachograph (driving or work = WORK); no data = REST, source NONE. */
export interface DriverDay {
  day: string;
  kind: DayKind;
  source: DaySource;
  absenceKind: AbsenceKind | null;
  drivingMin: number;
  workMin: number;
  note: string | null;
}

export interface DriverMonth {
  days: DriverDay[];
  summary: { workDays: number; restDays: number; absenceDays: number; holidayDays: number; drivingMin: number; workMin: number };
}

// --- pay ---------------------------------------------------------------------------------------------

export interface PayRate {
  id: number;
  /** YYYY-MM-DD, one per driver (validFrom: driver.pay_rate.date_taken). */
  validFrom: string;
  monthlyBase: number | null;
  perDiemHome: number | null;
  perDiemAbroad: number | null;
  /** 4 decimals */
  perKm: number | null;
  currency: string;
  createdAt: string;
  updatedAt: string;
}

/** At least one amount (else 422 monthlyBase: validation.required). */
export type PayRateInput = Pick<PayRate, 'validFrom' | 'monthlyBase' | 'perDiemHome' | 'perDiemAbroad' | 'perKm' | 'currency'>;

export const PAYMENT_KINDS = ['SALARY', 'PER_DIEM', 'BONUS', 'ADVANCE', 'DEDUCTION'] as const;
export type PaymentKind = (typeof PAYMENT_KINDS)[number];

/** Advances and deductions reduce what is still to be paid (they count negative in the totals). */
export const NEGATIVE_PAYMENT_KINDS: readonly PaymentKind[] = ['ADVANCE', 'DEDUCTION'];

export interface DriverPayment {
  id: number;
  driver: PersonRef;
  /** YYYY-MM */
  period: string;
  kind: PaymentKind;
  /** Above zero; the kind gives the sign. */
  amount: number;
  currency: string;
  paidAt: string | null;
  note: string | null;
  createdAt: string;
  updatedAt: string;
}

export type DriverPaymentInput = Pick<DriverPayment, 'period' | 'kind' | 'amount' | 'currency' | 'paidAt' | 'note'>;

export interface PaymentTotal {
  currency: string;
  amount: number;
}

export const hrApi = {
  absences: (driverId: number | string, from?: string, to?: string) =>
    api<Absence[]>(`/drivers/${driverId}/absences`, { query: { from, to } }),
  createAbsence: (driverId: number | string, body: AbsenceInput) =>
    api<Absence>(`/drivers/${driverId}/absences`, { method: 'POST', body }),
  updateAbsence: (driverId: number | string, id: number, body: AbsenceInput) =>
    api<Absence>(`/drivers/${driverId}/absences/${id}`, { method: 'PUT', body }),
  removeAbsence: (driverId: number | string, id: number) => api<void>(`/drivers/${driverId}/absences/${id}`, { method: 'DELETE' }),
  /** Absences of the firma overlapping the range (always pass one: without it the backend returns all). */
  firmAbsences: (from: string, to: string, kind?: AbsenceKind, driverId?: number) =>
    api<Absence[]>('/absences', { query: { from, to, kind, driverId } }),

  month: (driverId: number | string, month: string) => api<DriverMonth>(`/drivers/${driverId}/days`, { query: { month } }),
  setDay: (driverId: number | string, day: string, body: { kind: DayKind; note: string | null }) =>
    api<DriverDay>(`/drivers/${driverId}/days/${day}`, { method: 'PUT', body }),
  /** Back to the computed day. */
  resetDay: (driverId: number | string, day: string) => api<DriverDay>(`/drivers/${driverId}/days/${day}`, { method: 'DELETE' }),

  payRates: (driverId: number | string) => api<PayRate[]>(`/drivers/${driverId}/pay-rates`),
  createPayRate: (driverId: number | string, body: PayRateInput) =>
    api<PayRate>(`/drivers/${driverId}/pay-rates`, { method: 'POST', body }),
  updatePayRate: (driverId: number | string, id: number, body: PayRateInput) =>
    api<PayRate>(`/drivers/${driverId}/pay-rates/${id}`, { method: 'PUT', body }),
  removePayRate: (driverId: number | string, id: number) => api<void>(`/drivers/${driverId}/pay-rates/${id}`, { method: 'DELETE' }),

  payments: (driverId: number | string, from: string, to: string) =>
    api<DriverPayment[]>(`/drivers/${driverId}/payments`, { query: { from, to } }),
  createPayment: (driverId: number | string, body: DriverPaymentInput) =>
    api<DriverPayment>(`/drivers/${driverId}/payments`, { method: 'POST', body }),
  updatePayment: (driverId: number | string, id: number, body: DriverPaymentInput) =>
    api<DriverPayment>(`/drivers/${driverId}/payments/${id}`, { method: 'PUT', body }),
  removePayment: (driverId: number | string, id: number) => api<void>(`/drivers/${driverId}/payments/${id}`, { method: 'DELETE' }),
  /** Payments of the firma for one month, with totals per currency. */
  firmPayments: (period: string, driverId?: number, kind?: PaymentKind) =>
    api<{ items: DriverPayment[]; totals: PaymentTotal[] }>('/payments', { query: { period, driverId, kind } }),
};

/** Query keys: per driver under DRIVERS_KEY (so a driver delete refreshes them), firm-wide under HR_KEY. */
export const HR_KEY = ['hr'] as const;
export const driverHrKey = (driverId: number | string, part: 'absences' | 'days' | 'pay-rates' | 'payments') =>
  ['drivers', String(driverId), part] as const;
