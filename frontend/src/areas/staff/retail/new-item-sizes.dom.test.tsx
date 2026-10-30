// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Family, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { READY_COLOURS, SIZE_KINDS } from '../../../api/retail-variants';
import { StaffContext } from '../context';
import { chooseCategory } from '../../../test-utils/category-picker';

// Issue #697 (ADR-111, D81, D98): Add an item asks about sizes, colours or types in the same form, offers the ready lists as
// chips, and one save makes the item, its sizes and their packages. Every item, unit and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const createProduct = vi.fn();
const createFamily = vi.fn();
const lighting = { id: 'c1', name: 'Test lighting', active: true, product_count: 0, version: 1, code_prefix: 'TLG', next_code: 'TLG-0001' };
const units: Unit[] = [
  { id: 'u1', name: 'Bulb', active: true, product_count: 0, version: 1, is_package: false },
  { id: 'u2', name: 'Carton', active: true, product_count: 0, version: 1, is_package: true },
];
const family: Family = {
  id: 'f1', name: 'Test longlife bulb', base_code: 'TLG-0001', category_id: 'c1', unit_id: 'u1', version: 1,
  variants: [
    { id: 'v1', code: 'TLG-0001-40W', description: 'Test longlife bulb (40W)', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Bulb', sell_minor: 3000, active: true, version: 1 },
    { id: 'v2', code: 'TLG-0001-60W', description: 'Test longlife bulb (60W)', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Bulb', sell_minor: 4000, active: true, version: 1 },
    { id: 'v3', code: 'TLG-0001-100W', description: 'Test longlife bulb (100W)', category_id: 'c1', category: 'Test lighting', unit_id: 'u1', unit: 'Bulb', sell_minor: 4000, active: true, version: 1 },
  ],
};

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
      listCatalogue: () => Promise.resolve({ items: [] }),
      listCategories: () => Promise.resolve([lighting]),
      listUnits: () => Promise.resolve(units),
      similarProducts: () => Promise.resolve({ exact: [], similar: [] }),
      nameHints: () => Promise.resolve({}),
      variantOptions: () => Promise.resolve({ sizes: ['Size 7'], colours: ['Sky blue'] }),
      createProduct: (...a: unknown[]) => createProduct(...a),
      createFamily: (...a: unknown[]) => createFamily(...a),
    },
  };
});

const { ProductsPage } = await import('./catalogue-products');
const { withPacksOn } = await import('./catalogue-packs');
const { SIZES_QUESTION, chosenOptions, inOrder, inSizes, toggled } = await import('./new-item-sizes');

afterEach(() => {
  cleanup();
  createProduct.mockReset();
  createFamily.mockReset();
});

function open() {
  const me = withPacksOn(mockMe('admin'));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '', chooseBranch: () => undefined }}><ProductsPage /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

async function fill() {
  fireEvent.click(await screen.findByRole('button', { name: 'Add an item' }));
  await chooseCategory(screen.getByLabelText(/^Category/), 'Test lighting');
  fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Test longlife bulb' } });
  await screen.findByRole('option', { name: 'Bulb' });
  fireEvent.change(screen.getByLabelText(/^Smallest unit you sell/), { target: { value: 'u1' } });
  fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '4000' } });
}

const chip = (group: string, name: string) => within(screen.getByRole('group', { name: group })).getByRole('button', { name });

describe('the ready lists of sizes and colours (D98)', () => {
  it('ships the colours and the kinds of size the owner approved', () => {
    expect(READY_COLOURS).toEqual(['Black', 'White', 'Red', 'Blue', 'Green', 'Yellow', 'Orange', 'Brown', 'Grey', 'Pink', 'Purple', 'Silver', 'Gold', 'Clear']);
    expect(SIZE_KINDS.map((k) => k.label)).toEqual(['Clothes', 'Shoes', 'Bulbs (watts)', 'Cable (mm)', 'Other']);
    expect(SIZE_KINDS.find((k) => k.key === 'shoes')?.sizes).toEqual(['36', '37', '38', '39', '40', '41', '42', '43', '44', '45', '46']);
    expect(SIZE_KINDS.find((k) => k.key === 'other')?.sizes).toEqual([]);
  });

  it('keeps the order of the list whatever order the chips were tapped in, the shop\'s own after', () => {
    expect(inOrder(['5W', '40W', '60W'], ['60W', 'Size 7', '40w'])).toEqual(['40W', '60W', 'Size 7']);
    expect(toggled(['40W'], '40w')).toEqual([]);
    expect(chosenOptions({ answer: 'no', kind: 'bulbs', sizes: ['40W'], colours: [] })).toEqual({ sizes: [], colours: [] });
    expect(inSizes(3, 3, 0)).toBe('in 3 sizes');
    expect(inSizes(2, 0, 2)).toBe('in 2 colours');
    expect(inSizes(6, 3, 2)).toBe('in 6 sizes and colours');
  });
});

describe('sizes on Add an item', () => {
  it('asks the question with No first and a tip, and on No saves the one item as before', async () => {
    createProduct.mockImplementation(async () => ({ ...family.variants![0]!, id: 'p1', code: 'TLG-0001', description: 'Test longlife bulb' }));
    open();
    await fill();
    expect(screen.getByText(SIZES_QUESTION)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'More about Does it come in different sizes, colours or types?' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'No, one size' }).getAttribute('aria-checked')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
    expect(createFamily).not.toHaveBeenCalled();
  });

  it('makes three bulb sizes with a carton of 24 in one save, each at its own price', async () => {
    createFamily.mockImplementation(async () => family);
    open();
    await fill();
    fireEvent.click(screen.getByRole('radio', { name: 'Yes, sizes, colours or types' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Bulbs (watts)' }));
    // Tapped out of order, saved in the order of the list.
    for (const s of ['100W', '40W', '60W']) fireEvent.click(chip('Sizes', s));
    expect(chip('Sizes', '40W').getAttribute('aria-pressed')).toBe('true');
    // A size the shop typed before is offered again.
    expect(await screen.findByRole('button', { name: 'Size 7' })).toBeTruthy();
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.change(screen.getByLabelText(/^Package unit/), { target: { value: 'u2' } });
    fireEvent.change(screen.getByLabelText(/^How many bulbs in one carton/), { target: { value: '24' } });
    fireEvent.change(screen.getByLabelText(/^Selling price of one carton/), { target: { value: '96000' } });
    expect(screen.queryByTestId('size-pack-note')).toBeNull();
    fireEvent.change(screen.getByLabelText('Selling price of 40W'), { target: { value: '3000' } });
    fireEvent.change(screen.getByLabelText('Selling price of 100W'), { target: { value: '6000' } });
    // Round 2 of #723: sizes priced apart get no carton price, and the form says where to set it.
    expect(screen.getByTestId('size-pack-note').textContent).toBe("Sizes have different prices, so set the carton price on each size's page after saving.");
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createFamily).toHaveBeenCalledTimes(1));
    expect(createProduct).not.toHaveBeenCalled();
    expect(createFamily.mock.calls[0]?.[0]).toMatchObject({
      name: 'Test longlife bulb',
      category_id: 'c1',
      unit_id: 'u1',
      sell_minor: 4000,
      variants: [{ size: '40W', sell_minor: 3000 }, { size: '60W', sell_minor: 4000 }, { size: '100W', sell_minor: 6000 }],
      packs: [{ unit_id: 'u2', factor: '24', purpose: 'both' }],
    });
    expect((createFamily.mock.calls[0]?.[0] as { packs: object[] }).packs[0]).not.toHaveProperty('sell_minor');
    expect((await screen.findAllByText('Test longlife bulb added in 3 sizes, with carton of 24.')).length).toBeGreaterThan(0);
  });

  // JUSTIFICATION-A3: round 2 of #723 asks a DOM test of one price with a carton price, and of a kind switch.
  it('saves the carton price on every size while all keep the item\'s price', async () => {
    createFamily.mockImplementation(async () => family);
    open();
    await fill();
    fireEvent.click(screen.getByRole('radio', { name: 'Yes, sizes, colours or types' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Bulbs (watts)' }));
    for (const s of ['40W', '60W', '100W']) fireEvent.click(chip('Sizes', s));
    fireEvent.click(screen.getByRole('radio', { name: 'Yes' }));
    fireEvent.change(screen.getByLabelText(/^Package unit/), { target: { value: 'u2' } });
    fireEvent.change(screen.getByLabelText(/^How many bulbs in one carton/), { target: { value: '24' } });
    fireEvent.change(screen.getByLabelText(/^Selling price of one carton/), { target: { value: '96000' } });
    expect(screen.queryByTestId('size-pack-note')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createFamily).toHaveBeenCalledTimes(1));
    expect(createFamily.mock.calls[0]?.[0]).toMatchObject({ packs: [{ unit_id: 'u2', factor: '24', purpose: 'both', sell_minor: 96000 }] });
  });

  it('drops the ready sizes of the kind left, and keeps the shop\'s own', async () => {
    open();
    await fill();
    fireEvent.click(screen.getByRole('radio', { name: 'Yes, sizes, colours or types' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Bulbs (watts)' }));
    fireEvent.click(chip('Sizes', '40W'));
    fireEvent.change(screen.getByLabelText('Type another size'), { target: { value: 'Long neck' } });
    fireEvent.keyDown(screen.getByLabelText('Type another size'), { key: 'Enter' });
    fireEvent.click(screen.getByRole('button', { name: 'Clothes' }));
    expect(screen.queryByRole('button', { name: '40W' })).toBeNull();
    expect(chip('Your sizes', 'Long neck').getAttribute('aria-pressed')).toBe('true');
    expect(SIZE_KINDS.find((k) => k.key === 'cable')?.sizes[0]).toBe('1mm');
  });

  it('takes colours alone, typed ones too, and asks for at least two', async () => {
    createFamily.mockImplementation(async () => family);
    open();
    await fill();
    fireEvent.click(screen.getByRole('radio', { name: 'Yes, sizes, colours or types' }));
    fireEvent.click(chip('Colours', 'Red'));
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    expect((await screen.findAllByText('Tap at least 2 sizes or colours.')).length).toBeGreaterThan(0);
    expect(createFamily).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Type another colour'), { target: { value: 'Sea green' } });
    fireEvent.keyDown(screen.getByLabelText('Type another colour'), { key: 'Enter' });
    expect(chip('Colours', 'Sea green').getAttribute('aria-pressed')).toBe('true');
    expect(createFamily).not.toHaveBeenCalled();
    // The refused press holds the button for a moment against a double press (BusyButton).
    await new Promise((r) => setTimeout(r, 50));
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    await waitFor(() => expect(createFamily).toHaveBeenCalledTimes(1));
    expect(createFamily.mock.calls[0]?.[0]).toMatchObject({ variants: [{ colour: 'Red' }, { colour: 'Sea green' }] });
  });
});
