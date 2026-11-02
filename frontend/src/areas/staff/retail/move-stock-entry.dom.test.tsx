// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { TourProvider } from '../../../tour/host';
import { StaffContext } from '../context';
import { StaffSideNav } from '../route';
import { RetailHome } from './home';

// Issue #104: a tenant administrator finds Move stock in one tap from the Retail home (its tile) and in two from anywhere (the
// menu, then its row), both opening /staff/retail/transfer. The figures on the home are not what this asks about, so the
// dashboard is left out. The person is fabricated.

vi.mock('./dashboard', () => ({ RetailDashboard: () => null }));
vi.mock('./own-sales', () => ({ OwnSales: () => null }));

afterEach(cleanup);

const admin = { ...mockMe('admin'), tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as unknown as Me;

function show(me: Me, ui: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>
          <TourProvider me={me} saveProgress={false}>
            {ui}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('Move stock for a tenant administrator (#104)', () => {
  it('has a tile on the Retail home that opens the Move stock page', () => {
    show(admin, <RetailHome />);
    const tiles = screen.getByRole('list', { name: 'Retail pages' });
    const tile = within(tiles).getByRole('link', { name: /Move stock/ });
    expect(tile.getAttribute('href')).toBe('/staff/retail/transfer');
  });

  it('has a row in the staff menu that opens the Move stock page', () => {
    show(admin, <StaffSideNav me={admin} onBranch={() => undefined} busy={false} onSignOut={() => undefined} />);
    const nav = screen.getByRole('navigation', { name: 'Staff areas' });
    const row = within(nav).getByRole('link', { name: /Move stock/ });
    expect(row.getAttribute('href')).toBe('/staff/retail/transfer');
  });

  it('shows neither to a person without the transfer permission', () => {
    const seller = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'retail.stock.transfer') } as unknown as Me;
    show(seller, <RetailHome />);
    expect(screen.queryByRole('link', { name: /Move stock/ })).toBeNull();
  });
});
