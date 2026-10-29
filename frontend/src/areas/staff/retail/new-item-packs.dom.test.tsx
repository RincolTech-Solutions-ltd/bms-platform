// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Product, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

// Issue #689 (ADR-106): Add an item asks for the smallest unit you sell, never a package, and takes its packages in the same
// form, sent with the item in one request. Every item, unit and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
let items: Product[] = [];
const lighting = { id: 'c1', name: 'Test lighting', active: true, product_count: 0, version: 1, code_prefix: 'TLG', next_code: 'TLG-0001' };
const units: Unit[] = [
  { id: 'u1', name: 'Bulb', active: true, product_count: 0, version: 1, is_package: false },
  { id: 'u2', name: 'Carton', active: true, product_count: 0, version: 1, is_package: true },
  { id: 'u3', name: 'Box', active: true, product_count: 0, version: 1, is_package: true, usual_pack: { size: '100.000', of_unit_id: 'u1', of_unit: 'Bulb', purpose: 'buy' } },
  { id: 'u4', name: 'Piece', active: true, product_count: 0, version: 1, is_package: false },
];
const made: Product = { id: 'p1', code: 'TLG-0001', description: 'Test bulb 60W', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Bulb', sell_minor: 5000, active: true, version: 1 };

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCatalogue: () => Promise.resolve({ items }),
      listCategories: () => Promise.resolve([lighting]),
      listUnits: () => Promise.resolve(units),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      nameHints: () => Promise.resolve({}),
      createProduct: (...a: unknown[]) => createProduct(...a),
    },
  };
});

const { ProductsPage } = await import('./catalogue-products');
const { withPacksOn } = await import('./catalogue-packs');
const { PACKAGES_QUESTION, forSmallest, manyOf, oneOf, packageAsUnit, sizeProblem, withPackages, newPackageRow } = await import('./new-item-packs');

const owner = withPacksOn(mockMe('admin'));

afterEach(() => {
  cleanup();
  items = [];
  createProduct.mockReset();
});

function open(me: Me = owner) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '', chooseBranch: () => undefined }}><ProductsPage /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function fill(unitId: string) {
  fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
  await chooseCategory(screen.getByLabelText(/^Category/), 'Test lighting');
  fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test bulb 60W' } });
  await screen.findByRole('option', { name: 'Bulb' });
  fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: unitId } });
  fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '5000' } });
}

describe('the words of Add an item', () => {
  it('names a package as the smallest unit in the words the server uses', () => {
    expect(packageAsUnit(units[2])).toBe('A box is a package. Choose the smallest unit, like piece, then add the box below.');
    expect(packageAsUnit({ ...units[1]!, name: 'Cartons' })).toBe('Cartons is a package. Choose the smallest unit, like piece, then add the cartons below.');
    // Every short code reads as its word, the same as the server's sentence (round 4 review of #692).
    for (const [code, word] of [['Ctns', 'carton'], ['pkts', 'packet'], ['Bx', 'box'], ['DOZ', 'dozen'], ['dz', 'dozen']]) {
      expect(packageAsUnit({ ...units[1]!, name: code, is_package: true })).toMatch(new RegExp(`^A ${word} is a package\.`));
    }
    expect(packageAsUnit({ ...units[2]!, name: 'Boxes' })).toMatch(/^Boxes is a package\./);
    expect(packageAsUnit(units[0])).toBeNull();
    expect(packageAsUnit(undefined)).toBeNull();
    // A short code reads in the shop's words, as the rest of the form names it (review of #692).
    expect(packageAsUnit({ name: 'Ctn', is_package: true })).toBe('A carton is a package. Choose the smallest unit, like piece, then add the carton below.');
    // The sentences RetailNewItemPacksIT checks on the server, from one file, so the screen and the API agree.
    const contract = JSON.parse(readFileSync('../fixtures/retail/package-refusals-689.json', 'utf8')) as { cases: { unit: string; sentence: string }[] };
    for (const c of contract.cases) expect(packageAsUnit({ name: c.unit, is_package: true }), c.unit).toBe(c.sentence);
  });

  it('refills a size the app filled when the smallest unit changes, and keeps one the owner typed (review of #692)', () => {
    const filled = { ...newPackageRow(1), unitId: 'u3', factor: '100', purpose: 'buy' as const, filled: { factor: '100', purpose: 'buy' as const } };
    const typed = { ...newPackageRow(2), unitId: 'u2', factor: '12' };
    const [box, carton] = forSmallest([filled, typed], units, 'u4');
    // The box's usual pack counts bulbs, not the new unit, so the 100 goes.
    expect(box?.factor).toBe('');
    expect(carton?.factor).toBe('12');
    // Back to bulbs, the usual size comes again; a package in the new smallest unit is dropped.
    expect(forSmallest([box!], units, 'u1')[0]?.factor).toBe('100');
    expect(forSmallest([typed], units, 'u2')[0]?.unitId).toBe('');
  });

  it('takes a whole number of 2 or more for a package', () => {
    for (const ok of ['2', '10', ' 100 ']) expect(sizeProblem(ok)).toBeNull();
    for (const bad of ['', '1', '0', '2.5', '-4', 'ten']) expect(sizeProblem(bad)).toBe('Type a whole number, 2 or more.');
    expect(sizeProblem('100000')).toBeNull();
    // Unit names read in the shop's words (#680): a short code is its full word, one or many.
    expect([oneOf({ name: 'Pc' }, 'item'), manyOf({ name: 'Pc' }, 'items'), oneOf({ name: 'Ctn' }, 'package')]).toEqual(['piece', 'pieces', 'carton']);
    expect([oneOf(undefined, 'item'), manyOf({ name: 'Bulb' }, 'items')]).toEqual(['item', 'bulbs']);
    for (const big of ['100001', '1000000000000']) expect(sizeProblem(big)).toBe('Type a number up to 100,000.');
  });

  it('says the packages after the item in the toast', () => {
    const carton = { ...newPackageRow(1), unitId: 'u2', factor: '10' };
    const box = { ...newPackageRow(2), unitId: 'u3', factor: '100' };
    expect(withPackages([], units)).toBe('');
    expect(withPackages([carton], units)).toBe(', with carton of 10');
    expect(withPackages([carton, box], units)).toBe(', with carton of 10 and box of 100');
  });
});

describe('Add an item (issue #689)', () => {
  it('calls the unit Smallest unit you sell, with its hint, and names the prices by it', async () => {
    open();
    await fill('u1');
    const unit = screen.getByLabelText(/^Smallest unit you sell/);
    expect(unit.getAttribute('aria-required')).toBe('true');
    expect(screen.getByText('The single thing a customer can buy. For example piece, bulb, metre, litre.')).toBeTruthy();
    expect(screen.getByLabelText(/^Buying price of one bulb/)).toBeTruthy();
    expect(screen.getByLabelText(/^Selling price of one bulb/)).toBeTruthy();
  });

  it('refuses Box as the smallest unit with a field message and sends nothing', async () => {
    open();
    await fill('u3');
    const said = 'A box is a package. Choose the smallest unit, like piece, then add the box below.';
    expect(screen.getByText(said)).toBeTruthy();
    expect(screen.getByLabelText(/^Smallest unit you sell/).getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await new Promise((r) => setTimeout(r, 50));
    expect(createProduct).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByLabelText(/^Smallest unit you sell/));
  });

  it('asks No or Yes about packages, No first, and sends none on No', async () => {
    createProduct.mockImplementation(async () => { items = [made]; return made; });
    open();
    await fill('u1');
    expect(screen.getByText(PACKAGES_QUESTION)).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'No' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.queryByTestId('package-row')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).not.toHaveProperty('packs');
    expect((await screen.findAllByText('Test bulb 60W added as TLG-0001.')).length).toBeGreaterThan(0);
  });

  it('makes the item and two packages in one request, the words following the units, and shows it on the list', async () => {
    createProduct.mockImplementation(async () => made);
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const first = screen.getByTestId('package-row');
    // The smallest unit is never offered as its own package.
    expect(within(first).queryByRole('option', { name: 'Bulb' })).toBeNull();
    fireEvent.change(within(first).getByLabelText(/^Package/), { target: { value: 'u2' } });
    const size = within(first).getByLabelText(/^How many bulbs in one carton/);
    expect(size.getAttribute('aria-required')).toBe('true');
    fireEvent.change(size, { target: { value: '10' } });
    expect(within(first).getByRole('radio', { name: 'Both' }).getAttribute('aria-checked')).toBe('true');
    fireEvent.change(within(first).getByLabelText(/^Selling price of one carton/), { target: { value: '45000' } });

    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    const second = screen.getAllByTestId('package-row')[1]!;
    // A unit taken by the first row is not offered again.
    expect(within(second).queryByRole('option', { name: 'Carton' })).toBeNull();
    fireEvent.change(within(second).getByLabelText(/^Package/), { target: { value: 'u3' } });
    // The box's usual pack fills in its size and Used for (ADR-100).
    expect((within(second).getByLabelText(/^How many bulbs in one box/) as HTMLInputElement).value).toBe('100');
    expect(within(second).getByRole('radio', { name: 'Buying' }).getAttribute('aria-checked')).toBe('true');
    expect(within(second).queryByLabelText(/^Selling price of one box/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createProduct.mock.calls[0]?.[0]).toMatchObject({
      unit_id: 'u1',
      packs: [
        { unit_id: 'u2', factor: '10', purpose: 'both', sell_minor: 45000 },
        { unit_id: 'u3', factor: '100', purpose: 'buy' },
      ],
    });
    expect((createProduct.mock.calls[0]?.[0] as { packs: object[] }).packs[1]).not.toHaveProperty('sell_minor');
    expect((await screen.findAllByText('Test bulb 60W added as TLG-0001, with carton of 10 and box of 100.')).length).toBeGreaterThan(0);
    // Back on the Items list, the new item is there though the list was read before it was made.
    expect(await screen.findByRole('button', { name: 'Edit Test bulb 60W' })).toBeTruthy();
  });

  it('blocks the save while a package has no unit or a size under 2', async () => {
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect(await screen.findByText('Choose the package.')).toBeTruthy();
    const row = screen.getByTestId('package-row');
    fireEvent.change(within(row).getByLabelText(/^Package/), { target: { value: 'u2' } });
    fireEvent.change(within(row).getByLabelText(/^How many bulbs in one carton/), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect(await screen.findByText('Type a whole number, 2 or more.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('puts Used for in the owner\'s order, Both, Buying, Selling, and names the picker Package unit', async () => {
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const row = screen.getByTestId('package-row');
    expect(within(row).getAllByRole('radio').map((r) => r.textContent)).toEqual(['Both', 'Buying', 'Selling']);
    expect(within(row).getByLabelText(/^Package unit/)).toBeTruthy();
  });

  it('fills a usual pack size again when the smallest unit changes, and keeps a typed one', async () => {
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.change(within(screen.getByTestId('package-row')).getByLabelText(/^Package/), { target: { value: 'u3' } });
    expect((screen.getByLabelText(/^How many bulbs in one box/) as HTMLInputElement).value).toBe('100');
    // The box's usual pack is 100 bulbs, not 100 pieces.
    fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u4' } });
    expect((screen.getByLabelText(/^How many pieces in one box/) as HTMLInputElement).value).toBe('');
    fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u1' } });
    expect((screen.getByLabelText(/^How many bulbs in one box/) as HTMLInputElement).value).toBe('100');
    fireEvent.change(screen.getByLabelText(/^How many bulbs in one box/), { target: { value: '50' } });
    fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u4' } });
    expect((screen.getByLabelText(/^How many pieces in one box/) as HTMLInputElement).value).toBe('50');
  });

  it('shows a refused package on its own row, not on the smallest unit', async () => {
    const { RetailError } = await import('../../../api/retail');
    const message = 'This unit is switched off. Choose another one.';
    // The problem's detail is the API's generic line; the reason is the package's own field problem (round 4 of the review).
    createProduct.mockRejectedValue(new RetailError('One or more fields are invalid.', 422, 'validation_failed', { pack_index: 1, errors: [{ field: 'packs[1].unit_id', code: 'inactive_unit', message }] }));
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.change(within(screen.getByTestId('package-row')).getByLabelText(/^Package/), { target: { value: 'u2' } });
    fireEvent.change(within(screen.getByTestId('package-row')).getByLabelText(/^How many bulbs in one carton/), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    fireEvent.change(within(screen.getAllByTestId('package-row')[1]!).getByLabelText(/^Package/), { target: { value: 'u3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    const [first, second] = screen.getAllByTestId('package-row');
    expect(await within(second!).findByText(message)).toBeTruthy();
    expect(within(first!).queryByText(message)).toBeNull();
    expect(screen.getAllByText(message)).toHaveLength(1);
    expect(screen.queryByText('One or more fields are invalid.')).toBeNull();
  });

  it('makes a carton of boxes of bulbs: the bigger package holds the box and the bulbs are worked out (issue #699)', async () => {
    createProduct.mockImplementation(async () => made);
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const first = screen.getByTestId('package-row');
    fireEvent.change(within(first).getByLabelText(/^Package/), { target: { value: 'u3' } });
    fireEvent.change(within(first).getByLabelText(/^How many bulbs in one box/), { target: { value: '10' } });
    expect(within(first).getByTestId('package-pieces').textContent).toBe('So 1 box = 10 bulbs');
    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    const second = screen.getAllByTestId('package-row')[1]!;
    fireEvent.change(within(second).getByLabelText(/^Package/), { target: { value: 'u2' } });
    expect((within(second).getByLabelText('What is inside one carton') as HTMLSelectElement).value).not.toBe('0');
    fireEvent.change(within(second).getByLabelText(/^How many boxes in one carton/), { target: { value: '12' } });
    expect(within(second).getByTestId('package-pieces').textContent).toBe('So 1 carton = 120 bulbs');
    const ladder = screen.getByTestId('new-item-ladder');
    expect(within(ladder).getByText(/1 carton → 12 boxes → each 10 bulbs/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect((createProduct.mock.calls[0]?.[0] as { packs: object[] }).packs).toEqual([
      { unit_id: 'u3', factor: '10', purpose: 'buy' },
      { unit_id: 'u2', holds_unit_id: 'u3', holds_qty: 12, purpose: 'both' },
    ]);
    expect((await screen.findAllByText('Test bulb 60W added as TLG-0001, with box of 10 and carton of 12 boxes.')).length).toBeGreaterThan(0);
  });

  // JUSTIFICATION-A3: round 4 of the #692 review asks for a DOM test of the size branch, client cap and server refusal.
  it('caps a package at 100,000 in the form, and shows the server\'s size refusal under that row\'s size (round 4)', async () => {
    const { RetailError } = await import('../../../api/retail');
    const said = 'numeric value out of bounds (<11 digits>.<3 digits> expected)';
    createProduct.mockRejectedValue(new RetailError('One or more fields are invalid.', 422, 'validation_failed', { pack_index: 0, errors: [{ field: 'packs[0].factor', code: 'invalid', message: said }] }));
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    const row = screen.getByTestId('package-row');
    fireEvent.change(within(row).getByLabelText(/^Package unit/), { target: { value: 'u2' } });
    const size = within(row).getByLabelText(/^How many bulbs in one carton/);
    // At the cap the form sends it; a refusal of the size lands on that size field, once.
    fireEvent.change(size, { target: { value: '100000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(await within(row).findByText(said)).toBeTruthy();
    expect(size.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getAllByText(said)).toHaveLength(1);
    // Above the cap the row says so in its own words, and nothing more is sent.
    fireEvent.change(size, { target: { value: '100001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect(await within(row).findByText('Type a number up to 100,000.')).toBeTruthy();
    expect(createProduct).toHaveBeenCalledTimes(1);
  });

  it('offers Add a bigger package up to ten packages, the most the request takes', async () => {
    open();
    await fill('u1');
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    for (let n = 1; n < 10; n++) fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    expect(screen.getAllByTestId('package-row')).toHaveLength(10);
    expect(screen.queryByRole('button', { name: 'Add a bigger package' })).toBeNull();
    expect(screen.getByText('Up to 10 packages')).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: /^Remove the/ })[0]!);
    expect(screen.getByRole('button', { name: 'Add a bigger package' })).toBeTruthy();
  });

  it('shows no package question while packs are off', async () => {
    const off = { ...owner, module_capabilities: { ...owner.module_capabilities, retail: (owner.module_capabilities?.retail ?? []).filter((c) => c !== 'units') } };
    open(off);
    await fill('u1');
    expect(screen.queryByText(PACKAGES_QUESTION)).toBeNull();
  });
});
