// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState, type FormEvent } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BusyButton } from './BusyButton';

function Save({ send }: { send: () => Promise<void> }) {
  const save = useMutation({ mutationFn: send });
  return (
    <BusyButton type="button" className="btn-primary" busy={save.isPending} onClick={() => save.mutate()}>
      Save
    </BusyButton>
  );
}

function withQueries(ui: JSX.Element) {
  return render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);
}

afterEach(cleanup);

describe('the busy button (#456)', () => {
  it('is a plain button while idle: no spinner, not busy, nothing announced', () => {
    render(<BusyButton busy={false} className="btn-primary">Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).not.toHaveProperty('disabled', true);
    expect(button.getAttribute('aria-busy')).toBeNull();
    expect(button.className).toBe('btn-primary busy-button');
    expect(button.querySelector('.busy-spinner')).toBeNull();
    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('while busy keeps its label, shows the spinner, is disabled and aria-busy, and says Working', () => {
    render(<BusyButton busy className="btn-primary">Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.textContent).toBe('Save');
    expect(button.querySelector('.busy-spinner')?.getAttribute('aria-hidden')).toBe('true');
    expect(screen.getByRole('status').textContent).toBe('Working');
  });

  it('keeps a disabled button disabled when not busy', () => {
    render(<BusyButton busy={false} disabled>Save</BusyButton>);
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('sends once on a double click, then can be pressed again when the request is over', async () => {
    let finish = () => {};
    const send = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    withQueries(<Save send={send} />);
    const button = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    fireEvent.click(button);
    expect(send).toHaveBeenCalledTimes(1);
    await act(async () => finish());
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  });

  it('lets a press that started nothing be followed by another one', async () => {
    const onClick = vi.fn();
    render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    fireEvent.click(button);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve)); await new Promise((resolve) => setTimeout(resolve)); });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it('drops an Enter re-submit of its form while busy', () => {
    const onSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    function Form() {
      const [busy, setBusy] = useState(false);
      return (
        <form onSubmit={(e) => { onSubmit(e); setBusy(true); }}>
          <input aria-label="Amount" />
          <BusyButton type="submit" busy={busy}>Record</BusyButton>
        </form>
      );
    }
    render(<Form />);
    const form = screen.getByLabelText('Amount').closest('form') as HTMLFormElement;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Record' }).getAttribute('aria-busy')).toBe('true');
  });

  it('submits its form once on a double click', () => {
    const onSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <BusyButton type="submit" busy={false}>Record</BusyButton>
      </form>,
    );
    const button = screen.getByRole('button', { name: 'Record' });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
