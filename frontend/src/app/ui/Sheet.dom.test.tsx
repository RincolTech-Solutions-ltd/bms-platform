// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Sheet } from './Sheet';

// The one sheet (#520): modal, focus inside and trapped, Escape, the close button, the dimmed page and a swipe down close it.

afterEach(cleanup);

function Example() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <main>
        <button type="button" onClick={() => setOpen(true)}>
          Choose a payment
        </button>
      </main>
      <Sheet
        open={open}
        onClose={() => setOpen(false)}
        title="How did the buyer pay?"
        actions={
          <>
            <button type="button" className="btn-primary" onClick={() => setOpen(false)}>
              Use cash
            </button>
            <button type="button" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </>
        }
      >
        <p>Pick one.</p>
        <button type="button">Mobile money</button>
      </Sheet>
    </>
  );
}

function openIt() {
  render(<Example />);
  const opener = screen.getByRole('button', { name: 'Choose a payment' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

const dialog = () => screen.queryByRole('dialog', { name: 'How did the buyer pay?' });

describe('Sheet', () => {
  it('opens as a named modal dialog with focus on its first control and the page behind inert', () => {
    openIt();
    expect(dialog()?.getAttribute('aria-modal')).toBe('true');
    expect(document.activeElement?.textContent).toBe('Mobile money');
    expect(document.querySelector('main')?.closest('[inert]')).toBeTruthy();
    expect(document.body.style.overflow).toBe('hidden');
  });

  it('keeps Tab inside, both ways', () => {
    openIt();
    const close = screen.getByRole('button', { name: 'Close' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    cancel.focus();
    fireEvent.keyDown(cancel, { key: 'Tab' });
    expect(document.activeElement).toBe(close);
    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(cancel);
  });

  it('closes on Escape and gives focus back to what opened it, with the page live again', () => {
    const opener = openIt();
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.querySelector('[inert]')).toBeNull();
    expect(document.body.style.overflow).toBe('');
  });

  it('closes from the close button and from a tap on the dimmed page', () => {
    openIt();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(dialog()).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Choose a payment' }));
    fireEvent.click(document.querySelector('.sheet-scrim')!);
    expect(dialog()).toBeNull();
  });

  it('closes on a swipe down its top, and springs back from a short one', () => {
    openIt();
    const head = document.querySelector('.sheet-head')!;
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(head, { clientY: 140, pointerId: 1 });
    expect((dialog() as HTMLElement).style.transform).toBe('translateY(40px)');
    fireEvent.pointerUp(head, { clientY: 140, pointerId: 1 });
    expect((dialog() as HTMLElement).style.transform).toBe('');
    expect(dialog()).toBeTruthy();
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(head, { clientY: 260, pointerId: 1 });
    fireEvent.pointerUp(head, { clientY: 260, pointerId: 1 });
    expect(dialog()).toBeNull();
  });

  it('draws nothing while closed', () => {
    render(<Example />);
    expect(dialog()).toBeNull();
    expect(document.querySelector('.sheet-layer')).toBeNull();
  });
});
