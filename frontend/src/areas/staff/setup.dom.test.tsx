// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../api/retail-mock';
import { StaffContext } from './context';

// After a logo is saved on the set-up screen, the admin is told it is now the app's icon and how it reaches a phone (issue #453,
// ADR-066), with the iPhone steps one tap away. Fabricated business and logo.

const settings = { version: 1, display_name: 'Test Traders', display_name_set: true, setup_dismissed: true } as Record<string, unknown>;
const client = {
  GET: vi.fn(async () => ({ data: { items: [] } })),
  PUT: vi.fn(async () => {
    settings.logo_document_id = '00000000-0000-4000-8000-0000000000a1';
    return { data: settings };
  }),
  DELETE: vi.fn(async () => {
    delete settings.logo_document_id;
    return { data: settings };
  }),
  PATCH: vi.fn(),
};

vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  api: new Proxy({}, { get: (_t, name: string) => (client as Record<string, unknown>)[name] }),
  fetchSettings: async () => ({ ...settings }),
}));
vi.mock('../../app/branding', async (original) => ({
  ...(await original<typeof import('../../app/branding')>()),
  useShellBrand: () => ({ name: 'Test Traders', logoSrc: '/logo.png', themePrimary: null, themeText: null }),
}));
vi.mock('@tanstack/react-router', async (original) => ({
  ...(await original<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: ReactNode }) => <a href="#test">{children}</a>,
}));

const { Setup } = await import('./setup');

beforeEach(() => {
  delete settings.logo_document_id;
});

afterEach(cleanup);

function show() {
  const me = mockMe('owner');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: { ...me, permissions: ['core.settings.manage'] }, branch: null, chooseBranch: () => {} }}>
        <Setup />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function uploadLogo() {
  const input = (await screen.findByLabelText(/Upload a logo/)) as HTMLInputElement;
  fireEvent.change(input, { target: { files: [new File(['x'], 'logo.png', { type: 'image/png' })] } });
  await screen.findByText(/Logo saved/);
}

describe('the set-up note on the new icon', () => {
  it('is not there before a logo is saved', async () => {
    show();
    await screen.findByRole('heading', { name: 'Business set-up' });
    expect(screen.queryByTestId('new-icon-note')).toBeNull();
  });

  it('appears after a save and reveals the iPhone steps', async () => {
    show();
    await uploadLogo();
    const note = await screen.findByTestId('new-icon-note');
    expect(note.textContent).toContain("Your logo is now your app's icon. Android phones update it by themselves, usually within a day.");
    expect(note.textContent).toContain('On an iPhone, remove BMS from the home screen and add it again.');
    fireEvent.click(screen.getByRole('button', { name: 'Show me how on an iPhone' }));
    expect(screen.getByTestId('ios-install-steps')).toBeTruthy();
  });

  it('goes when the logo is removed', async () => {
    show();
    await uploadLogo();
    await screen.findByTestId('new-icon-note');
    fireEvent.click(screen.getByRole('button', { name: 'Remove the logo' }));
    await screen.findByText('Logo removed.');
    await waitFor(() => expect(screen.queryByTestId('new-icon-note')).toBeNull());
  });
});
