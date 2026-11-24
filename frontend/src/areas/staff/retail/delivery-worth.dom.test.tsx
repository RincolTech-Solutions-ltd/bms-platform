// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { businessToday, type Product, type Purchase } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// What a delivery is worth (issue #821, ADR-126), as the owner's old delivery form showed it: under the lines of Buy stock the
// total cost, what the goods should sell for and the expected profit, live as the lines change, and the same three on each row of
// Past purchases. Only a person with retail.profit.read sees them; a buyer without it sees Buy stock as before. Every price and
// quantity is fabricated.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => vi.fn(), Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

/** The two reads of Past purchases and of a purchase alone, counted so a test sees them read again (issue #852 item 7). */
const reads = vi.hoisted(() => ({
  list: vi.fn(() => Promise.resolve({ items: [], next_cursor: '' })),
  one: vi.fn((id: string) => Promise.resolve({ id, purchase_no: 'RP-TEST-1', status: 'posted', lines: [], total_minor: 0, currency: 'UGX' })),
  /** Issue #967: the one-product read, with the stock per branch the person may read (fabricated). */
  product: vi.fn((id: string): Promise<Record<string, unknown>> => Promise.resolve({ id, stock_by_branch: [] })),
}));

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listPurchases: reads.list,
      getPurchase: reads.one,
      getProduct: reads.product,
      listProducts: () => Promise.resolve([]),
      listSuppliers: () => Promise.resolve([]),
      priceHistory: () => Promise.resolve([]),
      editPrices: (id: string, _version: number, body: { sell_minor?: number }) => Promise.resolve({ id, description: 'Test soap', sell_minor: body.sell_minor }),
    },
  };
});

const { RestockForm, restockWorth, branchStockAfter, profitPercent, deliveredInto } = await import('./restock');
const { branchLabel } = await import('../../../auth/branch');
const { PriceForm } = await import('./catalogue-products');
const { RestockCard, PurchasePage, Route: RestocksRoute } = await import('./restocks');
const { PROFIT } = await import('./permissions');

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

/** The owner, with retail.profit.read and the units capability off. */
const owner = { ...mockMe('admin'), module_capabilities: { retail: [] } };
/** A buyer who may buy stock and change prices but not read profit. */
const buyer = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.profit.read') };
const branch = owner.branches?.[0]?.id ?? '';
const rice: Product = { id: 'p1', code: 'T-RICE', description: 'Test rice', unit: 'bag', cost_minor: 1000, sell_minor: 1500, active: true };
const soap: Product = { id: 'p2', code: 'T-SOAP', description: 'Test soap', unit: 'bar', cost_minor: 300, sell_minor: 500, active: true };

function screenOf(me: typeof owner, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const draft = () => ({
  supplierId: '', purchasedOn: businessToday(), method: 'cash' as const,
  lines: [
    { product: rice, cost: '1000', sell: '1600', qtyByBranch: { [branch]: '5' } },
    { product: soap, cost: '300', sell: '', qtyByBranch: { [branch]: '10' } },
  ],
});

/** The three figures as their words and amounts, in order. */
function figures(within_: HTMLElement): string[] {
  return [...within_.querySelectorAll('[data-testid="delivery-worth"] > div')].map((row) => `${row.querySelector('dt')?.textContent}: ${row.querySelector('dd')?.textContent}`);
}

describe('the worth of a delivery on the form', () => {
  it('uses the selling price on the line, else the item\'s current price', () => {
    const lines = draft().lines;
    // 5 x 1,600 on the line, and 10 x 500 from the item.
    expect(restockWorth(lines)).toBe(13_000);
    expect(restockWorth([{ ...lines[1]!, sell: '450' }])).toBe(4_500);
    expect(restockWorth([])).toBe(0);
  });

  it('is not known while an item has no selling price yet', () => {
    const lines = draft().lines;
    expect(restockWorth([lines[0]!, { ...lines[1]!, product: { ...soap, sell_minor: 0 } }])).toBeNull();
    expect(restockWorth([{ ...lines[0]!, sell: '0' }])).toBeNull();
  });

  it('counts a line bought in packs in the item\'s own unit, at the price per unit', () => {
    const packed: Product = { ...soap, packs: [{ unit_id: 'u-box', unit: 'box', factor: '12', active: true, purpose: 'both' }] } as Product;
    // 2 boxes of 12 bars at 500 a bar.
    expect(restockWorth([{ product: packed, cost: '3000', sell: '500', unitId: 'u-box', qtyByBranch: { [branch]: '2' } }])).toBe(12_000);
  });
});

describe('Buy stock (issue #821)', () => {
  it('shows the owner the total cost, what it should sell for and the expected profit, in tabular money', () => {
    screenOf(owner, <RestockForm initial={draft()} />);
    const total = screen.getByTestId('restock-total');
    // Cost 5 x 1,000 + 10 x 300 = 8,000; worth 13,000.
    // Issue #967: and the profit as a share of the cost, 5,000 of 8,000.
    expect(figures(total)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 13,000', 'Expected profit: UGX 5,000', 'Expected profit on cost: 62.5%']);
    expect(total.querySelectorAll('dd .num')).toHaveLength(4);
    expect(within(total).getByLabelText('This delivery')).toBeTruthy();
  });

  it('updates the figures as the lines change', () => {
    screenOf(owner, <RestockForm initial={draft()} />);
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: '6' } });
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 9,000', 'Should sell for: UGX 14,600', 'Expected profit: UGX 5,600', 'Expected profit on cost: 62.2%']);
    fireEvent.change(document.getElementById('sell-1') as HTMLInputElement, { target: { value: '200' } });
    // The soap now sells below its cost: 6 x 1,600 + 10 x 200 = 11,600 against 9,000.
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 9,000', 'Should sell for: UGX 11,600', 'Expected profit: UGX 2,600', 'Expected profit on cost: 28.9%']);
    fireEvent.change(document.getElementById('cost-0') as HTMLInputElement, { target: { value: '2000' } });
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 15,000', 'Should sell for: UGX 11,600', 'Expected loss: UGX 3,400', 'Expected loss on cost: 22.7%']);
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test rice' }));
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 3,000', 'Should sell for: UGX 2,000', 'Expected loss: UGX 1,000', 'Expected loss on cost: 33.3%']);
  });

  it('says so instead of the two figures while an item has no selling price yet', () => {
    const lines = draft().lines;
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [lines[0]!, { ...lines[1]!, product: { ...soap, sell_minor: 0 } }] }} />);
    const total = screen.getByTestId('restock-total');
    expect(figures(total)).toEqual(['Total cost: UGX 8,000']);
    expect(within(total).getByTestId('unpriced-note').textContent).toBe('Some items have no selling price yet.');
    // A price typed for the soap brings the figures back: 5 x 1,600 + 10 x 450.
    fireEvent.change(document.getElementById('sell-1') as HTMLInputElement, { target: { value: '450' } });
    expect(figures(total)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 12,500', 'Expected profit: UGX 4,500', 'Expected profit on cost: 56.3%']);
    expect(screen.queryByTestId('unpriced-note')).toBeNull();
  });

  it('shows the total cost alone, with no zeros to sell for or earn, while the form has no line (issue #852)', () => {
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [] }} />);
    const total = screen.getByTestId('restock-total');
    expect(figures(total)).toEqual(['Total cost: UGX 0']);
    expect(screen.queryByText(/Should sell for|Expected profit|Expected loss/)).toBeNull();
    expect(screen.queryByTestId('unpriced-note')).toBeNull();
  });

  it('hides the two figures again when the last line is removed (issue #852)', () => {
    const lines = draft().lines;
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [lines[1]!] }} />);
    expect(figures(screen.getByTestId('restock-total'))).toHaveLength(4);
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test soap' }));
    expect(figures(screen.getByTestId('restock-total'))).toEqual(['Total cost: UGX 0']);
    expect(screen.queryByText(/Should sell for|Expected profit|Expected loss/)).toBeNull();
  });

  it('works nothing out for someone who reads profit only at a branch the delivery does not go to', async () => {
    const other = owner.branches?.find((b) => b.id !== branch)?.id ?? 'b-other';
    const elsewhere = { ...owner, permission_scopes: { ...(owner.permission_scopes ?? {}), [PROFIT]: { all_branches: false, branch_ids: [other] } } };
    screenOf(elsewhere, <RestockForm initial={draft()} />);
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(screen.getByTestId('restock-total').textContent).toBe('Total cost UGX 8,000');
    // Issue #967: nor the amount per branch, which follows the same gate (review of #968).
    await waitFor(() => expect(screen.getByTestId(`branch-stock-0-${branch}`).textContent).toBe('In stock now 0 · after this 5 bags'));
    expect(screen.queryByText(/Amount/)).toBeNull();
  });

  it('shows a buyer without the profit permission the total cost only, as before', () => {
    screenOf(buyer, <RestockForm initial={draft()} />);
    const total = screen.getByTestId('restock-total');
    expect(total.tagName).toBe('P');
    expect(total.textContent).toBe('Total cost UGX 8,000');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(screen.queryByText(/Should sell for|Expected profit|Expected loss/)).toBeNull();
  });
});

// JUSTIFICATION-A3: issue #967 asks for its tests in the existing Buy stock dom tests, beside the figures of #821 it extends.
describe('Buy stock, the stock at each branch (issue #967)', () => {
  const other = owner.branches?.find((b) => b.id !== branch);
  const hereLabel = branchLabel(owner.branches?.find((b) => b.id === branch));
  /** The rice holds 12 bags here and 3 at the other branch. */
  const stocked = () => reads.product.mockImplementation((id: string) => Promise.resolve({ id, stock_by_branch: [{ branch_id: branch, qty: '12.000' }, { branch_id: other?.id, qty: '3.000' }] }));
  const hint = (i: number, b: string) => screen.queryByTestId(`branch-stock-${i}-${b}`)?.textContent;

  it('says under each quantity the stock there now and after this purchase, and the amount for that branch, live', async () => {
    stocked();
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} />);
    await waitFor(() => expect(hint(0, branch)).toBe('In stock now 12 · after this 17 bags · Amount UGX 5,000'));
    // Nothing typed at the other branch yet: its stock now, the same after, and no amount.
    expect(hint(0, other?.id ?? '')).toBe('In stock now 3 · after this 3 bags');
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: '8' } });
    expect(hint(0, branch)).toBe('In stock now 12 · after this 20 bags · Amount UGX 8,000');
    // A quantity that cannot be read says the stock now alone.
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: 'abc' } });
    expect(hint(0, branch)).toBe('In stock now 12 bags');
    // The numbers are tabular.
    expect(screen.getByTestId(`branch-stock-0-${branch}`).querySelectorAll('.num')).toHaveLength(1);
  });

  it('moves a branch quantity with one less and one more', async () => {
    stocked();
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} />);
    await waitFor(() => expect(hint(0, branch)).toContain('after this 17'));
    fireEvent.click(screen.getByRole('button', { name: `One more Test rice, ${hereLabel}` }));
    expect((document.getElementById(`q-0-${branch}`) as HTMLInputElement).value).toBe('6');
    expect(hint(0, branch)).toBe('In stock now 12 · after this 18 bags · Amount UGX 6,000');
    fireEvent.click(screen.getByRole('button', { name: `One less Test rice, ${hereLabel}` }));
    fireEvent.click(screen.getByRole('button', { name: `One less Test rice, ${hereLabel}` }));
    expect((document.getElementById(`q-0-${branch}`) as HTMLInputElement).value).toBe('4');
    expect(figures(screen.getByTestId('restock-total'))[0]).toBe('Total cost: UGX 4,000');
  });

  it('shows a buyer without the profit permission the stock but no amount and no profit share', async () => {
    stocked();
    screenOf(buyer, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} />);
    await waitFor(() => expect(hint(0, branch)).toBe('In stock now 12 · after this 17 bags'));
    expect(screen.queryByText(/Amount|on cost/)).toBeNull();
  });

  it('says no stock for a branch whose stock the person may not read', async () => {
    stocked();
    const one = { ...owner, permission_scopes: { ...(owner.permission_scopes ?? {}), 'retail.stock.read': { all_branches: false, branch_ids: [branch] } } };
    screenOf(one, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} />);
    await waitFor(() => expect(hint(0, branch)).toContain('In stock now 12'));
    // The other branch's amount is still said to the owner, who reads profit there; its stock is not.
    expect(hint(0, other?.id ?? '') ?? '').not.toContain('In stock');
  });

  it('counts a line bought in packs in the item\'s own unit', () => {
    const packed = { ...soap, packs: [{ unit_id: 'u-box', unit: 'box', factor: '12', active: true, purpose: 'both' }] } as Product;
    const line = { product: packed, cost: '3000', sell: '500', unitId: 'u-box', qtyByBranch: { [branch]: '2' } };
    // 2 boxes of 12 bars onto 5 bars.
    expect(branchStockAfter(line, { stock_by_branch: [{ branch_id: branch, qty: '5.000' }] }, branch)).toEqual({ now: 5_000, after: 29_000 });
    expect(branchStockAfter(line, { stock_by_branch: [] }, branch)).toEqual({ now: 0, after: 24_000 });
    expect(branchStockAfter(line, {}, branch)).toBeNull();
  });

  it('says the profit share always to one decimal, a loss and a profit of one size alike, and nothing without a cost', () => {
    expect(profitPercent(8_000, 13_000)).toBe('62.5%');
    expect(profitPercent(3_000, 2_000)).toBe('33.3%');
    expect(profitPercent(4_000, 5_000)).toBe('25.0%');
    // A half of a tenth rounds up in size either way (review of #968).
    expect(profitPercent(2_000, 2_001)).toBe('0.1%');
    expect(profitPercent(2_000, 1_999)).toBe('0.1%');
    expect(profitPercent(0, 2_000)).toBeNull();
  });

  it('says one of the unit when one is left', async () => {
    reads.product.mockImplementation((id: string) => Promise.resolve({ id, stock_by_branch: [] }));
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [{ ...draft().lines[0]!, qtyByBranch: { [branch]: '1' } }] }} />);
    await waitFor(() => expect(hint(0, branch)).toBe('In stock now 0 · after this 1 bag · Amount UGX 1,000'));
  });

  // Review of #968, must-fix 1: the delivery being edited is in the stock now already, and goes back out when the edit is saved.
  it('does not count the delivery being edited twice', async () => {
    stocked();
    const posted: Purchase = {
      id: 'pu-edit', purchase_no: 'RP00000967', status: 'posted', currency: 'UGX', total_minor: 5_000,
      lines: [{ id: 'l1', product_id: 'p1', description: 'Test rice', qty_total: '5.000', qty_by_branch: [{ branch_id: branch, qty: '5.000' }] }],
    };
    expect(deliveredInto(posted, 'p1', branch)).toBe(5_000);
    expect(deliveredInto(posted, 'p2', branch)).toBe(0);
    expect(deliveredInto({ ...posted, status: 'voided' }, 'p1', branch)).toBe(0);
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} draftName="restock-edit-test" again replaces={posted} />);
    // 12 now holds the 5 of this delivery: saved unchanged it stays 12, and 7 instead of 5 makes it 14.
    await waitFor(() => expect(hint(0, branch)).toBe('In stock now 12 · after this 12 bags · Amount UGX 5,000'));
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: '7' } });
    expect(hint(0, branch)).toBe('In stock now 12 · after this 14 bags · Amount UGX 7,000');
    expect(hint(0, other?.id ?? '')).toBe('In stock now 3 · after this 3 bags');
  });

  it('does not mark a quantity wrong while it is being typed', async () => {
    stocked();
    screenOf(owner, <RestockForm initial={{ ...draft(), lines: [draft().lines[0]!] }} />);
    fireEvent.change(document.getElementById(`q-0-${branch}`) as HTMLInputElement, { target: { value: '1.' } });
    expect(document.getElementById(`q-0-${branch}`)?.getAttribute('aria-invalid')).toBe('false');
  });
});

describe('Past purchases (issue #821)', () => {
  const purchase: Purchase = {
    id: 'pu1', purchase_no: 'RP00000001', purchased_on: '2026-10-30', payment_method: 'cash', currency: 'UGX', total_minor: 8_000, status: 'posted',
    lines: [{ id: 'l1', description: 'Test rice', qty_total: '5.000', qty_by_branch: [{ branch_id: branch, qty: '5.000' }], base_unit: 'bag' }],
    sell_worth_minor: 13_000, expected_profit_minor: 5_000,
  };

  it('shows the owner the three figures the server sent on each row', () => {
    screenOf(owner, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(figures(card)).toEqual(['Total cost: UGX 8,000', 'Should sell for: UGX 13,000', 'Expected profit: UGX 5,000']);
    expect(card.querySelectorAll('dd .num')).toHaveLength(3);
    expect(within(card).getByLabelText('Purchase RP00000001')).toBeTruthy();
    // The total is said once, in the figures.
    expect(card.textContent).not.toContain('Total UGX');
  });

  it('shows a voided delivery with its total alone, since it will never sell', () => {
    screenOf(owner, <ul><RestockCard purchase={{ ...purchase, status: 'voided', voided_on: '2026-10-31', void_reason: 'Test wrong delivery' }} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(card.textContent).toContain('Total UGX 8,000.');
  });

  it('says an item has no selling price yet when the server says so', () => {
    const { sell_worth_minor: _w, expected_profit_minor: _p, ...unknown } = purchase;
    screenOf(owner, <ul><RestockCard purchase={{ ...unknown, has_unpriced_items: true }} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(figures(card)).toEqual(['Total cost: UGX 8,000']);
    expect(within(card).getByTestId('unpriced-note').textContent).toBe('Some items have no selling price yet.');
  });

  it('says a loss as one', () => {
    screenOf(owner, <ul><RestockCard purchase={{ ...purchase, sell_worth_minor: 7_500, expected_profit_minor: -500 }} onDone={() => undefined} /></ul>);
    expect(figures(screen.getByTestId('restock-card'))).toContain('Expected loss: UGX 500');
  });

  it('shows a buyer without the profit permission the total as before and works nothing out', () => {
    const { sell_worth_minor: _w, expected_profit_minor: _p, ...asSent } = purchase;
    screenOf(buyer, <ul><RestockCard purchase={asSent} onDone={() => undefined} /></ul>);
    const card = screen.getByTestId('restock-card');
    expect(card.textContent).toContain('Total UGX 8,000.');
    expect(screen.queryByTestId('delivery-worth')).toBeNull();
    expect(card.textContent).not.toMatch(/Should sell for|Expected profit|Expected loss/);
  });
});

// JUSTIFICATION-A3: issue #852 item 3 asks for this one assertion, which needs the price form rendered with a query cache.
describe('a price edit (issue #852)', () => {
  it('refreshes Past purchases and a delivery read alone, whose worth follows the item\'s current price', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const list = ['retail', 'restocks', '2026-11-01', '2026-11-04', 'all'];
    const one = ['retail', 'restocks', 'one', 'purchase-1'];
    client.setQueryData(list, { items: [] });
    client.setQueryData(one, { id: 'purchase-1' });
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: owner, branch }}>
          <PriceForm product={{ ...soap, version: 1 }} onSaved={() => undefined} onCancel={() => undefined} />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(client.getQueryState(list)?.isInvalidated).toBe(false);
    fireEvent.change(document.getElementById('price-sell') as HTMLInputElement, { target: { value: '600' } });
    fireEvent.change(document.getElementById('price-reason') as HTMLInputElement, { target: { value: 'Test new price' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the new price' }));
    await waitFor(() => expect(client.getQueryState(list)?.isInvalidated).toBe(true));
    expect(client.getQueryState(one)?.isInvalidated).toBe(true);
  });

  // JUSTIFICATION-A3: review item 7 of PR #853 asks to prove the real screens, which needs them rendered beside the form.
  it('reads the real Past purchases and a purchase alone again, so the keys above are theirs', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const RestocksPage = RestocksRoute.options.component as () => ReactElement;
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: owner, branch }}>
          <RestocksPage />
          <PurchasePage purchaseId="purchase-1" />
          <PriceForm product={{ ...soap, version: 1 }} onSaved={() => undefined} onCancel={() => undefined} />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(reads.one).toHaveBeenCalledWith('purchase-1'));
    await waitFor(() => expect(reads.list).toHaveBeenCalled());
    const before = { list: reads.list.mock.calls.length, one: reads.one.mock.calls.length };
    fireEvent.change(document.getElementById('price-sell') as HTMLInputElement, { target: { value: '600' } });
    fireEvent.change(document.getElementById('price-reason') as HTMLInputElement, { target: { value: 'Test new price' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the new price' }));
    await waitFor(() => expect(reads.one.mock.calls.length).toBeGreaterThan(before.one));
    await waitFor(() => expect(reads.list.mock.calls.length).toBeGreaterThan(before.list));
  });
});
