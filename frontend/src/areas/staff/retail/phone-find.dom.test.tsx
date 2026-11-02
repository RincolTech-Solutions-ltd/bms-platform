// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { BarcodeMatch, Product, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #690: on a phone, Items and My stock put the search first and a row of category chips under it. A chip filters the list
// through the page's own category filter and is remembered per page; a search that finds nothing offers Add it, which opens
// Quick add with the name, only when nothing narrowed the search; digits that are a barcode find its item; on a phone an item
// is one row with its category, its stock in packs and its selling price, and the row opens the item. Fabricated names, codes
// and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const listStock = vi.fn();
const listCatalogue = vi.fn();
const getProduct = vi.fn();
const findByBarcode = vi.fn<(code: string) => Promise<BarcodeMatch | null>>();
const categoriesFail = { on: false };
let stockRows: StockRow[] = [];
let catalogue: Product[] = [];

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return {
    ...real,
    useNavigate: () => vi.fn(),
    Link: ({ children, to, search, className, 'aria-label': label }: { children: unknown; to: string; search?: Record<string, string>; className?: string; 'aria-label'?: string }) => (
      <a href={search ? `${to}?${new URLSearchParams(search).toString()}` : to} className={className} aria-label={label}>{children as never}</a>
    ),
  };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listStock: (q: unknown) => { listStock(q); return Promise.resolve(stockRows); },
      listCatalogue: (q: unknown) => { listCatalogue(q); return Promise.resolve({ items: catalogue }); },
      getProduct: (id: string) => getProduct(id),
      findByBarcode: (code: string) => findByBarcode(code),
      listUnits: () => Promise.resolve([]),
      listCategories: () => (categoriesFail.on ? Promise.reject(new Error('offline')) : Promise.resolve([
        { id: 'c1', name: 'Test Cables', active: true, product_count: 2, code_prefix: 'CBL', next_code: 'CBL-0003', version: 1 },
        { id: 'c2', name: 'Test Lighting', active: true, product_count: 1, code_prefix: 'LGT', next_code: 'LGT-0002', version: 1 },
      ])),
    },
  };
});

const { StockPage } = await import('./stock');
const { ProductsPage } = await import('./catalogue-products');
const { CategoriesPage } = await import('./catalogue-lists');

const admin = mockMe('admin');
const hq = admin.branches?.[0]?.id ?? '';

function show(node: ReactElement, me: Me = admin) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: hq }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** Below 720px, as on a phone. */
function phoneWidth(phone: boolean) {
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: phone && media.includes('max-width'), media, addEventListener: () => undefined, removeEventListener: () => undefined }));
}

const LABEL = 'Search by name, code, barcode or category';
const chip = (name: string) => within(screen.getByRole('group', { name: 'Categories' })).getByRole('button', { name: new RegExp(`^${name}( |$)`) });
const lastQuery = (fn: ReturnType<typeof vi.fn>) => fn.mock.calls.at(-1)?.[0] as Record<string, unknown>;
const type = (text: string) => fireEvent.change(screen.getByLabelText(LABEL), { target: { value: text } });

beforeEach(() => {
  localStorage.clear();
  findByBarcode.mockResolvedValue(null);
  stockRows = [{ product_id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm', category: 'Test Cables', unit: 'roll', qty: '4.000', sell_minor: 230000 }];
  catalogue = [{
    id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm', category: 'Test Cables', category_id: 'c1', unit: 'pc', sell_minor: 4500, qty: '27.000', active: true,
    packs: [{ id: 'k1', unit_id: 'u2', unit: 'Carton', factor: '12.000', active: true, standard: true }],
  }];
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  for (const f of [listStock, listCatalogue, getProduct, findByBarcode]) f.mockReset();
});

describe('My stock', () => {
  it('puts Items and Categories first, one tap each', async () => {
    show(<StockPage />);
    const entries = screen.getByRole('navigation', { name: 'Items and categories' });
    expect(within(entries).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['/staff/retail/catalogue/products', '/staff/retail/catalogue/categories']);
  });

  it('filters by the chip tapped, through the category filter, and remembers it for the next visit', async () => {
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables')).toBeTruthy());
    expect(chip('All').getAttribute('aria-pressed')).toBe('true');
    expect(chip('All').textContent).toBe('All');
    expect(chip('Test Cables').textContent).toBe('Test Cables 2');
    fireEvent.click(chip('Test Cables'));
    await waitFor(() => expect(lastQuery(listStock).categoryId).toBe('c1'));
    expect(chip('Test Cables').getAttribute('aria-pressed')).toBe('true');
    expect(localStorage.getItem('bms.findChip.stock')).toBe('c1');
    // Items keeps its own chip.
    expect(localStorage.getItem('bms.findChip.items')).toBeNull();

    cleanup();
    listStock.mockReset();
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables').getAttribute('aria-pressed')).toBe('true'));
    expect(lastQuery(listStock).categoryId).toBe('c1');
    fireEvent.click(chip('All'));
    await waitFor(() => expect(lastQuery(listStock).categoryId).toBe(''));
    expect(localStorage.getItem('bms.findChip.stock')).toBeNull();
  });

  it('forgets a remembered category that has gone, and never asks the list with it', async () => {
    localStorage.setItem('bms.findChip.stock', 'gone');
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables')).toBeTruthy());
    expect(chip('All').getAttribute('aria-pressed')).toBe('true');
    await waitFor(() => expect(lastQuery(listStock).categoryId).toBe(''));
    expect(listStock.mock.calls.map((c) => (c[0] as { categoryId?: string }).categoryId)).not.toContain('gone');
    expect(localStorage.getItem('bms.findChip.stock')).toBeNull();
  });

  // JUSTIFICATION-A3: the round 3 review of #703 asks for this case (a remembered chip with categories that cannot load).
  it('shows the list under All when the categories cannot load, never a list waiting for ever', async () => {
    localStorage.setItem('bms.findChip.stock', 'c1');
    categoriesFail.on = true;
    try {
      show(<StockPage />);
      await waitFor(() => expect(listStock).toHaveBeenCalled());
      expect(lastQuery(listStock).categoryId).toBe('');
    } finally {
      categoriesFail.on = false;
    }
  });

  // JUSTIFICATION-A3: new cases the round 2 review of #703 asked for (digits of any length, a barcode under a chip, exact match).
  it.each(['1234567', '123456789012345'])('never offers Add it for digits that match nothing (%s)', async (digits) => {
    stockRows = [];
    show(<StockPage />);
    type(digits);
    expect(await screen.findByText(`No item with the code or barcode "${digits}".`)).toBeTruthy();
    expect(screen.queryByText(/Add it/)).toBeNull();
  });

  it('with a remembered chip, a known barcode of another category says where it looked and Search all categories finds it', async () => {
    localStorage.setItem('bms.findChip.stock', 'c1');
    findByBarcode.mockResolvedValue({ product: { id: 'p2', code: 'LG-0001', description: 'Test bulb 9W' } });
    stockRows = [];
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables').getAttribute('aria-pressed')).toBe('true'));
    type('6001234567890');
    expect(await screen.findByText('No item with the code or barcode "6001234567890" in Test Cables.')).toBeTruthy();
    expect(screen.queryByText(/Add it/)).toBeNull();
    stockRows = [{ product_id: 'p2', code: 'LG-0001', description: 'Test bulb 9W', category: 'Test Lighting', unit: 'pc', qty: '3.000', sell_minor: 6000 }];
    fireEvent.click(screen.getByRole('button', { name: 'Search all categories' }));
    await waitFor(() => expect(lastQuery(listStock)).toMatchObject({ query: 'LG-0001', categoryId: '', includeInactive: true }));
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
  });

  it('shows a found barcode\'s own item alone, not the items whose code only begins the same', async () => {
    findByBarcode.mockResolvedValue({ product: { id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm' } });
    stockRows = [
      { product_id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm', category: 'Test Cables', unit: 'roll', qty: '4.000', sell_minor: 230000 },
      { product_id: 'p10', code: 'CB-00010', description: 'Test cable 10mm', category: 'Test Cables', unit: 'roll', qty: '1.000', sell_minor: 900000 },
    ];
    show(<StockPage />);
    type('6001234567890');
    await waitFor(() => expect(lastQuery(listStock).query).toBe('CB-0001'));
    await waitFor(() => expect(screen.queryByText('Test cable 10mm')).toBeNull());
    expect(screen.getByText('Test cable 2.5mm')).toBeTruthy();
  });

  it('offers Add it with the name typed when a search nothing narrowed finds nothing', async () => {
    stockRows = [];
    show(<StockPage />);
    type('bulb');
    expect(await screen.findByText('No item called "bulb".')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add bulb' }).getAttribute('href')).toBe('/staff/retail/quick-add?name=bulb');
    expect(lastQuery(listStock).query).toBe('bulb');
    const none = screen.getByText('No item called "bulb".').parentElement as HTMLElement;
    fireEvent.click(within(none).getByRole('button', { name: 'Clear search' }));
    await waitFor(() => expect(screen.queryByText('No item called "bulb".')).toBeNull());
  });

  it('with a remembered chip says where it looked and offers all categories, never Add it', async () => {
    localStorage.setItem('bms.findChip.stock', 'c1');
    stockRows = [];
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables').getAttribute('aria-pressed')).toBe('true'));
    type('bulb');
    expect(await screen.findByText('No item called "bulb" in Test Cables.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Add bulb' })).toBeNull();
    stockRows = [{ product_id: 'p2', code: 'LG-0001', description: 'Test bulb 9W', category: 'Test Lighting', unit: 'pc', qty: '3.000', sell_minor: 6000 }];
    fireEvent.click(screen.getByRole('button', { name: 'Search all categories' }));
    await waitFor(() => expect(lastQuery(listStock)).toMatchObject({ query: 'bulb', categoryId: '' }));
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
    expect(chip('All').getAttribute('aria-pressed')).toBe('true');
  });

  it('with Out of stock chosen says the filters hid it, never Add it, and Search all categories clears them', async () => {
    stockRows = [];
    show(<StockPage />);
    fireEvent.click(screen.getByRole('radio', { name: 'Out of stock' }));
    type('bulb');
    expect(await screen.findByText('No item called "bulb" with these filters.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Add bulb' })).toBeNull();
    stockRows = [{ product_id: 'p2', code: 'LG-0001', description: 'Test bulb 9W', category: 'Test Lighting', unit: 'pc', qty: '3.000', sell_minor: 6000 }];
    fireEvent.click(screen.getByRole('button', { name: 'Search all categories' }));
    await waitFor(() => expect(lastQuery(listStock)).toMatchObject({ query: 'bulb', categoryId: '', level: undefined, negativeOnly: false, includeInactive: true }));
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'All items' }).getAttribute('aria-checked')).toBe('true');
  });

  it('finds an item by its barcode, through its code', async () => {
    findByBarcode.mockResolvedValue({ product: { id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm' } });
    show(<StockPage />);
    type('6001234567890');
    await waitFor(() => expect(lastQuery(listStock).query).toBe('CB-0001'));
    expect(findByBarcode).toHaveBeenCalledWith('6001234567890');
    expect(await screen.findByText('Test cable 2.5mm')).toBeTruthy();
  });

  it('says a barcode it does not know is no item, and never offers to add it as a name', async () => {
    stockRows = [];
    show(<StockPage />);
    type('6001234567890');
    expect(await screen.findByText('No item with the code or barcode "6001234567890".')).toBeTruthy();
    expect(screen.queryByText(/Add it/)).toBeNull();
  });

  it('on a phone shows the category under each item', async () => {
    phoneWidth(true);
    show(<StockPage />);
    const card = (await screen.findByText('Test cable 2.5mm')).closest('li') as HTMLElement;
    expect(within(card).getByText('Test Cables')).toBeTruthy();
    expect(card.textContent).toContain('In stock: 4 rolls');
    // One plain list: the chips stand for the groups.
    expect(screen.queryByRole('checkbox', { name: 'Group by category' })).toBeNull();
  });
});

describe('Items', () => {
  it('filters by the chip tapped and asks for the stock of the branch in use', async () => {
    show(<ProductsPage />);
    await waitFor(() => expect(chip('Test Lighting')).toBeTruthy());
    expect(lastQuery(listCatalogue).branchId).toBe(hq);
    fireEvent.click(chip('Test Lighting'));
    await waitFor(() => expect(lastQuery(listCatalogue).categoryId).toBe('c2'));
    expect(localStorage.getItem('bms.findChip.items')).toBe('c2');
  });

  it('never asks for the stock of a branch where the session may not read stock', async () => {
    const other = admin.branches?.find((b) => b.id !== hq)?.id ?? '';
    const elsewhere = { ...admin, permission_scopes: { ...admin.permission_scopes, 'retail.stock.read': { all_branches: false, branch_ids: [other] } } };
    show(<ProductsPage />, elsewhere);
    await waitFor(() => expect(listCatalogue).toHaveBeenCalled());
    expect(lastQuery(listCatalogue).branchId).toBe('');
  });

  it('on a phone shows each item as one row with its category, stock in packs and selling price, and the row opens it', async () => {
    phoneWidth(true);
    getProduct.mockReturnValue(new Promise(() => undefined));
    show(<ProductsPage />);
    const row = await screen.findByRole('button', { name: /Test cable 2\.5mm/ });
    expect(row.textContent).toContain('Test Cables · 2 cartons and 3 pieces (27 pieces)');
    expect(row.textContent).toContain('Selling price');
    expect(row.textContent).toContain('UGX 4,500');
    expect(screen.queryByRole('button', { name: 'Edit Test cable 2.5mm' })).toBeNull();
    // The list comes before the sizes and colours ways to add.
    const sizes = screen.getByRole('link', { name: 'Add an item with sizes and colours' });
    expect(row.compareDocumentPosition(sizes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(row);
    await waitFor(() => expect(getProduct).toHaveBeenCalledWith('p1'));
  });

  it('on a laptop keeps the cards with their buttons', async () => {
    phoneWidth(false);
    show(<ProductsPage />);
    expect(await screen.findByRole('button', { name: 'Edit Test cable 2.5mm' })).toBeTruthy();
  });

  // JUSTIFICATION-A3: the Items half of the chip and filter cases the round 2 review of #703 asked for.
  it('with a chip on says where it looked, never Add it, and Search all categories finds the item in another', async () => {
    localStorage.setItem('bms.findChip.items', 'c1');
    catalogue = [];
    show(<ProductsPage />);
    await waitFor(() => expect(chip('Test Cables').getAttribute('aria-pressed')).toBe('true'));
    type('bulb');
    expect(await screen.findByText('No item called "bulb" in Test Cables.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Add bulb' })).toBeNull();
    catalogue = [{ id: 'p2', code: 'LG-0001', description: 'Test bulb 9W', category: 'Test Lighting', category_id: 'c2', unit: 'pc', sell_minor: 6000, active: true }];
    fireEvent.click(screen.getByRole('button', { name: 'Search all categories' }));
    await waitFor(() => expect(lastQuery(listCatalogue)).toMatchObject({ query: 'bulb', categoryId: '' }));
    expect(await screen.findByText('Test bulb 9W')).toBeTruthy();
  });

  it('with Inactive shown, Search all categories looks at active and inactive items alike', async () => {
    catalogue = [];
    show(<ProductsPage />);
    fireEvent.change(screen.getByLabelText('Show'), { target: { value: 'off' } });
    type('bulb');
    expect(await screen.findByText('No item called "bulb" with these filters.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Add bulb' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Search all categories' }));
    await waitFor(() => expect((screen.getByLabelText('Show') as HTMLSelectElement).value).toBe('all'));
    await waitFor(() => expect(lastQuery(listCatalogue).active).toBeUndefined());
  });

  it('offers Add it when a search nothing narrowed finds nothing', async () => {
    catalogue = [];
    show(<ProductsPage />);
    type('test lamp');
    expect(await screen.findByText('No item called "test lamp".')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add test lamp' }).getAttribute('href')).toBe('/staff/retail/quick-add?name=test+lamp');
  });
});

describe('Categories', () => {
  it('lists each category with its item count and Add a category', async () => {
    show(<CategoriesPage />);
    expect(await screen.findByText('Test Cables')).toBeTruthy();
    expect(screen.getByText('Used by 2 items.')).toBeTruthy();
    expect(screen.getByText('Used by 1 item.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add a category' })).toBeTruthy();
  });
});
