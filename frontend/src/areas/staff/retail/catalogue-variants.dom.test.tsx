// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { pickItem } from '../../../test-utils/item-picker';
import { chooseCategory } from '../../../test-utils/category-picker';

// The screen that makes an item with sizes and colours (issue #384): the sizes and prints typed once give the list of what will
// be made, each with its code and price the owner may change, and Save sends exactly that list. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const createFamily = vi.fn();
const joinFamily = vi.fn();
const romperFamily = () => Promise.resolve({ id: 'f9', name: 'Test Romper', unit_id: 'u1', variants: [{ id: 's1', variant: { family_id: 'f9', family_name: 'Test Romper', size: 'S', position: 0 } }] });
const getFamily = vi.fn<(...a: unknown[]) => Promise<unknown>>(romperFamily);

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Baby wear', active: true, product_count: 0, version: 1, code_prefix: 'BBW', next_code: 'BBW-0007' }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 0, version: 1 }]),
      createFamily: (...a: unknown[]) => createFamily(...a),
      joinFamily: (...a: unknown[]) => joinFamily(...a),
      listProducts: () => Promise.resolve([
        { id: 'p1', code: 'CAB-15', description: 'Test Cable thin', unit: 'piece', unit_id: 'u1', sell_minor: 2000 },
        { id: 'p2', code: 'TWC-15', description: 'Test twin cable 1.5mm', unit: 'piece', unit_id: 'u1', sell_minor: 2500 },
        { id: 'p3', code: 'TWC-25', description: 'Test twin cable 2.5mm', unit: 'piece', unit_id: 'u1', sell_minor: 3500 },
        { id: 's1', code: 'ROMP-S', description: 'Test Romper (S)', unit: 'piece', unit_id: 'u1', sell_minor: 9000, variant: { family_id: 'f9', family_name: 'Test Romper', size: 'S', position: 0 } },
      ]),
      getFamily: (...a: unknown[]) => getFamily(...a),
    },
  };
});

const { NewFamilyPage, JoinPage, joinSuggestion } = await import('./catalogue-variants');

afterEach(() => {
  cleanup();
  createFamily.mockReset();
  joinFamily.mockReset();
  getFamily.mockReset();
  getFamily.mockImplementation(romperFamily);
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });

function screenOf(node: ReactElement) {
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('an item with sizes and colours', () => {
  it('asks the buying price before the selling price (#494)', async () => {
    screenOf(<NewFamilyPage />);
    const selling = await screen.findByLabelText(/^Selling price/);
    const buying = screen.getByLabelText(/^Buying price/);
    expect(buying.compareDocumentPosition(selling) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('lists every combination with the code the app makes and sends the list the owner checked, without codes', async () => {
    createFamily.mockResolvedValue({ id: 'f1', name: 'Test romper', variants: [] });
    screenOf(<NewFamilyPage />);
    fireEvent.change(screen.getByLabelText('What is it called?'), { target: { value: 'Test romper' } });
    // Issue #562: no code is asked; the codes start with the category's next code.
    expect(screen.queryByLabelText(/code/i)).toBeNull();
    await screen.findByRole('option', { name: 'piece' });
    fireEvent.change(screen.getByLabelText('Sizes'), { target: { value: '0-3m' } });
    expect(document.getElementById('variant-0-code')?.textContent).toBe('Item code: made by the app once you choose the category');
    await chooseCategory(screen.getByLabelText('Category'), 'Baby wear');
    fireEvent.change(screen.getByLabelText('Counted in'), { target: { value: 'u1' } });
    fireEvent.change(document.getElementById('sizes-item-sell') as HTMLInputElement, { target: { value: '9000' } });
    fireEvent.change(screen.getByLabelText('Sizes'), { target: { value: '0-3m, 3-6m' } });
    fireEvent.change(screen.getByLabelText('Colours or prints'), { target: { value: 'Blue, Pink' } });
    expect(screen.getByText('4 to be made')).toBeTruthy();
    expect(document.getElementById('variant-3-code')?.textContent).toBe('Item code: BBW-0007-36M-PINK, made by the app');
    expect(screen.queryByLabelText(/^Code of/)).toBeNull();
    // A bigger size costs more: its price is changed in the list.
    fireEvent.change(screen.getByLabelText('Selling price of 3-6m, Pink'), { target: { value: '11000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make these 4' }));
    await waitFor(() => expect(createFamily).toHaveBeenCalledTimes(1));
    const body = createFamily.mock.calls[0]?.[0] as { base_code?: string; variants: { code?: string; size: string; sell_minor: number }[] };
    expect(body).not.toHaveProperty('base_code');
    expect(body.variants.map((v) => v.size)).toEqual(['0-3m', '0-3m', '3-6m', '3-6m']);
    expect(body.variants.every((v) => !('code' in v))).toBe(true);
    expect(body.variants[3]?.sell_minor).toBe(11000);
    expect(body.variants[0]?.sell_minor).toBe(9000);
  });

  it('says in plain words when two would share a code', async () => {
    screenOf(<NewFamilyPage />);
    await screen.findByRole('option', { name: 'piece' });
    await chooseCategory(screen.getByLabelText('Category'), 'Baby wear');
    fireEvent.change(screen.getByLabelText('Sizes'), { target: { value: '0-3m, 03m' } });
    expect(screen.getByRole('note').textContent).toContain('Two of them would have the code BBW-0007-03M. Spell one of the sizes or colours differently');
  });

});

describe('putting items together as sizes (issue #707)', () => {
  const ready = () => screen.getByRole('button', { name: 'Put them together' }).getAttribute('data-not-ready') !== 'true';

  /** Add more sizes to an item that already has sizes: one size of it names the item, then the same three steps. */
  async function addingTo(name: string, typed: string) {
    fireEvent.click(await screen.findByRole('button', { name: 'Add more sizes to an item that already has sizes' }));
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    await pickItem(name, { typed });
  }

  it('is three steps with one search, the name and the sizes suggested, and says what it did', async () => {
    joinFamily.mockResolvedValue({ id: 'f2', name: 'Test twin cable', variants: [] });
    screenOf(<JoinPage />);
    expect(await screen.findByRole('heading', { name: '1. Pick the items' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '2. Name the item' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '3. Check the sizes' })).toBeTruthy();
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'More about Join items as sizes' })).toBeTruthy();

    await pickItem('Test twin cable 1.5mm', { typed: 'twin' });
    expect(ready()).toBe(false);
    expect(screen.getByRole('note').textContent).toBe('Tick 2 or more items.');
    await pickItem('Test twin cable 2.5mm', { typed: 'twin' });
    expect(screen.getByRole('list', { name: 'Ticked items' }).querySelectorAll('li')).toHaveLength(2);
    expect((screen.getByLabelText('What do customers call it?') as HTMLInputElement).value).toBe('Test twin cable');
    expect((screen.getByLabelText('Size of Test twin cable 1.5mm') as HTMLInputElement).value).toBe('1.5mm');
    expect((screen.getByLabelText('Size of Test twin cable 2.5mm') as HTMLInputElement).value).toBe('2.5mm');

    fireEvent.click(screen.getByRole('button', { name: 'Put them together' }));
    await waitFor(() => expect(joinFamily).toHaveBeenCalledTimes(1));
    expect(joinFamily.mock.calls[0]?.[0]).toBeNull();
    expect(joinFamily.mock.calls[0]?.[1]).toEqual({ name: 'Test twin cable', items: [{ product_id: 'p2', size: '1.5mm' }, { product_id: 'p3', size: '2.5mm' }] });
    expect(await screen.findByRole('heading', { name: 'Test twin cable now has 2 sizes' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open it' }).getAttribute('href')).toBe('/staff/retail/catalogue/products/sizes/f2');
    fireEvent.click(screen.getByRole('button', { name: 'Put more together' }));
    expect(await screen.findByRole('heading', { name: '1. Pick the items' })).toBeTruthy();
    expect(screen.queryByRole('list', { name: 'Ticked items' })).toBeNull();
  });

  it('takes a tick off with a second tap or Remove, and keeps a name or size the owner typed', async () => {
    screenOf(<JoinPage />);
    await pickItem('Test twin cable 1.5mm', { typed: 'twin' });
    await pickItem('Test twin cable 2.5mm', { typed: 'twin' });
    fireEvent.change(screen.getByLabelText('What do customers call it?'), { target: { value: 'Test cable' } });
    fireEvent.change(screen.getByLabelText('Size of Test twin cable 2.5mm'), { target: { value: 'Thick' } });
    await pickItem('Test Cable thin', { typed: 'Cable' });
    expect((screen.getByLabelText('What do customers call it?') as HTMLInputElement).value).toBe('Test cable');
    expect((screen.getByLabelText('Size of Test twin cable 2.5mm') as HTMLInputElement).value).toBe('Thick');
    // A second tap on a ticked match takes its tick off.
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'thin' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Cable thin .*Ticked$/ }));
    expect(screen.queryByLabelText('Size of Test Cable thin')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test twin cable 1.5mm' }));
    expect(screen.getAllByRole('listitem').filter((li) => li.textContent?.includes('1.5mm'))).toHaveLength(0);
    expect(ready()).toBe(false);
  });

  it('adds to an item that has sizes already, sending only the items and not a name', async () => {
    joinFamily.mockResolvedValue({ id: 'f9', name: 'Test Romper', variants: [] });
    screenOf(<JoinPage />);
    await addingTo('Test Romper (S)', 'Romper');
    expect(await screen.findByText('Test Romper', { selector: 'strong' })).toBeTruthy();
    expect(screen.queryByLabelText('What do customers call it?')).toBeNull();
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    await pickItem('Test Cable thin', { typed: 'Cable' });
    // Without the item's own name, what is left of the item's name is its size.
    expect((screen.getByLabelText('Size of Test Cable thin') as HTMLInputElement).value).toBe('Cable thin');
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 'M' } });
    fireEvent.click(screen.getByRole('button', { name: 'Put them together' }));
    await waitFor(() => expect(joinFamily).toHaveBeenCalledTimes(1));
    expect(joinFamily.mock.calls[0]?.[0]).toBe('f9');
    expect(joinFamily.mock.calls[0]?.[1]).toEqual({ items: [{ product_id: 'p1', size: 'M' }] });
    expect(await screen.findByRole('heading', { name: 'Test Romper now has 2 sizes' })).toBeTruthy();
  });

  it('warns before sending a size the item has already', async () => {
    screenOf(<JoinPage />);
    await addingTo('Test Romper (S)', 'Romper');
    await screen.findByText('Test Romper', { selector: 'strong' });
    await pickItem('Test Cable thin', { typed: 'Cable' });
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 's' } });
    await waitFor(() => expect(screen.getByRole('note').textContent).toContain('Test Romper has one of these sizes and colours already'));
    expect(ready()).toBe(false);
  });

  it('says so when the item chosen to add to has no sizes, instead of doing nothing', async () => {
    screenOf(<JoinPage />);
    await addingTo('Test Cable thin', 'Cable');
    expect(screen.getByRole('note').textContent).toContain('Test Cable thin has no sizes yet');
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByLabelText('What do customers call it?')).toBeTruthy();
  });

  it('waits for the item it joins before judging the units, and says when it could not be read', async () => {
    getFamily.mockImplementation(() => new Promise(() => {}));
    screenOf(<JoinPage />);
    await addingTo('Test Romper (S)', 'Romper');
    await screen.findByText('Test Romper', { selector: 'strong' });
    await pickItem('Test Cable thin', { typed: 'Cable' });
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 'M' } });
    expect(screen.getByRole('note').textContent).toContain('Reading Test Romper');
    expect(screen.getByRole('note').textContent).not.toContain('one unit');
    cleanup();

    getFamily.mockImplementation(() => Promise.reject(new Error('offline')));
    screenOf(<JoinPage />);
    await addingTo('Test Romper (S)', 'Romper');
    await screen.findByText('Test Romper', { selector: 'strong' });
    await pickItem('Test Cable thin', { typed: 'Cable' });
    await waitFor(() => expect(screen.getByRole('note').textContent).toContain('Test Romper could not be read'));
    expect(ready()).toBe(false);
  });
});

describe('the suggested name and sizes', () => {
  it('takes the shared words as the name and the rest as each size', () => {
    expect(joinSuggestion(['Twin cable 1.5mm', 'Twin cable 2.5mm'])).toEqual({ name: 'Twin cable', sizes: ['1.5mm', '2.5mm'] });
    expect(joinSuggestion(['Bulb 9W LED', 'bulb 12W led'])).toEqual({ name: 'Bulb LED', sizes: ['9W', '12W'] });
    expect(joinSuggestion(['Romper (S)', 'Romper (M)'])).toEqual({ name: 'Romper', sizes: ['S', 'M'] });
  });

  it('leaves every item a size, and suggests nothing from one item alone', () => {
    expect(joinSuggestion(['Soap', 'Soap bar'])).toEqual({ name: '', sizes: ['Soap', 'Soap bar'] });
    expect(joinSuggestion(['Twin cable 1.5mm'])).toEqual({ name: '', sizes: [''] });
    expect(joinSuggestion(['Romper (L)'], 'Romper')).toEqual({ name: 'Romper', sizes: ['L'] });
  });
});
