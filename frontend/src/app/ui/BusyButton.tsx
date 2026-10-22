import { useEffect, useRef, type ButtonHTMLAttributes, type MouseEvent } from 'react';

// The one busy button (#456): every button that writes (a sale, a payment, an invitation, a sign-in)
// shows a small spinner beside its label while its request runs and cannot be pressed twice. The label
// stays and the spinner sits over the end padding, so the width never jumps; the button is disabled and
// aria-busy, and a visually hidden status says "Working". Pass the mutation's isPending (or the screen's
// own submitting state) as `busy`; every other prop is a normal <button> prop.

export type BusyButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { busy: boolean };

export function BusyButton({ busy, disabled, className, onClick, children, ...rest }: BusyButtonProps) {
  const button = useRef<HTMLButtonElement>(null);
  const busyNow = useRef(busy);
  busyNow.current = busy;
  // A second press can land before the pending state renders (a double click is two events in one
  // task), so a press holds the button until busy shows, or for one more task if the press started
  // nothing (a form that refused its input).
  const held = useRef(false);

  useEffect(() => {
    if (!busy) held.current = false;
  }, [busy]);

  // Enter in a field presses the form's first submit button, which the press guard covers; a form with
  // another submit button first, or one submitted from code, still submits, so while busy (or held by a
  // press of this button) the form drops any submit this button did not send before the screen sees it.
  useEffect(() => {
    const owner = button.current?.form;
    if (!owner || (rest.type !== undefined && rest.type !== 'submit')) return;
    const drop = (e: SubmitEvent) => {
      if (busyNow.current || (held.current && e.submitter !== button.current)) {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    };
    owner.addEventListener('submit', drop);
    return () => owner.removeEventListener('submit', drop);
  }, [rest.type]);

  function press(e: MouseEvent<HTMLButtonElement>) {
    if (busyNow.current || held.current) {
      e.preventDefault();
      return;
    }
    held.current = true;
    onClick?.(e);
    // Two tasks later the query library and React have rendered any pending state the press started.
    setTimeout(() => setTimeout(() => {
      if (!busyNow.current) held.current = false;
    }));
  }

  return (
    <>
      <button
        ref={button}
        {...rest}
        className={className ? `${className} busy-button` : 'busy-button'}
        disabled={disabled || busy}
        aria-busy={busy || undefined}
        onClick={press}
      >
        {children}
        {busy && <span className="busy-spinner" aria-hidden="true" />}
      </button>
      <span className="visually-hidden" role="status">
        {busy ? 'Working' : ''}
      </span>
    </>
  );
}
