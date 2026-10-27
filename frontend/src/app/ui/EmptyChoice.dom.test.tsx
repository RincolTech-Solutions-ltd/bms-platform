// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { focusFirstMissing } from './Field';

// The empty choice (issue #619): one plain line and one way to fill the list, or who to ask; Save takes the person to it.

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useRouter: () => null, Link: ({ children, to, search, ...rest }: { children: unknown; to: string; search?: Record<string, string> }) => <a href={`${to}?back=${encodeURIComponent(search?.back ?? '')}`} {...rest}>{children as never}</a> };
});

const { EmptyChoice, useComeBack } = await import('./EmptyChoice');

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

describe('EmptyChoice', () => {
  it('names the field, says the list is empty and offers the screen that adds one, to come back here', () => {
    window.history.replaceState(null, '', '/staff/retail/restock');
    render(<EmptyChoice id="supplier" label="Supplier" text="No suppliers yet." ask="Ask the owner to add a supplier first." add={{ label: 'Add a supplier', to: '/staff/retail/catalogue/suppliers' }} />);
    expect(screen.getByText('Supplier')).toBeTruthy();
    expect(screen.getByText('No suppliers yet.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add a supplier' }).getAttribute('href')).toBe('/staff/retail/catalogue/suppliers?back=%2Fstaff%2Fretail%2Frestock');
    expect(screen.queryByText(/Ask the owner/)).toBeNull();
  });

  it('says who to ask when the session may not add one', () => {
    render(<EmptyChoice id="unit" text="No units yet." ask="Ask the owner to add a unit first." add={null} />);
    expect(screen.getByText('No units yet. Ask the owner to add a unit first.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('is where Save takes the person when the field is needed', () => {
    const { container } = render(
      <div>
        <input aria-required="true" defaultValue="filled" />
        <EmptyChoice id="unit" label="Counted in" required text="No units yet." ask="x" add={{ label: 'Add a new unit', onClick: () => undefined }} />
      </div>,
    );
    expect(focusFirstMissing(container)).toBe(true);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Add a new unit' }));
  });

  it('comes back only to a page of the app', () => {
    let back: (() => void) | null = null;
    function Probe() {
      back = useComeBack();
      return null;
    }
    window.history.replaceState(null, '', '/staff/retail/catalogue/units?back=%2Fstaff%2Fretail%2Fsale');
    render(<Probe />);
    expect(back).not.toBeNull();
    cleanup();
    window.history.replaceState(null, '', '/staff/retail/catalogue/units?back=%2F%2Fexample.test');
    render(<Probe />);
    expect(back).toBeNull();
    cleanup();
    window.history.replaceState(null, '', '/staff/retail/catalogue/units');
    render(<Probe />);
    expect(back).toBeNull();
  });
});
