import { api } from './client';

/* Demo endpoints used by the Fleet page to exercise the list + detail drawer pattern.
 * Real shape will follow DB_SCHEMA.md (vehicle table) once the backend module exists. */

export type VehicleStatus = 'ON_ROUTE' | 'REST' | 'BASE' | 'SERVICE';

export interface Vehicle {
  id: number;
  plate: string;
  model: string;
  status: VehicleStatus;
  driver: string | null;
  odometerKm: number;
}

export const fleetApi = {
  list: () => api<Vehicle[]>('/fleet/vehicles'),
  get: (id: number | string) => api<Vehicle>(`/fleet/vehicles/${id}`),
};
