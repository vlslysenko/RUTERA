import { useEffect, useRef, useState } from 'react';
import type * as Leaflet from 'leaflet';
import { Paper, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { CalcRoute } from '@/api/calc';
import { formatMinutes } from '@/pages/tacho/hosFormat';
import { decodePolyline } from '@/utils/polyline';
import classes from './Calc.module.css';

const TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';

/**
 * Map of the calculator on OpenStreetMap tiles: the points picked so far, or the truck route of the last calculation
 * with its km per country. A click on the map hands the place to the form (`onPick`). Leaflet loads with this panel only.
 */
export function RouteMap({
  route,
  picks,
  onPick,
}: {
  route: CalcRoute | null;
  /** Points picked on the map before a route exists, in driving order. */
  picks: { lat: number; lng: number }[];
  onPick: (lat: number, lng: number) => void;
}) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const [ready, setReady] = useState(false);
  const mapEl = useRef<HTMLDivElement>(null);
  const map = useRef<{ L: typeof Leaflet; map: Leaflet.Map; layer: Leaflet.LayerGroup } | null>(null);
  // The latest onPick for the map's click handler, which is bound once.
  const pick = useRef(onPick);
  useEffect(() => {
    pick.current = onPick;
  }, [onPick]);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([import('leaflet'), import('leaflet/dist/leaflet.css')]).then(([mod]) => {
      if (cancelled || !mapEl.current) return;
      const L = ((mod as { default?: typeof Leaflet }).default ?? mod) as typeof Leaflet;
      const m = L.map(mapEl.current, { center: [51.5, 15], zoom: 5 });
      L.tileLayer(TILES, { maxZoom: 18, attribution: OSM }).addTo(m);
      const layer = L.layerGroup().addTo(m);
      m.on('click', (e: Leaflet.LeafletMouseEvent) => pick.current(e.latlng.lat, e.latlng.lng));
      map.current = { L, map: m, layer };
      setReady(true);
    });
    return () => {
      cancelled = true;
      map.current?.map.remove();
      map.current = null;
    };
  }, []);

  // Numbered markers and the line of the route.
  useEffect(() => {
    const m = map.current;
    if (!ready || !m) return;
    const { L, layer } = m;
    layer.clearLayers();
    const marks: [number, number][] = route ? route.points.map((p) => [p.lat, p.lng]) : picks.map((p) => [p.lat, p.lng]);
    marks.forEach((ll, i) =>
      L.marker(ll, { icon: L.divIcon({ className: classes.pin, html: String(i + 1), iconSize: [24, 24], iconAnchor: [12, 12] }), keyboard: false }).addTo(layer),
    );
    const line = route ? route.legs.flatMap((l) => decodePolyline(l.polyline, route.polylinePrecision)) : [];
    if (line.length) L.polyline(line, { color: '#1F5FBF', weight: 5, opacity: 0.8 }).addTo(layer);
    const all = line.length ? line : marks;
    if (all.length > 1) m.map.fitBounds(L.latLngBounds(all), { padding: [24, 24] });
    else if (all.length === 1) m.map.setView(all[0], Math.max(m.map.getZoom(), 8));
  }, [ready, picks, route]);

  const countryKm = new Map<string, number>();
  for (const l of route?.legs ?? []) for (const c of l.countries) countryKm.set(c.country, (countryKm.get(c.country) ?? 0) + c.km);
  const km = (v: number) => new Intl.NumberFormat(lang, { maximumFractionDigits: 0 }).format(v);

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <div ref={mapEl} className={classes.map} aria-label={t('calc.map_hint')} />
        {route ? (
          <Stack gap={2}>
            <Text size="sm" fw={600} className={classes.mono}>
              {km(route.distanceKm)} km · {formatMinutes(t, route.durationMin)}
            </Text>
            <Text size="xs" c="dimmed">
              {[...countryKm].map(([c, v]) => `${c} ${km(v)} km`).join(' · ')}
            </Text>
          </Stack>
        ) : (
          <Text size="xs" c="dimmed">
            {t('calc.map_hint')}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}
