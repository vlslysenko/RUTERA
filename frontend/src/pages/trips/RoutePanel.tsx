import { useEffect, useRef, useState } from 'react';
import type * as Leaflet from 'leaflet';
import { ActionIcon, Alert, Button, Checkbox, Group, Paper, Stack, Text, TextInput } from '@mantine/core';
import { IconInfoCircle, IconPlus, IconRoute, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { errorCode } from '@/api/errors';
import { calcApi, type CalcRoute, type RoutePoint } from '@/api/calc';
import { formatMinutes } from '@/pages/tacho/hosFormat';
import { decodePolyline } from '@/utils/polyline';
import classes from './Calc.module.css';

interface Waypoint {
  key: number;
  /** An address to geocode, or the label of a point picked on the map. */
  text: string;
  /** Set when the point was picked on the map (or routed). */
  lat: number | null;
  lng: number | null;
}

const MAX_POINTS = 25;
const TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';
let seq = 0;
const empty = (): Waypoint => ({ key: ++seq, text: '', lat: null, lng: null });
const coords = (lat: number, lng: number) => `${lat.toFixed(5)}, ${lng.toFixed(5)}`;

/**
 * Route of the calculator on an OpenStreetMap map: points typed as addresses or clicked on the map, the truck route
 * from POST /app/calc/route drawn on it; a built route hands its legs (km per country) to the calculator.
 * Leaflet is loaded only with this panel.
 */
export function RoutePanel({ vehicleId, onRoute }: { vehicleId: number | null; onRoute: (route: CalcRoute) => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? 'en';
  const [points, setPoints] = useState<Waypoint[]>([empty(), empty()]);
  const [avoidTolls, setAvoidTolls] = useState(false);
  const [avoidFerries, setAvoidFerries] = useState(false);
  const [route, setRoute] = useState<CalcRoute | null>(null);
  const [errors, setErrors] = useState<Record<number, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const mapEl = useRef<HTMLDivElement>(null);
  const map = useRef<{ L: typeof Leaflet; map: Leaflet.Map; layer: Leaflet.LayerGroup } | null>(null);


  useEffect(() => {
    let cancelled = false;
    void Promise.all([import('leaflet'), import('leaflet/dist/leaflet.css')]).then(([mod]) => {
      if (cancelled || !mapEl.current) return;
      const L = ((mod as { default?: typeof Leaflet }).default ?? mod) as typeof Leaflet;
      const m = L.map(mapEl.current, { center: [51.5, 15], zoom: 5 });
      L.tileLayer(TILES, { maxZoom: 18, attribution: OSM }).addTo(m);
      const layer = L.layerGroup().addTo(m);
      // A click on the map fills the first empty point, or adds one (state setters are stable, no stale closure).
      m.on('click', (e: Leaflet.LeafletMouseEvent) => {
        const { lat, lng } = e.latlng;
        setRoute(null);
        setPoints((ps) => {
          const i = ps.findIndex((p) => !p.text.trim() && p.lat == null);
          const picked = { text: coords(lat, lng), lat, lng };
          if (i >= 0) return ps.map((p, j) => (j === i ? { ...p, ...picked } : p));
          return ps.length >= MAX_POINTS ? ps : [...ps, { key: ++seq, ...picked }];
        });
      });
      map.current = { L, map: m, layer };
      setReady(true);
    });
    return () => {
      cancelled = true;
      map.current?.map.remove();
      map.current = null;
    };
  }, []);

  // Markers of the points and the line of the route.
  useEffect(() => {
    const m = map.current;
    if (!ready || !m) return;
    const { L, layer } = m;
    layer.clearLayers();
    const marks: [number, number][] = route
      ? route.points.map((p) => [p.lat, p.lng])
      : points.filter((p) => p.lat != null && p.lng != null).map((p) => [p.lat!, p.lng!]);
    marks.forEach((ll, i) =>
      L.marker(ll, { icon: L.divIcon({ className: classes.pin, html: String(i + 1), iconSize: [24, 24], iconAnchor: [12, 12] }), keyboard: false }).addTo(layer),
    );
    const line = route ? route.legs.flatMap((l) => decodePolyline(l.polyline, route.polylinePrecision)) : [];
    if (line.length) L.polyline(line, { color: '#1F5FBF', weight: 5, opacity: 0.8 }).addTo(layer);
    const all = line.length ? line : marks;
    if (all.length > 1) m.map.fitBounds(L.latLngBounds(all), { padding: [24, 24] });
    else if (all.length === 1) m.map.setView(all[0], Math.max(m.map.getZoom(), 8));
  }, [ready, points, route]);

  const set = (key: number, text: string) => {
    setRoute(null);
    setPoints((ps) => ps.map((p) => (p.key === key ? { ...p, text, lat: null, lng: null } : p)));
  };

  const build = async () => {
    const used = points.filter((p) => p.lat != null || p.text.trim());
    setErrors({});
    setFailure(null);
    if (used.length < 2) {
      setFailure('calc.route.min_points');
      return;
    }
    const body: RoutePoint[] = used.map((p) => (p.lat != null && p.lng != null ? { lat: p.lat, lng: p.lng } : { address: p.text.trim() }));
    setBusy(true);
    try {
      const r = await calcApi.route({ points: body, vehicleId, avoidTolls, avoidFerries });
      setDisabled(false);
      setRoute(r);
      onRoute(r);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'calc.routing.disabled') setDisabled(true);
      else if (e instanceof ApiError && e.details) {
        // points[i] counts only the points that were sent.
        const byRow: Record<number, string> = {};
        for (const [field, code] of Object.entries(e.details)) {
          const m = /^points\[(\d+)\]$/.exec(field);
          if (m && used[Number(m[1])]) byRow[used[Number(m[1])].key] = code;
          else setFailure(code);
        }
        setErrors(byRow);
      } else setFailure(errorCode(e));
    } finally {
      setBusy(false);
    }
  };

  const countryKm = new Map<string, number>();
  for (const l of route?.legs ?? []) for (const c of l.countries) countryKm.set(c.country, (countryKm.get(c.country) ?? 0) + c.km);
  const km = (v: number) => new Intl.NumberFormat(lang, { maximumFractionDigits: 0 }).format(v);

  return (
    <Paper withBorder p="md">
      <Stack gap="sm">
        <Text size="xs" fw={600} tt="uppercase" c="dimmed">
          {t('calc.route.title')}
        </Text>
        {disabled && (
          <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} p="xs">
            {t('calc.routing.disabled')}. {t('calc.route.manual_hint')}
          </Alert>
        )}
        <div ref={mapEl} className={classes.map} aria-label={t('calc.route.map_hint')} />
        <Text size="xs" c="dimmed">
          {t('calc.route.map_hint')}
        </Text>
        {points.map((p, i) => (
          <Group key={p.key} gap={6} wrap="nowrap" align="flex-start">
            <span className={classes.seq} style={{ marginTop: 6 }}>
              {i + 1}
            </span>
            <TextInput
              size="xs"
              style={{ flex: 1 }}
              placeholder={t('calc.route.point_hint')}
              aria-label={`${t('calc.route.point_hint')} (${i + 1})`}
              value={p.text}
              onChange={(e) => set(p.key, e.currentTarget.value)}
              classNames={p.lat != null ? { input: classes.mono } : undefined}
              error={errors[p.key] && t(errors[p.key])}
            />
            <ActionIcon
              variant="subtle"
              color="gray"
              mt={2}
              onClick={() => {
                setRoute(null);
                setPoints((ps) => (ps.length > 2 ? ps.filter((x) => x.key !== p.key) : ps.map((x) => (x.key === p.key ? { ...x, text: '', lat: null, lng: null } : x))));
              }}
              aria-label={t('common.delete')}
            >
              <IconX size={14} />
            </ActionIcon>
          </Group>
        ))}
        <Group justify="space-between" wrap="wrap">
          <Button size="xs" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => setPoints((ps) => [...ps, empty()])} disabled={points.length >= MAX_POINTS}>
            {t('calc.route.add_point')}
          </Button>
          <Group gap="md">
            <Checkbox size="xs" label={t('calc.route.avoid_tolls')} checked={avoidTolls} onChange={(e) => setAvoidTolls(e.currentTarget.checked)} />
            <Checkbox size="xs" label={t('calc.route.avoid_ferries')} checked={avoidFerries} onChange={(e) => setAvoidFerries(e.currentTarget.checked)} />
          </Group>
        </Group>
        <Button leftSection={<IconRoute size={16} />} onClick={() => void build()} loading={busy}>
          {t('calc.route.build')}
        </Button>
        {failure && (
          <Alert color="red" variant="light" p="xs">
            {t(failure)}
          </Alert>
        )}
        {route && (
          <Stack gap={2}>
            <Text size="sm" fw={600} className={classes.mono}>
              {km(route.distanceKm)} km · {formatMinutes(t, route.durationMin)}
            </Text>
            <Text size="xs" c="dimmed">
              {[...countryKm].map(([c, v]) => `${c} ${km(v)} km`).join(' · ')}
            </Text>
            <Text size="xs" c="dimmed">
              {t('calc.route.filled')}
            </Text>
          </Stack>
        )}
      </Stack>
    </Paper>
  );
}
