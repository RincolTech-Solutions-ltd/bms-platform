// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { FeedbackItem } from '../../../api/feedback';
import { FeedbackHistory } from './page';

// What you sent on Feedback (issue #933, rule 1 of docs/ui/ux-rules.md): the last ten, Show more for the rest, each message
// closed to its first line until tapped, the reply and the whole message behind it. Fabricated messages only.

afterEach(cleanup);

/** A fixture checked against the API type `T` (an object literal passed here gets the excess property check). */
const as = <T,>(value: T): T => value;

const sent = (n: number) =>
  as<FeedbackItem>({
    id: `f-${n}`, kind: 'idea', message: `\nTest message ${n}\nSecond line ${n}`, status: 'seen', has_screenshot: false,
    created_at: `2026-11-${String(30 - n).padStart(2, '0')}T08:00:00Z`, reply_note: n === 1 ? 'Test reply' : undefined,
  });

function show(items: FeedbackItem[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FeedbackHistory load={() => Promise.resolve(items)} />
    </QueryClientProvider>,
  );
}

describe('what you sent', () => {
  it('shows the last ten, each closed to its first line, and Show more adds the rest', async () => {
    const view = show(Array.from({ length: 12 }, (_, i) => sent(i + 1)));
    await screen.findByText('Test message 1', { selector: '.feedback-first-line' });
    const rows = () => Array.from(view.container.querySelectorAll('details.feedback-sent')) as HTMLDetailsElement[];
    expect(rows()).toHaveLength(10);
    expect(rows().every((row) => !row.open)).toBe(true);
    // The first line with words on it, never the blank line before it.
    expect(rows().map((row) => row.querySelector('.feedback-first-line')?.textContent)).toEqual(Array.from({ length: 10 }, (_, i) => `Test message ${i + 1}`));
    const summary = rows()[0]?.querySelector('summary') as HTMLElement;
    expect(summary.textContent).toContain('Read by the BMS team, with a reply');
    expect(summary.textContent).not.toContain('Second line');
    expect(summary.textContent).not.toContain('Test reply');
    fireEvent.click(summary);
    expect(rows()[0]?.open).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Show more' }));
    expect(rows()).toHaveLength(12);
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull();
  });

  it('shows the newest ten first even when the list arrives oldest first', async () => {
    // sent(1) is the newest (29 Nov), sent(12) the oldest (18 Nov); the list comes oldest first.
    const view = show(Array.from({ length: 12 }, (_, i) => sent(12 - i)));
    await screen.findByText('Test message 1', { selector: '.feedback-first-line' });
    const lines = Array.from(view.container.querySelectorAll('.feedback-first-line')).map((s) => s.textContent);
    expect(lines).toEqual(Array.from({ length: 10 }, (_, i) => `Test message ${i + 1}`));
    expect(screen.queryByText('Test message 12', { selector: '.feedback-first-line' })).toBeNull();
  });

  it('offers no Show more for ten or fewer', async () => {
    show(Array.from({ length: 10 }, (_, i) => sent(i + 1)));
    await screen.findByText('Test message 10', { selector: '.feedback-first-line' });
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull();
  });
});
