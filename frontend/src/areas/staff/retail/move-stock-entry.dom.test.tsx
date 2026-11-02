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
// dashboard is left out. A Seller, a real role without retail.stock.transfer, sees neither, while the rest of the Retail home and
// of the menu still shows. The people are fabricated.

vi.mock('./dashboard', () => ({ RetailDashboard: () => null }));
vi.mock('./own-sales', () => ({ OwnSales: () => null }));

afterEach(cleanup);

const TOURS_OFF: Me['tours'] = { 'tours-off': { status: 'dismissed', version: 1 } };
const admin: Me = { ...mockMe('admin'), tours: TOURS_OFF };
const seller: Me = { ...mockMe('sales'), tours: TOURS_OFF };
const sideNav = (me: Me) => <StaffSideNav me={me} onBranch={() => undefined} busy={false} onSignOut={() => undefined} />;

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
    show(admin, sideNav(admin));
    const nav = screen.getByRole('navigation', { name: 'Staff areas' });
    const row = within(nav).getByRole('link', { name: /Move stock/ });
    expect(row.getAttribute('href')).toBe('/staff/retail/transfer');
  });

  it('shows neither the tile nor the menu row to a Seller, who has no transfer permission, while the rest still shows', () => {
    expect(admin.permissions).toContain('retail.stock.transfer');
    expect(seller.permissions).not.toContain('retail.stock.transfer');
    show(seller, <RetailHome />);
    const tiles = within(screen.getByRole('list', { name: 'Retail pages' })).getAllByRole('link');
    expect(tiles.length).toBeGreaterThan(0);
    expect(tiles.map((t) => t.textContent)).not.toContainEqual(expect.stringMatching(/Move stock/));
    cleanup();
    show(seller, sideNav(seller));
    const rows = within(screen.getByRole('navigation', { name: 'Staff areas' })).getAllByRole('link');
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.map((r) => r.textContent)).not.toContainEqual(expect.stringMatching(/Move stock/));
  });
});
