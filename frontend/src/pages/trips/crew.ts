import { useQuery } from '@tanstack/react-query';
import { DRIVERS_KEY, driversApi } from '@/api/drivers';
import { fleetApi, isTrailer, makeModel, VEHICLES_KEY, type Vehicle } from '@/api/fleet';

export interface CrewOption {
  value: string;
  label: string;
}

const vehicleLabel = (v: Vehicle) => [v.plate, makeModel(v)].filter(Boolean).join(' · ');

/**
 * Vehicles, trailers and drivers for crew selects: active ones, plus whatever is already chosen (`keep`) so an
 * inactive vehicle on an old trip still shows its plate. `vehicle(id)` gives the defaults of a chosen vehicle.
 */
export function useCrewOptions(keep: (string | null)[] = []) {
  const vehicles = useQuery({ queryKey: VEHICLES_KEY, queryFn: fleetApi.list });
  const drivers = useQuery({ queryKey: DRIVERS_KEY, queryFn: driversApi.list });
  const kept = new Set(keep.filter(Boolean));
  const usable = (status: string, id: number) => status !== 'INACTIVE' || kept.has(String(id));

  const all = vehicles.data ?? [];
  return {
    loading: vehicles.isPending || drivers.isPending,
    error: vehicles.error ?? drivers.error,
    vehicles: all.filter((v) => !isTrailer(v.kind) && usable(v.status, v.id)).map((v) => ({ value: String(v.id), label: vehicleLabel(v) })),
    trailers: all.filter((v) => isTrailer(v.kind) && usable(v.status, v.id)).map((v) => ({ value: String(v.id), label: vehicleLabel(v) })),
    drivers: (drivers.data ?? []).filter((d) => usable(d.status, d.id)).map((d) => ({ value: String(d.id), label: d.name })),
    vehicle: (id: string | null) => (id ? all.find((v) => String(v.id) === id) : undefined),
  };
}

export const idOrNull = (v: string | null | undefined) => (v ? Number(v) : null);
