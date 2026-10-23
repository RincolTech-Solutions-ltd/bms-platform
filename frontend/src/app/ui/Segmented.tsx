import { useRef, type KeyboardEvent, type ReactNode } from 'react';

// The one "pick one" control (#520, docs/ui-rules.md rule 11): a choice of 2 to 5 options drawn as one tray with the chosen
// option raised, replacing the rows of separate pressed buttons (.cluster.filters). It is a group of toggle buttons
// (aria-pressed), so each option is a button named by its words. The group is one Tab stop (the chosen option, or the first
// while none is) and the arrow keys move between the options. A screen that lets the
// person clear the choice passes an onChange that sets it back to nothing when the chosen option is pressed again. The look is
// .segmented in src/app/ui/kit.css.

export interface SegmentedOption<T extends string> {
  value: T;
  label: ReactNode;
}

export function Segmented<T extends string>({ label, options, value, onChange, tour }: {
  /** Names the group for a screen reader ("Show", "View"). */
  label: string;
  options: readonly SegmentedOption<T>[];
  /** The chosen option, or null while none is. */
  value: T | null;
  onChange: (value: T) => void;
  tour?: string;
}) {
  const group = useRef<HTMLDivElement>(null);
  const move = (e: KeyboardEvent<HTMLButtonElement>) => {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
    if (!step || !group.current) return;
    const buttons = [...group.current.querySelectorAll('button')];
    const at = buttons.indexOf(e.currentTarget);
    buttons[(at + step + buttons.length) % buttons.length]?.focus();
    e.preventDefault();
  };
  return (
    <div ref={group} className="segmented" role="group" aria-label={label} data-tour={tour}>
      {options.map((o, i) => (
        <button
          key={o.value}
          type="button"
          tabIndex={value === o.value || (i === 0 && !options.some((x) => x.value === value)) ? 0 : -1}
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          onKeyDown={move}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
