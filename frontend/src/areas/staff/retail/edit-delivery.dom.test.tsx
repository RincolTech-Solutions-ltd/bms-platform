// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { businessToday, retail, type Purchase } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Edit a delivery as in the owner's old app (issue #830): Past purchases offers Edit to whoever may correct the purchase; it opens
// Buy stock filled in with that delivery; Save changes voids the old one through the correct-this-purchase path and saves the new
// one naming it, with the toast "Delivery updated". The old one is kept, marked Voided. The retail adapter is the fabricated mock.

vi.setConfig({ testTimeout: 20000 });

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useNavigate: () => navigate, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const { RestockCard } = await import('./restocks');
const { Again } = await import('./restock');
const { DoneToast, doneToasts } = await import('./toast');
const { EDIT_REASON, editNote } = await import('./restock-correct-state');

const admin = mockMe('admin');
const seller = mockMe('sales');
const branch = admin.branches?.[0]?.id ?? '';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});

afterEach(() => {
  cleanup();
  navigate.mockReset();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

function screenOf(me: ReturnType<typeof mockMe>, node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } }, mutationCache: doneToasts() })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
      <DoneToast />
    </QueryClientProvider>,
  );
}

const toastText = () => document.querySelector('.toast-success')?.textContent ?? null;

/** A fabricated delivery of 4 of the first mock item at the first branch, saved through the mock. */
async function delivery(): Promise<Purchase> {
  const [item] = await retail.listProducts({});
  return retail.createPurchase({ purchased_on: businessToday(), payment_method: 'cash', lines: [{ product_id: item?.id ?? '', cost_minor: 1000, qty_by_branch: [{ branch_id: branch, qty: '4' }] }] }, 'k-edit-delivery');
}

describe('Edit a delivery (issue #830)', () => {
  it('offers Edit beside Correct this purchase, and opens Buy stock with that delivery', async () => {
    const purchase = await delivery();
    screenOf(admin, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    fireEvent.click(screen.getByRole('button', { name: `Edit delivery ${purchase.purchase_no ?? ''}` }));
    expect(navigate).toHaveBeenCalledWith({ to: '/staff/retail/restock', search: { edit: purchase.id } });
    expect(screen.getByRole('button', { name: `Correct purchase ${purchase.purchase_no ?? ''}` })).toBeTruthy();
  });

  it('offers no Edit to someone who may not correct the purchase, nor on a voided one', async () => {
    const purchase = await delivery();
    const noBuy = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.purchase.create'), permission_scopes: {} };
    screenOf(noBuy, <ul><RestockCard purchase={purchase} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /^Edit delivery/ })).toBeNull();
    cleanup();
    screenOf(admin, <ul><RestockCard purchase={{ ...purchase, status: 'voided' }} onDone={() => undefined} /></ul>);
    expect(screen.queryByRole('button', { name: /^Edit delivery/ })).toBeNull();
  });

  it('fills the form in, and Save changes voids the old delivery and saves the new one naming it, with one toast', async () => {
    const purchase = await delivery();
    const voidSpy = vi.spyOn(retail, 'voidPurchase');
    const createSpy = vi.spyOn(retail, 'createPurchase');
    const saved = vi.fn();
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onSaved={saved} />);
    expect(await screen.findByText(new RegExp(`You are editing delivery ${purchase.purchase_no ?? ''}`))).toBeTruthy();
    const qty = (await screen.findByDisplayValue('4')) as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(voidSpy).toHaveBeenCalledWith(purchase.id, EDIT_REASON, expect.any(String));
    expect(createSpy.mock.calls[0]?.[0]).toMatchObject({ note: editNote(purchase), lines: [expect.objectContaining({ qty_by_branch: [{ branch_id: branch, qty: '3.000' }] })] });
    expect(voidSpy.mock.invocationCallOrder[0]).toBeLessThan(createSpy.mock.invocationCallOrder[0] ?? 0);
    await waitFor(() => expect(toastText()).toBe('Delivery updated'));
    // The old delivery is kept, marked Voided, its reason saying it was edited; the new one says which it replaces.
    const old = await retail.getPurchase(purchase.id ?? '');
    expect(old.status).toBe('voided');
    expect(old.void_reason).toBe(EDIT_REASON);
    expect(editNote(purchase)).toBe(`Replaces ${purchase.purchase_no ?? ''}, edited`);
  });

  it('saves nothing new when the old delivery cannot be voided (its stock moved), and says why', async () => {
    const purchase = await delivery();
    const createSpy = vi.spyOn(retail, 'createPurchase');
    // The stock this delivery added has gone: the void is refused, so nothing is entered twice.
    vi.spyOn(retail, 'voidPurchase').mockRejectedValue(Object.assign(new Error('Only 1 are in stock there now. It cannot be voided.'), { code: 'purchase_stock_moved' }));
    screenOf(admin, <Again purchaseId={purchase.id ?? ''} edit onSaved={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    expect((await screen.findByRole('alert')).textContent).toContain('cannot be voided');
    expect(createSpy).not.toHaveBeenCalled();
  });
});
