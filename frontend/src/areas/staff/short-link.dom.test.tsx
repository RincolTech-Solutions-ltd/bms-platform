// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The shop's short link (issue #708, ADR-108): the field checks a name as it is typed and offers free names, the town first, with
// one tap; Business set-up shows the link with Copy and the old link while it forwards, and only the Owner changes the name.
// Fabricated shops only.

const client = { GET: vi.fn(), PUT: vi.fn() };
vi.mock('../../api/client', async (original) => {
  const real = await original<typeof import('../../api/client')>();
  return { ...real, api: client };
});

const { ShortNameField, shortNameFrom, shortNameProblem, cleanShortName } = await import('../onboarding/short-name');
const { linkNameFrom } = await import('../onboarding/link-not-found');
const { ShortLinkCard } = await import('./short-link');
const { followMove } = await import('../../api/client');

afterEach(() => {
  cleanup();
  client.GET.mockReset();
  client.PUT.mockReset();
});

function wrap(node: ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;
}

const link = {
  short_name: 'testduka',
  link: 'rincoltech.com/testduka',
  url: 'https://rincoltech.com/testduka',
  sign_in_url: 'https://testduka-bms.rincoltech.test/sign-in',
  forwarding: [{ short_name: 'oldduka', link: 'rincoltech.com/oldduka', forwards_until: '2027-01-28T10:00:00Z' }],
  can_change: true,
};

// JUSTIFICATION-A3: review of #713 item 2 adds two behaviours (the moved header, the switch) that each need a test.
describe('an old address of a business that changed its short name', () => {
  it('sends the app to the new address on the same page, and only to a plain origin', () => {
    const go = vi.fn();
    const moved = (to: string, status = 404) => new Response('{}', { status, headers: { 'Bms-Moved-To': to } });
    window.history.replaceState(null, '', '/staff/retail/sale?x=1');
    expect(followMove(moved('https://newduka-bms.rincoltech.test'), go)).toBe(true);
    expect(go).toHaveBeenCalledWith('https://newduka-bms.rincoltech.test/staff/retail/sale?x=1');
    expect(followMove(moved('javascript:alert(1)'), go)).toBe(false);
    expect(followMove(moved('https://evil.test/path'), go)).toBe(false);
    expect(followMove(moved('https://newduka-bms.rincoltech.test', 200), go)).toBe(false);
    expect(go).toHaveBeenCalledTimes(1);
  });

  it('says to ask Rincol Tech where the short name cannot be changed yet', async () => {
    render(wrap(<ShortLinkCard isOwner={true} canManage={true} load={async () => ({ ...link, can_change: false })} />));
    expect(await screen.findByText(/To change your short name, ask Rincol Tech/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Change short name' })).toBeNull();
  });
});

describe('the short name rules in the form', () => {
  it('takes 3 to 20 letters and digits with single inner hyphens, and starts from the business name', () => {
    expect(shortNameProblem('modern')).toBeNull();
    expect(shortNameProblem('modern-kampala')).toBeNull();
    expect(shortNameProblem('ab')).toMatch(/3 to 20/);
    expect(shortNameProblem('a--b')).toMatch(/3 to 20/);
    expect(shortNameProblem('')).toBe('Choose a short name for your link.');
    expect(shortNameFrom('Modèrn Electricals Ltd')).toBe('modern');
    expect(shortNameFrom('A1')).toBe('');
    expect(cleanShortName('Modern Kampala!')).toBe('modern-kampala');
    expect(linkNameFrom('?name=oldduka')).toBe('oldduka');
    expect(linkNameFrom('?name=<b>')).toBeNull();
  });
});

describe('the short name field', () => {
  function Field({ check }: { check: (name: string) => Promise<object> }) {
    const [value, setValue] = useState('');
    return <ShortNameField id="sn" label="Short name for your link" value={value} onChange={setValue} check={check} />;
  }

  it('says a taken name is taken and puts its free names, the town first, one tap away', async () => {
    const check = vi.fn(async (name: string) =>
      name === 'modern'
        ? { name, available: false, problem: 'taken', message: 'modern is taken.', suggestions: ['modern-kampala', 'modernelec'] }
        : { name, available: true, suggestions: [] },
    );
    render(<Field check={check} />);
    fireEvent.change(screen.getByLabelText('Short name for your link'), { target: { value: 'Modern' } });
    expect(await screen.findByText('modern is taken.', {}, { timeout: 2000 })).toBeTruthy();
    const offers = screen.getAllByRole('button').map((b) => b.textContent);
    expect(offers).toEqual(['modern-kampala', 'modernelec']);
    fireEvent.click(screen.getByRole('button', { name: 'modern-kampala' }));
    expect((screen.getByLabelText('Short name for your link') as HTMLInputElement).value).toBe('modern-kampala');
    expect(await screen.findByText('rincoltech.com/modern-kampala is free.', {}, { timeout: 2000 })).toBeTruthy();
    expect(check).toHaveBeenLastCalledWith('modern-kampala');
  });
});

describe('the short link in Business set-up', () => {
  it('shows the link with Copy and the old link while it forwards', async () => {
    const writeText = vi.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    render(wrap(<ShortLinkCard isOwner={false} canManage={true} load={async () => link} />));
    expect(await screen.findByText('rincoltech.com/testduka')).toBeTruthy();
    expect(screen.getByText(/The old link rincoltech.com\/oldduka still forwards here until 28 Jan 2027/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('https://rincoltech.com/testduka'));
    expect(await screen.findByText(/Copied/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Change short name' })).toBeNull();
    expect(screen.getByText('Only the owner of the business changes the short name.')).toBeTruthy();
  });

  it('lets the Owner change it, says the app moves, and shows a refusal with free names', async () => {
    client.GET.mockResolvedValue({ data: { name: 'x', available: true, suggestions: [] } });
    client.PUT.mockResolvedValueOnce({
      error: { code: 'short_name_taken', detail: 'newduka is taken.', errors: [{ field: 'short_name', message: 'newduka is taken. Choose another.' }], suggestions: ['newduka2'] },
    });
    client.PUT.mockResolvedValueOnce({ data: { ...link, short_name: 'newduka2', link: 'rincoltech.com/newduka2', url: 'https://rincoltech.com/newduka2' } });
    render(wrap(<ShortLinkCard isOwner={true} canManage={true} load={async () => link} />));
    fireEvent.click(await screen.findByRole('button', { name: 'Change short name' }));
    fireEvent.change(screen.getByLabelText('New short name'), { target: { value: 'newduka' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change it' }));
    expect(await screen.findByText('newduka is taken. Choose another.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'newduka2' }));
    fireEvent.click(screen.getByRole('button', { name: 'Change it' }));
    expect(await screen.findByText(/Your short link is now rincoltech.com\/newduka2/)).toBeTruthy();
    expect(client.PUT).toHaveBeenLastCalledWith('/api/v1/settings/short-link', { body: { short_name: 'newduka2' } });
    expect(screen.getByRole('link', { name: 'Open the new sign-in page' })).toBeTruthy();
  });
});
