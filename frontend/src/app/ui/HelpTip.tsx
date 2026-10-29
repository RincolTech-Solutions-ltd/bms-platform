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
  const [place, setPlace] = useState<{ top: number; left: number } | null>(null);
  const id = useId();
  const box = useRef<HTMLSpanElement>(null);
  const button = useRef<HTMLButtonElement>(null);
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
    // The text is placed on the screen, not in the page, so a scroll would leave it behind: it closes instead.
    const scrolled = () => setOpen(false);
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    window.addEventListener('scroll', scrolled, true);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('scroll', scrolled, true);
    };
  }, [open]);

  // Under the button, kept inside the screen. Fixed on the screen, so a table that scrolls sideways never cuts it off.
  useLayoutEffect(() => {
    const at = button.current?.getBoundingClientRect();
    const width = bubble.current?.getBoundingClientRect().width ?? 0;
    if (!open || !at) {
      setPlace(null);
      return;
    }
    const right = document.documentElement.clientWidth - EDGE;
    setPlace({ top: at.bottom + 4, left: Math.max(EDGE, Math.min(at.left, right - width)) });
  }, [open]);

  return (
    <span className="help-tip" ref={box}>
      <button
        ref={button}
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
      <span ref={bubble} id={id} role="status" className="help-tip-text" style={place ?? undefined}>
        {open ? text : ''}
      </span>
    </span>
  );
}
