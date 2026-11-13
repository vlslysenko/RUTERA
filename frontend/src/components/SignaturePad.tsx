import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Group, Input, Text } from '@mantine/core';
import { IconEraser } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import classes from './SignaturePad.module.css';

type Point = [number, number];

const HEIGHT = 180;
const INK = '#1A2433';

/**
 * A signature drawn with a finger, a stylus or the mouse. The pad stays white with dark ink like paper (also in a dark
 * theme), keeps the strokes in relative coordinates so a rotated phone redraws them, and reports a PNG data URL after
 * every stroke, or null once cleared.
 */
export function SignaturePad({ label, description, error, onChange }: { label: string; description?: string; error?: string; onChange: (png: string | null) => void }) {
  const { t } = useTranslation();
  const canvas = useRef<HTMLCanvasElement>(null);
  const strokes = useRef<Point[][]>([]);
  const drawing = useRef(false);
  const [empty, setEmpty] = useState(true);

  /** Paints the background and every stroke at the canvas's current size. */
  const redraw = useCallback(() => {
    const c = canvas.current;
    const ctx = c?.getContext('2d');
    if (!c || !ctx) return;
    const w = c.width;
    const h = c.height;
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = INK;
    ctx.lineWidth = 2.2 * (window.devicePixelRatio || 1);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const s of strokes.current) {
      if (s.length === 0) continue;
      ctx.beginPath();
      ctx.moveTo(s[0][0] * w, s[0][1] * h);
      if (s.length === 1) ctx.lineTo(s[0][0] * w + 0.1, s[0][1] * h);
      // Through the midpoints, so the line is smooth and not a polygon.
      for (let i = 1; i < s.length - 1; i++) {
        const mx = ((s[i][0] + s[i + 1][0]) / 2) * w;
        const my = ((s[i][1] + s[i + 1][1]) / 2) * h;
        ctx.quadraticCurveTo(s[i][0] * w, s[i][1] * h, mx, my);
      }
      if (s.length > 1) ctx.lineTo(s[s.length - 1][0] * w, s[s.length - 1][1] * h);
      ctx.stroke();
    }
  }, []);

  // Size the canvas to its box in device pixels; on resize (a rotated phone) the strokes are drawn again.
  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const fit = () => {
      const dpr = window.devicePixelRatio || 1;
      const width = c.clientWidth;
      c.width = Math.round(width * dpr);
      c.height = Math.round(HEIGHT * dpr);
      redraw();
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(c);
    return () => ro.disconnect();
  }, [redraw]);

  const at = (e: React.PointerEvent<HTMLCanvasElement>): Point => {
    const r = e.currentTarget.getBoundingClientRect();
    return [Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))];
  };

  const down = (e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    drawing.current = true;
    strokes.current.push([at(e)]);
    redraw();
  };
  const move = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current) return;
    e.preventDefault();
    strokes.current[strokes.current.length - 1].push(at(e));
    redraw();
  };
  const up = () => {
    if (!drawing.current) return;
    drawing.current = false;
    setEmpty(false);
    onChange(canvas.current?.toDataURL('image/png') ?? null);
  };
  const clear = () => {
    strokes.current = [];
    redraw();
    setEmpty(true);
    onChange(null);
  };

  return (
    <Input.Wrapper label={label} description={description} error={error}>
      <div className={classes.box} data-error={error ? true : undefined}>
        <canvas
          ref={canvas}
          className={classes.pad}
          style={{ height: HEIGHT }}
          role="img"
          aria-label={t('signature.aria')}
          onPointerDown={down}
          onPointerMove={move}
          onPointerUp={up}
          onPointerCancel={up}
          onPointerLeave={up}
        />
        {empty && (
          <Text size="sm" c="dimmed" className={classes.hint}>
            {t('signature.hint')}
          </Text>
        )}
        <span className={classes.line} aria-hidden />
      </div>
      <Group justify="flex-end" mt={4}>
        <Button size="xs" variant="subtle" color="gray" leftSection={<IconEraser size={14} />} onClick={clear} disabled={empty}>
          {t('signature.clear')}
        </Button>
      </Group>
    </Input.Wrapper>
  );
}
