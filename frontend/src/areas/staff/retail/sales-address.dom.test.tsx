// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SalesSummary, SalesSummaryGroup, SalesSummaryQuery } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';

// All sales opens a sale at its own address (issue #933, ticket 1, rule 2 of docs/ui/ux-rules.md): /staff/retail/sales/<id>,
// so the address is the record and can be shared. Back to the list finds the same groups open and the same search typed,
// once. Fabricated names and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const summary = vi.fn<(q: SalesSummaryQuery) => Promise<SalesSummary>>();
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      salesSummary: (q: SalesSummaryQuery) => summary(q),
      listSales: async () => ({ items: [sale] }),
      getSale: async () => sale,
      listProducts: async () => [],
    },
  };
});

const { AllSales, openedFromAllSales } = await import('./sales');
const { SalePageView, useCloseSale } = await import('./sale-page');

const me = mockMe('admin');
const SALE = '5eed0000-0000-4000-8000-000000000933';
/** A fixture checked against the API type `T` (an object literal passed here gets the excess property check). */
const as = <T,>(value: T): T => value;
const sale = as<Sale>({
  id: SALE, sale_no: 'S-900933', branch_id: me.branches?.[0]?.id, sale_date: '2026-10-19', payment_method: 'cash', status: 'completed',
  buyer_name: 'Test Buyer One', total_minor: 3000, paid_minor: 3000, balance_minor: 0, historical: false,
});
const group = (key: string, label: string, over: Partial<SalesSummaryGroup> = {}) =>
  as<SalesSummaryGroup>({ key, label, sales_count: 1, total_minor: 3000, voided_count: 0, ...over });
const LEVELS: Record<string, SalesSummaryGroup[]> = {
  branch: [group('b1', 'Test Branch Gayaza')],
  year: [group('2026', '2026', { period: '2026', from: '2026-01-01', to: '2026-12-31' })],
  month: [group('2026-10', '2026-10', { period: '2026-10', from: '2026-10-01', to: '2026-10-31' })],
  seller: [group('u1', 'Test Seller One')],
  day: [group('2026-10-19', '2026-10-19', { period: '2026-10-19', from: '2026-10-19', to: '2026-10-19' })],
};

function show(router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/sales'] }) })) {
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}>
          <AllSales />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return router;
}

const head = (name: RegExp) => screen.findByRole('button', { name });
const trail = async () => within(await screen.findByRole('navigation', { name: 'Where you are' })).getAllByRole('listitem').map((li) => li.textContent);
const buyerBox = () => screen.getByLabelText('Buyer, phone or sale number') as HTMLInputElement;

beforeEach(() => {
  localStorage.clear();
  summary.mockImplementation(async (q) => {
    const chain = q.chain ?? [q.level];
    const next = chain[chain.indexOf(q.level) + 1];
    return as<SalesSummary>({ group_by: q.level, levels: chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: LEVELS[q.level] ?? [] });
  });
});
afterEach(() => {
  cleanup();
  summary.mockReset();
});

async function openTheDay() {
  fireEvent.click(await head(/^Test Branch Gayaza /));
  fireEvent.click(await head(/^2026 /));
  fireEvent.click(await head(/^October 2026 /));
  fireEvent.click(await head(/^Test Seller One /));
  fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
  fireEvent.click(await screen.findByRole('button', { name: 'Show the sales of this day' }));
}

/** The sale page as its route draws it, with its own way back. */
function SalePage() {
  return <SalePageView saleId={SALE} onClose={useCloseSale(SALE)} />;
}

/** Draws the sale page on `router`, presses See all sales, and lets the router read where that went, as RouterProvider does. */
async function back(router: ReturnType<typeof show>) {
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: ALL_BRANCHES }}><SalePage /></StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'See all sales' }));
  await router.load();
  cleanup();
}

describe('a sale opened from All sales', () => {
  it('goes to the sale\'s own address, and Back finds the same groups open and the same search', async () => {
    const router = show();
    fireEvent.change(buyerBox(), { target: { value: 'Test' } });
    await openTheDay();
    const before = await trail();
    const listIndex = router.state.location.state.__TSR_index;
    fireEvent.click(await screen.findByRole('button', { name: /^Open the sale of .* to Test Buyer One$/ }));
    // A press of See all sales before the move settles still goes Back: the sale page's index is known at once.
    expect(openedFromAllSales(SALE, { __TSR_index: listIndex + 1 })).toBe(true);
    expect(openedFromAllSales(SALE, { __TSR_index: listIndex + 2 })).toBe(false);
    await waitFor(() => expect(router.state.location.pathname).toBe(`/staff/retail/sales/${SALE}`));
    expect(router.state.location.search).toEqual({});
    // The sale page's own way back is Back, since the sale was opened from the list.
    await waitFor(() => expect(openedFromAllSales(SALE, router.state.location.state)).toBe(true));

    // The sale page, with its own way back, See all sales, on the same history.
    cleanup();
    await back(router);
    expect(router.state.location.pathname).toBe('/staff/retail/sales');
    show(router);
    expect(await trail()).toEqual(before);
    expect(buyerBox().value).toBe('Test');
  });

  it('keeps nothing past midnight: Back the next business day starts fresh', async () => {
    const router = show();
    fireEvent.change(buyerBox(), { target: { value: 'Test' } });
    await openTheDay();
    fireEvent.click(await screen.findByRole('button', { name: /^Open the sale of / }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/staff/retail/sales/${SALE}`));
    cleanup();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.now() + 24 * 60 * 60 * 1000);
    try {
      await back(router);
      show(router);
      expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
      expect(screen.queryByRole('navigation', { name: 'Where you are' })).toBeNull();
      expect(buyerBox().value).toBe('');
    } finally {
      vi.useRealTimers();
    }
  });

  it('starts fresh when All sales is reached any other way, such as from the menu after the sale', async () => {
    const router = show();
    fireEvent.change(buyerBox(), { target: { value: 'Test' } });
    await openTheDay();
    fireEvent.click(await screen.findByRole('button', { name: /^Open the sale of / }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/staff/retail/sales/${SALE}`));
    cleanup();
    // Home, then All sales from the menu: a new history entry, not the one the list was left on.
    await router.navigate({ to: '/staff' });
    await router.navigate({ to: '/staff/retail/sales' });
    show(router);
    expect(await head(/^Test Branch Gayaza /)).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Where you are' })).toBeNull();
    expect(buyerBox().value).toBe('');
    expect(openedFromAllSales(SALE, router.state.location.state)).toBe(false);
  });
});
