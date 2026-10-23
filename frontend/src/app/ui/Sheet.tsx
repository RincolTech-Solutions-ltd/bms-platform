import * as Dialog from '@radix-ui/react-dialog';
import { useRef, type PointerEvent, type ReactNode } from 'react';
import { icons } from '../../components/icons';

// The one sheet (#520): pickers and confirmations open in it. It is a Radix Dialog styled by our own tokens. On a phone (under
// 720px) it rises from the bottom edge and a swipe down on its top, by touch or pen and never a mouse, closes it; on a wider
// screen it is a centred dialog. Radix makes it modal: focus is trapped inside, Escape, a tap on the dimmed page and the close
// button close it, the page does not scroll, and focus goes back to what opened it. Focus starts on the first control of the
// content (not the close button). The screen owns `open` and closes it in `onClose`. The look is .sheet-layer in
// src/app/ui/kit.css.

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
  const panel = useRef<HTMLDivElement>(null);
  // What had focus when the sheet opened, given it back on close (Radix gives it back only to a Dialog.Trigger).
  const opener = useRef<HTMLElement | null>(null);
  const wasOpen = useRef(false);
  if (open && !wasOpen.current) opener.current = document.activeElement as HTMLElement | null;
  wasOpen.current = open;
  const drag = useRef<{ from: number; by: number } | null>(null);

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
    if (far) onClose();
  };

  return (
    <Dialog.Root open={open} onOpenChange={(next) => next || onClose()}>
      <Dialog.Portal>
        <div className="sheet-layer">
          <Dialog.Overlay className="sheet-scrim" />
          <Dialog.Content
            ref={panel}
            className="sheet-panel"
            aria-describedby={undefined}
            onCloseAutoFocus={(e) => {
              e.preventDefault();
              opener.current?.focus?.();
            }}
            onOpenAutoFocus={(e) => {
              // The first control of the content, then of the actions; the sheet itself when there is none.
              const parts = [...(panel.current?.querySelectorAll('.sheet-body, .sheet-actions') ?? [])];
              const first = parts.map((part) => part.querySelector<HTMLElement>(FOCUSABLE)).find(Boolean);
              e.preventDefault();
              (first ?? panel.current)?.focus();
            }}
          >
            <header className="sheet-head" onPointerDown={grab} onPointerMove={pull} onPointerUp={release} onPointerCancel={release}>
              <span className="sheet-grab" aria-hidden="true" />
              <Dialog.Title>{title}</Dialog.Title>
              <Dialog.Close className="sheet-close" aria-label="Close">
                {icons.close}
              </Dialog.Close>
            </header>
            <div className="sheet-body">{children}</div>
            {actions && <div className="sheet-actions">{actions}</div>}
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
