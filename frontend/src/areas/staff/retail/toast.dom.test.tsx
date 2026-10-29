// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DoneToast, type DoneWords, TOAST_MS, Toast, doneToasts, useToast } from './toast';

// The dark toast (#508, docs/ui-rules.md rule 15): a leading icon for its kind, announced by kind, gone after about four seconds.

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Toast', () => {
  it('leads with the check icon and is a polite status for a success', () => {
    render(<Toast message="Sale recorded." tone="success" />);
    const toast = screen.getByRole('status');
    expect(toast.className).toBe('toast toast-success');
    expect(toast.firstElementChild?.className).toBe('toast-icon');
    expect(toast.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(toast.textContent).toBe('Sale recorded.');
  });

  it('is an alert with the error icon for a failure, and hidden when the screen already says it', () => {
    render(<Toast message="Could not save." tone="error" />);
    expect(screen.getByRole('alert').className).toBe('toast toast-error');
    cleanup();
    const { container } = render(<Toast message="Saved." tone="success" silent />);
    expect(container.querySelector('.toast')?.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('shows for four seconds, then goes', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useToast());
    act(() => result.current.show('Saved.', 'info'));
    const { container, unmount } = render(<>{result.current.toast}</>);
    expect(container.querySelector('.toast-info')).not.toBeNull();
    unmount();
    expect(TOAST_MS).toBe(4000);
    act(() => vi.advanceTimersByTime(TOAST_MS));
    expect(result.current.toast).toBeNull();
  });

  it('has no default tone, so a refusal can never draw the success check by omission', () => {
    const { result } = renderHook(() => useToast());
    // @ts-expect-error the tone is required
    act(() => result.current.show('Already on the sale.'));
    act(() => result.current.show('Already on the sale.', 'info'));
    const { container } = render(<>{result.current.toast}</>);
    expect(container.querySelector('.toast')?.className).toBe('toast toast-info');
  });

  it('never lets an info hide an error still showing: it is said after it, in the error toast (review of #616)', () => {
    const { result } = renderHook(() => useToast());
    act(() => result.current.show('Test duvet cover is saved, but its count is not.', 'error'));
    act(() => result.current.show('The photo was not saved.', 'info'));
    const { container } = render(<>{result.current.toast}</>);
    expect(container.querySelector('.toast')?.className).toBe('toast toast-error');
    expect(container.querySelector('.toast')?.textContent).toBe('Test duvet cover is saved, but its count is not. The photo was not saved.');
    // A second info replaces the first after the error; it never piles up.
    act(() => result.current.show('Already on the sale.', 'info'));
    expect(render(<>{result.current.toast}</>).container.querySelector('.toast')?.textContent).toBe('Test duvet cover is saved, but its count is not. Already on the sale.');
    cleanup();
    const other = renderHook(() => useToast()).result;
    act(() => other.current.show('Saved.', 'success'));
    act(() => other.current.show('Already on the sale.', 'info'));
    expect(render(<>{other.current.toast}</>).container.querySelector('.toast')?.className).toBe('toast toast-info');
  });
});

// JUSTIFICATION-A3: the tests of the new shared success toast of #682; no existing test covers mutation meta.
describe('DoneToast (#682)', () => {
  function Write({ words, send }: { words: DoneWords; send: () => Promise<{ name: string; executed: boolean }> }) {
    const save = useMutation({ mutationFn: send, meta: { success: words } });
    return <button type="button" onClick={() => save.mutate()}>Save</button>;
  }
  const app = (ui: JSX.Element) => render(<QueryClientProvider client={new QueryClient({ mutationCache: doneToasts() })}>{ui}<DoneToast /></QueryClientProvider>);

  it('says the words of meta.success in the one success toast when the write works', async () => {
    app(<Write words="Item added" send={() => Promise.resolve({ name: 'Test Bulb', executed: true })} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const toast = await screen.findByRole('status');
    expect(toast.className).toBe('toast toast-success');
    expect(toast.textContent).toBe('Item added');
  });

  it('builds the words from the result, and says nothing for null or a failure', async () => {
    const words = (r: { name: string; executed: boolean }) => (r.executed ? `Stock moved to ${r.name}` : null);
    app(<Write words={words} send={() => Promise.resolve({ name: 'Test Store', executed: true })} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findByRole('status')).textContent).toBe('Stock moved to Test Store');
    cleanup();
    app(<Write words={words} send={() => Promise.resolve({ name: 'Test Store', executed: false })} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    expect(screen.queryByRole('status')).toBeNull();
    cleanup();
    app(<Write words="Item added" send={() => Promise.reject(new Error('refused'))} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    expect(screen.queryByRole('status')).toBeNull();
  });
});
