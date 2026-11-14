// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PlatformRun } from '../../api/imports';
import { router as appRouter } from '../../app/router';

// The operator portal's import queue (issue #896): a committing run whose commit or resume stopped by itself is shown first,
// under "Stopped by itself" with its time and reason, never under "Being brought in"; a healthy committing run stays there.
// Fabricated data only.

const run = (slug: string, id: string, extra: Partial<PlatformRun> = {}): PlatformRun => ({
  tenant_slug: slug,
  metrics: { run_id: id, status: 'committing', fabricated: true, stamps: { created_at: '2026-11-01T08:00:00Z' } },
  ...extra,
});

let runs: PlatformRun[] = [];
vi.mock('../../api/imports', async (original) => {
  const real = await original<typeof import('../../api/imports')>();
  return { ...real, platformRuns: () => Promise.resolve(runs), killSwitch: () => Promise.resolve(true) };
});
vi.mock('../../api/onboarding', async (original) => {
  const real = await original<typeof import('../../api/onboarding')>();
  return { ...real, platformMe: () => Promise.resolve({ full_name: 'Test Operator 01', permissions: ['platform.tenants.read'] }) };
});

const { ImportQueue, byState } = await import('./imports');

function show() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/platform/imports'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ImportQueue />
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

afterEach(() => cleanup());

describe('the import queue', () => {
  it('shows a run that stopped by itself first, with its time and reason, and a healthy one as being brought in', async () => {
    runs = [run('test-shop-one', 'r-1'), run('test-shop-two', 'r-2', { loop_stopped: { at: '2026-11-14T07:32:00Z', code: 'interrupted' } })];
    show();
    const stopped = (await screen.findByRole('heading', { name: 'Stopped by itself (1)' })).closest('section') as HTMLElement;
    expect(within(stopped).getByText('test-shop-two')).toBeTruthy();
    expect(within(stopped).getByText('Stopped by itself on 14 Nov 2026 10:32. The server restarted or the work ended while it ran.')).toBeTruthy();
    const going = screen.getByRole('heading', { name: 'Being brought in (1)' }).closest('section') as HTMLElement;
    expect(within(going).getByText('test-shop-one')).toBeTruthy();
    expect(within(going).queryByText(/Stopped by itself/)).toBeNull();
  });

  it('groups only a committing run as stopped', () => {
    const committed = run('test-shop-three', 'r-3', { loop_stopped: { at: '2026-11-14T07:32:00Z', code: 'failed' } });
    committed.metrics = { ...committed.metrics, status: 'committed' };
    expect(byState([committed]).map((g) => g.status)).toEqual(['committed']);
  });
});
