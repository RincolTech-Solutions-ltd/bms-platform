// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { businessToday, type Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #688 (review of #691): the customer of an order or a quotation stays optional. With no customers on file the field
// offers only "+ Add a customer" to whoever may add one, is left out for anyone else, never says "No customers yet", and Save
// never goes to it: the order and the quotation save with the name typed alone. Every name and amount is fabricated.

const createOrder = vi.fn();
const createQuotation = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: { ...real.retail, listCustomers: () => Promise.resolve([]), listProducts: () => Promise.resolve([]), createQuotation: (...a: unknown[]) => createQuotation(...a) },
  };
});
vi.mock('../../../api/retail-orders', async (original) => {
  const real = await original<typeof import('../../../api/retail-orders')>();
  return { ...real, retailOrders: { ...real.retailOrders, create: (...a: unknown[]) => createOrder(...a) } };
});

const { OrderForm } = await import('./orders');
const { QuotationForm } = await import('./quotations');
const { blankOrder } = await import('./orders-state');
const { blankQuotation } = await import('./documents-state');
const { newLine } = await import('./sale-state');

const rice: Product = { id: 'p1', code: 'T-RICE', description: 'Test Rice', unit: 'kg', sell_minor: 4000, cost_minor: 3000, active: true };

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  createOrder.mockReset();
  createQuotation.mockReset();
});

/** A draft with one line already on it, as the form keeps it in this tab (#77), so only the name is left to type. */
function seed(name: string, draft: unknown) {
  sessionStorage.setItem(`retail-draft:${name}`, JSON.stringify({ key: 'k-688', draft }));
}

function draw(me: Me, node: ReactElement) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const seller = (): Me => {
  const me = mockMe('admin');
  return { ...me, permissions: (me.permissions ?? []).filter((p) => p !== 'retail.customer.manage') };
};

describe.each([
  ['an owner who may add customers', () => mockMe('admin'), true],
  ['a seller who may not', seller, false],
])('with no customers on file, for %s', (_who, who, mayAdd) => {
  it('saves an order with the name alone', async () => {
    const me = who();
    const branchId = me.branches?.[0]?.id ?? '';
    createOrder.mockResolvedValue({ id: 'o1', order_no: 'OR000001' });
    seed(`order:${me.user_id ?? ''}:${branchId}`, { ...blankOrder(branchId, businessToday()), lines: [newLine(rice)] });
    draw(me, <OrderForm branchId={branchId} onSaved={() => undefined} />);
    if (mayAdd) {
      const select = (await screen.findByLabelText(/^Customer from the list/)) as HTMLSelectElement;
      expect([...select.options].map((o) => o.text)).toEqual(['Not on the list', '+ Add a customer']);
      expect(select.getAttribute('aria-required')).toBeNull();
    } else {
      await waitFor(() => expect(screen.queryByLabelText(/^Customer from the list/)).toBeNull());
    }
    expect(screen.queryByText(/No customers yet/)).toBeNull();
    expect(document.querySelector('[data-needed]')).toBeNull();
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Test Customer 05' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the order' }));
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));
    expect(createOrder.mock.calls[0]?.[0]).toMatchObject({ customer_name: 'Test Customer 05' });
    expect(createOrder.mock.calls[0]?.[0]).not.toHaveProperty('customer_id');
  });

  it('saves a quotation with the name alone', async () => {
    const me = who();
    const branchId = me.branches?.[0]?.id ?? '';
    createQuotation.mockResolvedValue({ id: 'q1', quotation_no: 'QT000001' });
    seed(`quotation:${me.user_id ?? ''}:${branchId}`, { ...blankQuotation(branchId, businessToday()), lines: [newLine(rice)] });
    draw(me, <QuotationForm branchId={branchId} onSaved={() => undefined} />);
    if (mayAdd) {
      const select = (await screen.findByLabelText(/^Customer from the list/)) as HTMLSelectElement;
      expect([...select.options].map((o) => o.text)).toEqual(['Not on the list: type their name', '+ Add a customer']);
    } else {
      await waitFor(() => expect(screen.queryByLabelText(/^Customer from the list/)).toBeNull());
    }
    expect(screen.queryByText(/No customers yet/)).toBeNull();
    expect(document.querySelector('[data-needed]')).toBeNull();
    fireEvent.change(screen.getByLabelText(/^Buyer name/), { target: { value: 'Test Customer 06' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save quotation' }));
    await waitFor(() => expect(createQuotation).toHaveBeenCalledTimes(1));
    expect(createQuotation.mock.calls[0]?.[0]).not.toHaveProperty('customer_id');
  });
});
