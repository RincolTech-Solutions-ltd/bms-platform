import type { KeyboardEvent } from 'react';
import { icons } from '../../components/icons';

// The quantity control (#520, docs/ui-rules.md rule 11): minus and plus around a number field, each a 48px button, so a
// quantity is changed by a tap and still typed when it is large. The value stays the text the person typed (a screen may allow
// 1.5 kg); minus and plus move it by `step` and keep it within `min` and `max`. In the field the arrow keys do the same, and
// Home and End go to the ends when there are ends. The field keeps its own id and label, so a screen's tests and tours still
// find it. The look is .stepper in src/app/ui/kit.css.

/** The value moved by `delta`, kept within the bounds; an empty or unreadable value counts as `min`. */
export function stepped(value: string, delta: number, min: number, max?: number): string {
  const now = Number(value.trim());
  const from = value.trim() === '' || !Number.isFinite(now) ? min : now;
  // Rounded to 6 places so 0.1 steps do not drift (0.30000000000000004).
  const next = Math.round((from + delta) * 1e6) / 1e6;
  return String(Math.min(max ?? Infinity, Math.max(min, next)));
}

export function Stepper({ id, label, value, onChange, min = 0, max, step = 1, name, inputMode = 'numeric', invalid = false, tour }: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  /** What is counted, for the buttons' names ("One more Sugar 1 kg"); without it they are "One less" and "One more". */
  name?: string;
  /** `decimal` where a part is allowed (1.5 kg). */
  inputMode?: 'numeric' | 'decimal';
  invalid?: boolean;
  tour?: string;
}) {
  const now = Number(value);
  const known = value.trim() !== '' && Number.isFinite(now);
  const atMin = known && now <= min;
  const atMax = known && max !== undefined && now >= max;
  const keys = (e: KeyboardEvent<HTMLInputElement>) => {
    const moves: Record<string, () => string | null> = {
      ArrowUp: () => stepped(value, step, min, max),
      ArrowDown: () => stepped(value, -step, min, max),
      Home: () => String(min),
      End: () => (max === undefined ? null : String(max)),
    };
    const next = moves[e.key]?.();
    if (next == null) return;
    e.preventDefault();
    onChange(next);
  };
  const suffix = name ? ` ${name}` : '';
  return (
    <div className="stepper-field" data-tour={tour}>
      <label htmlFor={id}>{label}</label>
      <div className="stepper">
        <button type="button" aria-label={`One less${suffix}`} aria-controls={id} disabled={atMin} onClick={() => onChange(stepped(value, -step, min, max))}>
          {icons.minus}
        </button>
        <input
          id={id}
          inputMode={inputMode}
          autoComplete="off"
          value={value}
          aria-invalid={invalid}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={keys}
        />
        <button type="button" aria-label={`One more${suffix}`} aria-controls={id} disabled={atMax} onClick={() => onChange(stepped(value, step, min, max))}>
          {icons.plus}
        </button>
      </div>
    </div>
  );
}
