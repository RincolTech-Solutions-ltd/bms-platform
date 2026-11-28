// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Outlet, RouterContextProvider, RouterProvider, createMemoryHistory, createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';

// The business's main address (issue #969, ADR-132): the landing page sends a device signed in as staff, or a phone with no
// network that kept the last person's profile, into the staff area as the main address; everyone else, and a refresh that
// fails in any way, sees the welcome page. The staff shell with no session goes to sign-in, never back to the main address, so
// the two cannot loop. Fabricated people only.

const session = vi.hoisted(() => ({ token: null as string | null, refresh: (): Promise<boolean> => Promise.resolve(false), offline: false }));
const offline = vi.hoisted(() => ({ profile: null as Me | null }));

vi.mock('../auth/session', async (original) => ({
  ...(await original<typeof import('../auth/session')>()),
  getAccessToken: () => session.token,
  refreshSession: () => session.refresh(),
  refreshFailedOffline: () => session.offline,
  subscribe: () => () => undefined,
}));
vi.mock('../offline/offline-context', async (original) => ({
  ...(await original<typeof import('../offline/offline-context')>()),
  lastProfile: async () => offline.profile,
}));

const { TenantLandingLoader } = await import('./landing');
const { endMainEntry, mainEntryPending } = await import('../auth/session');

beforeEach(() => {
  session.token = null;
  session.refresh = () => Promise.resolve(false);
  session.offline = false;
  offline.profile = null;
});

afterEach(() => {
  cleanup();
  endMainEntry();
});

function show() {
  const root = createRootRoute({ component: Outlet });
  const landing = createRoute({ getParentRoute: () => root, path: '/', component: TenantLandingLoader });
  const staff = createRoute({ getParentRoute: () => root, path: '/staff', component: () => <h1>Staff area</h1> });
  const signIn = createRoute({ getParentRoute: () => root, path: '/sign-in', component: () => <h1>Sign in</h1> });
  const router = createRouter({ routeTree: root.addChildren([landing, staff, signIn]), history: createMemoryHistory({ initialEntries: ['/'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['branding'], { modules: ['retail'] });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const welcome = () => screen.findByRole('heading', { name: 'Welcome' });

describe('the main address on a business host (#969)', () => {
  it('opens the staff area as the main address for a device that holds a session', async () => {
    session.token = 'fabricated-access-token';
    const router = show();
    await screen.findByRole('heading', { name: 'Staff area' });
    expect(router.state.location.pathname).toBe('/staff');
    expect(mainEntryPending()).toBe(true);
  });

  it('opens it after a refresh that restores the session', async () => {
    session.refresh = () => Promise.resolve(true);
    show();
    await screen.findByRole('heading', { name: 'Staff area' });
    expect(mainEntryPending()).toBe(true);
  });

  it('shows the welcome page to a visitor who is not signed in', async () => {
    const router = show();
    await welcome();
    expect(router.state.location.pathname).toBe('/');
    expect(mainEntryPending()).toBe(false);
  });

  it('shows the welcome page when the refresh throws, never an endless loader', async () => {
    session.refresh = () => Promise.reject(new Error('fabricated failure'));
    show();
    await welcome();
  });

  it('shows the welcome page offline to a first-time visitor with no profile kept', async () => {
    session.offline = true;
    const router = show();
    await welcome();
    expect(router.state.location.pathname).toBe('/');
  });

  it('opens the staff area offline for a phone that kept the last person', async () => {
    session.offline = true;
    offline.profile = mockMe('sales');
    show();
    await screen.findByRole('heading', { name: 'Staff area' });
    expect(mainEntryPending()).toBe(true);
  });

  it('offers Staff sign-in on the welcome page', async () => {
    show();
    await welcome();
    expect(screen.getByRole('link', { name: 'Staff sign-in' }).getAttribute('href')).toBe('/sign-in');
    fireEvent.click(screen.getByRole('link', { name: 'Staff sign-in' }));
    await screen.findByRole('heading', { name: 'Sign in' });
  });
});

describe('the real staff shell without a session (#969)', () => {
  it('goes to sign-in, never back to the main address, so the landing page and the shell cannot loop', async () => {
    const { router: appRouter } = await import('./router');
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'), { timeout: 5000 });
  });
});

describe('the end of a password reset (#969)', () => {
  it('opens the staff area as the main address after the recovery codes', async () => {
    const { ResetPassword } = await import('../areas/auth/reset-password');
    const { router: appRouter } = await import('./router');
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/reset-password'] }) });
    render(
      <RouterContextProvider router={router}>
        <ResetPassword token="fabricated-reset-token-0001" initial={{ kind: 'codes', codes: ['fab01-code1'] }} />
      </RouterContextProvider>,
    );
    fireEvent.click(await screen.findByLabelText('I have saved these codes'));
    fireEvent.click(screen.getByRole('button', { name: /Continue|Go to the app|Done/ }));
    await waitFor(() => expect(mainEntryPending()).toBe(true));
  });
});
