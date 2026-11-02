// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The shop's short link (issue #708, ADR-108): the sign-up field checks a name as it is typed and offers free names, the town
// first, with one tap; Business set-up shows the link with a Copy button and nothing to change it with (owner decision on #713).
// Fabricated shops only.

const { ShortNameField, shortNameFrom, shortNameProblem, cleanShortName } = await import('../onboarding/short-name');
const { linkNameFrom } = await import('../onboarding/link-not-found');
const { ShortLinkCard } = await import('./short-link');

afterEach(() => cleanup());

function wrap(node: ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;
}

const link = { short_name: 'testduka', link: 'rincoltech.test/testduka', url: 'https://rincoltech.test/testduka' };

describe('the short name rules in the form', () => {
  it('takes 3 to 20 letters and digits with single inner hyphens, and starts from the business name', () => {
    expect(shortNameProblem('testduka')).toBeNull();
    expect(shortNameProblem('testduka-gulu')).toBeNull();
    expect(shortNameProblem('ab')).toMatch(/3 to 20/);
    expect(shortNameProblem('a--b')).toMatch(/3 to 20/);
    expect(shortNameProblem('')).toBe('Choose a short name for your link.');
    expect(shortNameFrom('Tèstduka Hardware Ltd')).toBe('testduka');
    expect(shortNameFrom('A1')).toBe('');
    expect(cleanShortName('Testduka Gulu!')).toBe('testduka-gulu');
    expect(linkNameFrom('?name=oldduka')).toBe('oldduka');
    expect(linkNameFrom('?name=<b>')).toBeNull();
  });
});

describe('the short name field', () => {
  function Field({ check }: { check: (name: string) => Promise<object> }) {
    const [value, setValue] = useState('');
    return <ShortNameField id="sn" label="Short name for your link" value={value} onChange={setValue} check={check} host="rincoltech.test" />;
  }

  it('says a taken name is taken and puts its free names, the town first, one tap away', async () => {
    const check = vi.fn(async (name: string) =>
      name === 'testduka'
        ? { name, available: false, problem: 'taken', message: 'testduka is taken.', suggestions: ['testduka-gulu', 'testdukahard'] }
        : { name, available: true, suggestions: [] },
    );
    render(<Field check={check} />);
    expect(screen.getByText('rincoltech.test/')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Short name for your link'), { target: { value: 'Testduka' } });
    expect(await screen.findByText('testduka is taken.', {}, { timeout: 2000 })).toBeTruthy();
    const offers = screen.getAllByRole('button').map((b) => b.textContent);
    expect(offers).toEqual(['testduka-gulu', 'testdukahard']);
    fireEvent.click(screen.getByRole('button', { name: 'testduka-gulu' }));
    expect((screen.getByLabelText('Short name for your link') as HTMLInputElement).value).toBe('testduka-gulu');
    expect(await screen.findByText('rincoltech.test/testduka-gulu is free.', {}, { timeout: 2000 })).toBeTruthy();
    expect(check).toHaveBeenLastCalledWith('testduka-gulu');
  });
});

describe('the short link in Business set-up', () => {
  it('shows the link with Copy, and nothing to change it with', async () => {
    const writeText = vi.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    render(wrap(<ShortLinkCard load={async () => link} />));
    expect(await screen.findByText('rincoltech.test/testduka')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('https://rincoltech.test/testduka'));
    expect(await screen.findByText(/Copied/)).toBeTruthy();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Copy']);
  });

  it('selects the link for copying by hand when the phone will not copy', async () => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn(async () => Promise.reject(new Error('denied'))) } });
    render(wrap(<ShortLinkCard load={async () => link} />));
    fireEvent.click(await screen.findByRole('button', { name: 'Copy' }));
    expect(await screen.findByText(/press and hold, then choose Copy/)).toBeTruthy();
  });
});
