import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { HELP_TIPS, type HelpTipKey } from '../help-tips';

// The one help tip (issue #707): a small question mark after a label that is not obvious, with a short plain explanation. A
// phone has no hover, so the tip opens on a tap, and on keyboard focus; a second tap, a tap anywhere else or Escape closes it.
// The button keeps the 48px tap area of every button (rule 12) and is named "More about" its label; the open text is in a live
// region the button is described by, so a screen reader says it when it opens. The words are in src/app/help-tips.ts. The
// look is .help-tip in src/app/ui/help.css.

/** How far the open text keeps from the edge of the screen, so a tip near the edge never makes the page scroll sideways. */
const EDGE = 16;

export function HelpTip({ tip }: { tip: HelpTipKey }) {
  const { label, text } = HELP_TIPS[tip];
  const [open, setOpen] = useState(false);
  const [shift, setShift] = useState(0);
  const id = useId();
  const box = useRef<HTMLSpanElement>(null);
  const bubble = useRef<HTMLSpanElement>(null);
  // A tap focuses the button before its click: the focus must not open the tip that the click then closes again.
  const tapping = useRef(false);

  useEffect(() => {
    if (!open) return;
    const outside = (e: Event) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  useLayoutEffect(() => {
    const el = bubble.current;
    if (!open || !el) {
      setShift(0);
      return;
    }
    const rect = el.getBoundingClientRect();
    const right = document.documentElement.clientWidth - EDGE;
    setShift(rect.right > right ? Math.max(EDGE - rect.left, right - rect.right) : rect.left < EDGE ? EDGE - rect.left : 0);
  }, [open]);

  return (
    <span className="help-tip" ref={box}>
      <button
        type="button"
        className="btn-ghost btn-icon help-tip-button"
        aria-label={`More about ${label}`}
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        onPointerDown={() => { tapping.current = true; }}
        onFocus={() => { if (!tapping.current) setOpen(true); }}
        onBlur={() => { tapping.current = false; }}
        onClick={() => { tapping.current = false; setOpen((o) => !o); }}
      >
        <span aria-hidden="true">?</span>
      </button>
      <span ref={bubble} id={id} role="status" className="help-tip-text" style={shift ? { transform: `translateX(${shift}px)` } : undefined}>
        {open ? text : ''}
      </span>
    </span>
  );
}
