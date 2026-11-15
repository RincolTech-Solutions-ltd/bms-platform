// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, SalesSummary, SalesSummaryGroup, SalesSummaryQuery } from '../../../api/retail';

// The sale page's See all sales through the real route tree and staff layout (issue #933, review of 7b0dd8f). It goes Back only
// from the very entry All sales opened, with the list just before it, so it always lands on All sales: after a visit to an item
// page and Back to the sale, and when the same sale is reached again another way. The retail mock serves the session; the sales
// answers are fixed here. Fabricated names and amounts.

configure({ asyncUtilTimeout: 10_000 });
vi.setConfig({ testTimeout: 40_000 });
vi.hoisted(() => vi.stubEnv('VITE_RETAIL_MOCK', '1'));

const SALE = '5eed0000-0000-4000-8000-000000000934';
const ITEM = '00000000-0000-4000-8000-0000000b0934';
/** A fixture checked against the API type `T` (an object literal passed here gets the excess property check). */
const as = <T,>(value: T): T => value;
const sale = as<Sale>({
  id: SALE, sale_no: 'S-900934', sale_date: '2026-10-19', payment_method: 'cash', status: 'completed', buyer_name: 'Test Buyer Two',
  total_minor: 3000, paid_minor: 3000, balance_minor: 0, historical: false,
});
const group = (key: string, label: string, over: Partial<SalesSummaryGroup> = {}) =>
  as<SalesSummaryGroup>({ key, label, sales_count: 1, total_minor: 3000, voided_count: 0, ...over });
const LEVELS: Record<string, SalesSummaryGroup[]> = {
  year: [group('2026', '2026', { period: '2026', from: '2026-01-01', to: '2026-12-31' })],
  month: [group('2026-10', '2026-10', { period: '2026-10', from: '2026-10-01', to: '2026-10-31' })],
  day: [group('2026-10-19', '2026-10-19', { period: '2026-10-19', from: '2026-10-19', to: '2026-10-19' })],
};

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: new Proxy(real.retail, {
      get(target, name, receiver) {
        if (name === 'salesSummary') {
          return async (q: SalesSummaryQuery) => {
            const chain = q.chain ?? [q.level];
            const next = chain[chain.indexOf(q.level) + 1];
            return as<SalesSummary>({ group_by: q.level, levels: chain, ...(next ? { next_level: next } : {}), currency: 'UGX', has_more: false, items: LEVELS[q.level] ?? [] });
          };
        }
        if (name === 'listSales') return async () => ({ items: [sale] });
        if (name === 'getSale') return async () => sale;
        return Reflect.get(target, name, receiver) as unknown;
      },
    }),
  };
});

const { router: appRouter } = await import('../../../app/router');
const { writeChain } = await import('./sales-chain-state');
/** The retail mock's session user (`mockMe` in api/retail-mock.ts), named here: importing the mock would race its lazy load. */
const MOCK_USER = '00000000-0000-4000-8000-0000000000f1';

function app(path = '/staff/retail/sales') {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const head = (name: RegExp) => screen.findByRole('button', { name });
const trail = async () => within(await screen.findByRole('navigation', { name: 'Where you are' })).getAllByRole('listitem').map((li) => li.textContent);
const at = (router: ReturnType<typeof app>, path: string) => waitFor(() => expect(router.state.location.pathname).toBe(path));

/** Opens the day on All sales and the sale in it, and waits on the sale page. */
async function openFromTheList(router: ReturnType<typeof app>) {
  fireEvent.click(await head(/^2026 /));
  fireEvent.click(await head(/^October 2026 /));
  fireEvent.click(await head(/^[A-Z][a-z]+ 19 October 2026 /));
  const more = screen.queryByRole('button', { name: 'Show the sales of this day' });
  if (more) fireEvent.click(more);
  const before = await trail();
  fireEvent.click(await screen.findByRole('button', { name: /^Open the sale of .* to Test Buyer Two$/ }));
  await at(router, `/staff/retail/sales/${SALE}`);
  await screen.findByRole('button', { name: 'See all sales' });
  return before;
}

beforeEach(() => {
  localStorage.clear();
  writeChain(MOCK_USER, 'all', ['year', 'month', 'day']);
});
afterEach(cleanup);

describe('See all sales on the sale page, through the real routes', () => {
  it('after an item page and Back to the sale, goes Back to All sales as it was left', async () => {
    const router = app();
    const before = await openFromTheList(router);
    await router.navigate({ to: '/staff/retail/items/$productId', params: { productId: ITEM } });
    await at(router, `/staff/retail/items/${ITEM}`);
    router.history.back();
    await at(router, `/staff/retail/sales/${SALE}`);
    fireEvent.click(await screen.findByRole('button', { name: 'See all sales' }));
    await at(router, '/staff/retail/sales');
    expect(await trail()).toEqual(before);
  });

  it('when the same sale is reached again another way, opens All sales, never the page before it', async () => {
    const router = app();
    const before = await openFromTheList(router);
    // Home from the menu, then the same sale from somewhere else (a credit note's sale number): a new entry.
    await router.navigate({ to: '/staff' });
    await at(router, '/staff');
    await router.navigate({ to: '/staff/retail/sales/$saleId', params: { saleId: SALE } });
    await at(router, `/staff/retail/sales/${SALE}`);
    fireEvent.click(await screen.findByRole('button', { name: 'See all sales' }));
    await at(router, '/staff/retail/sales');
    // Opened anew, it takes what the list kept, since that is still today's.
    expect(await trail()).toEqual(before);
  });
});
