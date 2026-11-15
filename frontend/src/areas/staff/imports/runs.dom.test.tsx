// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { ImportRun } from '../../../api/imports';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Bring in your data with no import yet (issue #933, rule 3 of docs/ui/ux-rules.md): the empty line names the next step, Start a
// new import, which is the one primary below it; the line adds no second button. Fabricated data only.

let runs: ImportRun[] = [];

vi.mock('../../../api/imports', async (original) => {
  const real = await original<typeof import('../../../api/imports')>();
  return { ...real, importRuns: () => Promise.resolve(runs) };
});

const { ImportRuns } = await import('./runs');

const as = <T,>(value: T): T => value;
const importer = as<Me>({ ...mockMe('admin'), permissions: [...(mockMe('admin').permissions ?? []), 'core.imports.manage'] });

function show() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/imports/runs'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: importer, branch: null }}>
          <ImportRuns />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

afterEach(cleanup);

describe('Bring in your data', () => {
  it('names Start a new import on the empty line, and keeps that button the one primary', async () => {
    runs = [];
    show();
    expect(await screen.findByText('No import yet. Start a new import to bring in your first sheets.')).toBeTruthy();
    const primaries = Array.from(document.querySelectorAll('.btn-primary'));
    expect(primaries.map((b) => b.textContent)).toEqual(['Start a new import']);
  });

  it('shows no empty line once there is an import', async () => {
    runs = [as<ImportRun>({ id: 'run-1', status: 'uploaded', version: 1 })];
    show();
    expect(await screen.findByRole('list', { name: 'Imports' })).toBeTruthy();
    expect(screen.queryByText(/No import yet/)).toBeNull();
  });
});
