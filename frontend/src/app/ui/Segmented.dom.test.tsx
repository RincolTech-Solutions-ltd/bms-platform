// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Segmented } from './Segmented';

// The one "pick one" control (#520): a named group of toggle buttons, one pressed, arrow keys moving between them.

afterEach(cleanup);

const LEVELS = [
  { value: 'all', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
] as const;

function Example({ clearable = false }: { clearable?: boolean }) {
  const [value, setValue] = useState<string | null>('all');
  return <Segmented label="Show" tour="stock-show" options={LEVELS} value={value} onChange={(v) => setValue(clearable && v === value ? null : v)} />;
}

describe('Segmented', () => {
  it('is a named group of buttons with only the chosen one pressed', () => {
    render(<Example />);
    const group = screen.getByRole('group', { name: 'Show' });
    expect(group.className).toBe('segmented');
    expect(group.getAttribute('data-tour')).toBe('stock-show');
    const pressed = screen.getAllByRole('button').map((b) => [b.textContent, b.getAttribute('aria-pressed')]);
    expect(pressed).toEqual([['All items', 'true'], ['Out of stock', 'false'], ['Low stock', 'false']]);
    for (const b of screen.getAllByRole('button')) expect(b.getAttribute('type')).toBe('button');
  });

  it('moves the choice on a press', () => {
    render(<Example />);
    fireEvent.click(screen.getByRole('button', { name: 'Low stock' }));
    expect(screen.getByRole('button', { name: 'Low stock' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'All items' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('lets a screen clear the choice when the chosen option is pressed again', () => {
    render(<Example clearable />);
    fireEvent.click(screen.getByRole('button', { name: 'All items' }));
    expect(screen.getAllByRole('button').every((b) => b.getAttribute('aria-pressed') === 'false')).toBe(true);
  });

  it('is one Tab stop: the chosen option, or the first while none is', () => {
    render(<Example clearable />);
    expect(screen.getAllByRole('button').map((b) => b.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
    fireEvent.click(screen.getByRole('button', { name: 'Low stock' }));
    expect(screen.getAllByRole('button').map((b) => b.getAttribute('tabindex'))).toEqual(['-1', '-1', '0']);
    fireEvent.click(screen.getByRole('button', { name: 'Low stock' }));
    expect(screen.getAllByRole('button').map((b) => b.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
  });

  it('moves focus with the arrow keys and wraps at the ends', () => {
    render(<Example />);
    const [all, out, low] = screen.getAllByRole('button') as HTMLButtonElement[];
    all!.focus();
    fireEvent.keyDown(all!, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(out);
    fireEvent.keyDown(out!, { key: 'ArrowRight' });
    fireEvent.keyDown(low!, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(all);
    fireEvent.keyDown(all!, { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(low);
  });
});
