import { useEffect, useId, useRef, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { icons } from '../../components/icons';

// The one sheet (#520): pickers and confirmations open in it. On a phone (under 720px) it rises from the bottom edge and a
// swipe down on its top (by touch or pen, never a mouse) closes it; on a wider screen it is a centred dialog. Either way it is
// modal: aria-modal, the dimmed layer takes every tap on the page behind, the page does not scroll, focus starts inside and Tab
// stays inside, Escape, the close button and a tap on the dimmed page close it, and focus goes back
// to what opened it. The screen owns `open` and closes it in `onClose`. Drawn in a portal so no parent's overflow clips it. The
// look is .sheet-layer in src/app/ui/kit.css.

const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';
// How far down the top must be dragged, in px, to close the sheet rather than spring back.
const SWIPE_CLOSE = 96;

export function Sheet({ open, onClose, title, children, actions }: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** The buttons at the foot, the primary one first. */
  actions?: ReactNode;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const layer = useRef<HTMLDivElement>(null);
  const drag = useRef<{ from: number; by: number } | null>(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    // The page behind is not made inert: a toast's live region lives there and must still be heard.
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    // The first control of the content, then of the actions; the sheet itself when there is none.
    const parts = [...(panel.current?.querySelectorAll('.sheet-body, .sheet-actions') ?? [])];
    const first = parts.map((part) => part.querySelector<HTMLElement>(FOCUSABLE)).find(Boolean);
    (first ?? panel.current)?.focus();
    return () => {
      document.body.style.overflow = overflow;
      opener?.focus?.();
    };
  }, [open]);

  if (!open) return null;

  const keys = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      close.current();
      return;
    }
    if (e.key !== 'Tab' || !panel.current) return;
    const stops = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    const first = stops[0];
    const last = stops[stops.length - 1];
    if (!first || !last) return;
    if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const grab = (e: PointerEvent<HTMLElement>) => {
    if (e.pointerType === 'mouse' || (e.target as HTMLElement).closest('button')) return;
    drag.current = { from: e.clientY, by: 0 };
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const pull = (e: PointerEvent<HTMLElement>) => {
    if (!drag.current || !panel.current) return;
    drag.current.by = Math.max(0, e.clientY - drag.current.from);
    panel.current.style.transform = drag.current.by ? `translateY(${drag.current.by}px)` : '';
  };
  const release = () => {
    if (!drag.current || !panel.current) return;
    const far = drag.current.by >= SWIPE_CLOSE;
    drag.current = null;
    panel.current.style.transform = '';
    if (far) close.current();
  };

  return createPortal(
    <div ref={layer} className="sheet-layer">
      <div className="sheet-scrim" aria-hidden="true" onClick={() => close.current()} />
      <div ref={panel} className="sheet-panel" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} onKeyDown={keys}>
        <header className="sheet-head" onPointerDown={grab} onPointerMove={pull} onPointerUp={release} onPointerCancel={release}>
          <span className="sheet-grab" aria-hidden="true" />
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="sheet-close" aria-label="Close" onClick={() => close.current()}>
            {icons.close}
          </button>
        </header>
        <div className="sheet-body">{children}</div>
        {actions && <div className="sheet-actions">{actions}</div>}
      </div>
    </div>,
    document.body,
  );
}
