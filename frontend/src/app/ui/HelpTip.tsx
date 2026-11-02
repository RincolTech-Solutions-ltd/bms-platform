import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { HELP_TIPS, type HelpTipKey } from '../help-tips';

// The one help tip (issue #707): a small question mark after a label that is not obvious, with a short plain explanation. A
// phone has no hover, so the tip opens on a tap, and on keyboard focus; a second tap, a tap anywhere else, Escape or focus
// moving away closes it. The button keeps the 48px tap area of every button (rule 12), with no negative margin, so it never
// lies over the control next to it, and is named "More about" the words on screen. The text is a live region that is always
// in the page, at the end of the body: empty and visually hidden while closed, so the words are announced as they appear, and
// never part of the name of a heading, a legend or a column the button sits in. It is placed on the screen under the button
// and follows it when the page or a table scrolls. The words are in src/app/help-tips.ts; the look is .help-tip in help.css.

/** How far the open text keeps from the edge of the screen, so a tip near the edge never makes the page scroll sideways. */
const EDGE = 16;

export function HelpTip({ tip }: { tip: HelpTipKey }) {
  const { label, text } = HELP_TIPS[tip];
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState<{ top: number; left: number } | null>(null);
  const id = useId();
  const button = useRef<HTMLButtonElement>(null);
  const bubble = useRef<HTMLSpanElement>(null);
  // A tap focuses the button before its click: the focus must not open the tip that the click then closes again.
  const tapping = useRef(false);
  // Opened by keyboard focus: Enter or Space then keeps it open rather than closing what the person is reading.
  const byFocus = useRef(false);
  // The words move to the end of the body once mounted; a page drawn as a string has no body to move them to.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const inside = (node: EventTarget | null) => node instanceof Node && (button.current?.contains(node) === true || bubble.current?.contains(node) === true);

  useEffect(() => {
    if (!open) return;
    const outside = (e: Event) => {
      if (!inside(e.target)) setOpen(false);
    };
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    // Placed on the screen, so it moves with the button when the page or a table scrolls, or the screen changes size.
    const follow = () => setPlace(placeOf(button.current, bubble.current));
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    window.addEventListener('scroll', follow, true);
    window.addEventListener('resize', follow);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('scroll', follow, true);
      window.removeEventListener('resize', follow);
    };
  }, [open]);

  useLayoutEffect(() => {
    setPlace(open ? placeOf(button.current, bubble.current) : null);
  }, [open]);

  const words = (
    <span ref={bubble} id={id} role="status" className={open ? 'help-tip-text' : 'help-tip-text visually-hidden'} style={open && place ? place : undefined}>
      {open ? text : ''}
    </span>
  );

  return (
    <span className="help-tip">
      <button
        ref={button}
        type="button"
        className="btn-ghost btn-icon help-tip-button"
        aria-label={`More about ${label}`}
        aria-expanded={open}
        aria-describedby={id}
        onPointerDown={() => { tapping.current = true; }}
        onPointerCancel={() => { tapping.current = false; }}
        onFocus={() => {
          if (tapping.current) return;
          byFocus.current = true;
          setOpen(true);
        }}
        onBlur={(e) => {
          tapping.current = false;
          byFocus.current = false;
          if (!inside(e.relatedTarget)) setOpen(false);
        }}
        onClick={() => {
          tapping.current = false;
          if (byFocus.current) {
            byFocus.current = false;
            setOpen(true);
          } else setOpen((o) => !o);
        }}
      >
        <span aria-hidden="true">?</span>
      </button>
      {mounted ? createPortal(words, document.body) : words}
    </span>
  );
}

/** Under the button and inside the screen, over it when there is no room below, or nowhere while either is not drawn. */
function placeOf(button: HTMLElement | null, bubble: HTMLElement | null): { top: number; left: number } | null {
  if (!button || !bubble) return null;
  const at = button.getBoundingClientRect();
  const size = bubble.getBoundingClientRect();
  const right = document.documentElement.clientWidth - EDGE;
  const below = at.bottom + 4 + size.height <= window.innerHeight - EDGE;
  return { top: below ? at.bottom + 4 : Math.max(EDGE, at.top - 4 - size.height), left: Math.max(EDGE, Math.min(at.left, right - size.width)) };
}
