// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { endPendingLogout, getAccessToken, logoutPending, markLogoutPending, refreshSession, setAccessToken } from '../../auth/session';

// ADR-067 decision 9: the refresh cookie is HttpOnly, so only the logout call ends a staff session. A sign-out with no
// network notes a pending logout; the next start ends that session before anything restores it and stays on sign-in until
// it has, so a shared phone never hands the next person the last one's session. Every name is invented.

const seller = {
  user_id: '00000000-0000-4000-8000-0000000000b1',
  full_name: 'Test Seller 01',
  kind: 'staff',
  permissions: ['lending.loans.read'],
  modules: ['lending'],
  branches: [],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
} as unknown as Me;

const json = (status: number, body: unknown) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

function start() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

// Signed in as the seller, then Sign out pressed with no network.
async function signOutOffline() {
  setAccessToken('test-token-seller');
  vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
    path === '/api/v1/me' ? Promise.resolve({ data: seller, response: new Response() }) : new Promise(() => undefined)) as never);
  vi.spyOn(api, 'POST').mockImplementation((() => Promise.reject(new TypeError('offline'))) as never);
  const router = start();
  fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }, { timeout: 5000 }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
  expect(getAccessToken()).toBeNull();
  expect(logoutPending()).toBe(true);
  cleanup();
  vi.restoreAllMocks();
}

beforeEach(() => {
  window.scrollTo = vi.fn();
  window.localStorage.clear();
});
afterEach(() => {
  vi.useRealTimers();
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setAccessToken(null);
  window.localStorage.clear();
});

describe('signing out with no network', () => {
  it('never restores the session on the next start: it ends it on the server first and stays on sign-in', async () => {
    await signOutOffline();
    // Back online, the seller's cookie is still alive on the server.
    const me = vi.spyOn(api, 'GET').mockImplementation((() => new Promise(() => undefined)) as never);
    const fetch = vi.fn((url: string) =>
      url === '/api/v1/auth/refresh' ? json(200, { access_token: 'test-token-seller-2' }) : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal('fetch', fetch);
    const router = start();
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/logout',
      expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer test-token-seller-2' }) }),
    );
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(logoutPending()).toBe(false);
  });

  it('stays on sign-in while the logout still cannot reach the server, and no refresh restores the session', async () => {
    await signOutOffline();
    const fetch = vi.fn(() => Promise.reject(new TypeError('offline')));
    vi.stubGlobal('fetch', fetch);
    const router = start();
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    expect(logoutPending()).toBe(true);
    fetch.mockClear();
    expect(await refreshSession()).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });

  it('a new sign-in on this device clears the pending logout', async () => {
    await signOutOffline();
    setAccessToken('test-token-next-person');
    expect(logoutPending()).toBe(false);
  });
});

// A network that never answers (a captive portal, a very weak signal): the request only ends when its signal aborts.
const stalled = (_url: string, init?: RequestInit) =>
  new Promise<Response>((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal?.reason)));

describe('ending a pending sign-out on a connection that never answers', () => {
  async function startStalled(answer: typeof stalled = stalled) {
    await signOutOffline();
    const me = vi.spyOn(api, 'GET').mockImplementation((() => new Promise(() => undefined)) as never);
    const network = vi.fn(answer);
    vi.stubGlobal('fetch', network);
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // The test environment's AbortSignal.timeout runs on Node's own clock, which fake timers do not move: this one aborts
    // on the faked clock, as the browser's does on the page's.
    if (typeof AbortSignal.timeout === 'function') {
      vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms: number) => {
        const controller = new AbortController();
        setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms);
        return controller.signal;
      });
    }
    const router = start();
    // Fake time also moves with real time here, so the check before the limit keeps a margin for a slow runner.
    await vi.advanceTimersByTimeAsync(8_000);
    expect(router.state.location.pathname).toBe('/staff');
    await vi.advanceTimersByTimeAsync(2_000);
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-in'));
    return { me, network };
  }

  it('gives up after 10 seconds on the sign-in page with a notice, and never restores the session', async () => {
    const { me, network } = await startStalled();
    expect(await screen.findByText('Could not finish signing out. Connect to the internet and try again.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
    expect(AbortSignal.timeout).toHaveBeenCalledWith(10_000);
    expect(network).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(await refreshSession()).toBe(false);
  });

  it('gives up the same way where the browser has no AbortSignal.timeout', async () => {
    const timeout = Object.getOwnPropertyDescriptor(AbortSignal, 'timeout');
    Object.defineProperty(AbortSignal, 'timeout', { value: undefined, configurable: true });
    try {
      await startStalled();
      expect(await screen.findByText('Could not finish signing out. Connect to the internet and try again.')).toBeTruthy();
      expect(logoutPending()).toBe(true);
      expect(getAccessToken()).toBeNull();
    } finally {
      if (timeout) Object.defineProperty(AbortSignal, 'timeout', timeout);
    }
  });

  it('Try again ends the session once the network is back, and still signs nobody in', async () => {
    const { me } = await startStalled();
    const fetch = vi.fn((url: string) =>
      url === '/api/v1/auth/refresh' ? json(200, { access_token: 'test-token-seller-3' }) : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal('fetch', fetch);
    fireEvent.click(await screen.findByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByText('Could not finish signing out. Connect to the internet and try again.')).toBeNull());
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/logout',
      expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer test-token-seller-3' }) }),
    );
    expect(logoutPending()).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
  });

  it('shares one 10 second limit when the refresh answers late and the logout never does', async () => {
    // The refresh answers after 6 seconds; the logout then hangs, and the page still gives up at 10, not 16.
    const lateThenStalled = (url: string, init?: RequestInit) =>
      url === '/api/v1/auth/refresh'
        ? new Promise<Response>((resolve) => setTimeout(() => void json(200, { access_token: 'test-token-seller-4' }).then(resolve), 6_000))
        : stalled(url, init);
    const { me, network } = await startStalled(lateThenStalled);
    expect(network).toHaveBeenCalledWith('/api/v1/auth/logout', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(await screen.findByText('Could not finish signing out. Connect to the internet and try again.')).toBeTruthy();
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
    expect(me).not.toHaveBeenCalledWith('/api/v1/me');
  });

  it('Try again while still offline keeps the notice and the pending sign-out, and can be pressed again', async () => {
    await startStalled();
    const again = await screen.findByRole('button', { name: 'Try again' });
    fireEvent.click(again);
    await waitFor(() => expect(again.getAttribute('aria-busy')).toBe('true'));
    await vi.advanceTimersByTimeAsync(10_000);
    await waitFor(() => expect(again.getAttribute('aria-busy')).not.toBe('true'));
    expect(screen.getByText('Could not finish signing out. Connect to the internet and try again.')).toBeTruthy();
    expect(logoutPending()).toBe(true);
    expect(getAccessToken()).toBeNull();
  });

  it('refuses a sign-in while Try again is still ending the last session, so its answer never replaces the new cookie', async () => {
    await startStalled();
    const signIn = vi.spyOn(api, 'POST');
    fireEvent.click(await screen.findByRole('button', { name: 'Try again' }));
    fireEvent.change(screen.getByLabelText('Email or phone'), { target: { value: 'seller02@example.test' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'invented-password-02' } });
    const button = screen.getByRole('button', { name: 'Sign in' });
    expect(button.getAttribute('aria-busy')).toBe('true');
    fireEvent.submit(button.closest('form') as HTMLFormElement);
    expect(signIn).not.toHaveBeenCalled();
    // The retry gives up at its limit; then the form signs in as usual.
    await vi.advanceTimersByTimeAsync(10_000);
    await waitFor(() => expect(button.getAttribute('aria-busy')).not.toBe('true'));
    fireEvent.submit(button.closest('form') as HTMLFormElement);
    await waitFor(() => expect(signIn).toHaveBeenCalledWith('/api/v1/auth/staff/login', expect.anything()));
  });
});

describe('a sign-in while a pending sign-out is being ended', () => {
  it('leaves the decision to the new session: the late answer reports nothing ended', async () => {
    expect(markLogoutPending()).toBe(true);
    let answer: (r: Response) => void = () => undefined;
    vi.stubGlobal('fetch', vi.fn((url: string) =>
      url === '/api/v1/auth/refresh'
        ? new Promise<Response>((resolve) => { answer = resolve; })
        : Promise.resolve(new Response(null, { status: 204 }))));
    const ending = endPendingLogout();
    setAccessToken('test-token-next-person');
    answer(new Response(JSON.stringify({ access_token: 'test-token-seller-5' }), { status: 200 }));
    expect(await ending).toBe(false);
    expect(getAccessToken()).toBe('test-token-next-person');
  });
});
