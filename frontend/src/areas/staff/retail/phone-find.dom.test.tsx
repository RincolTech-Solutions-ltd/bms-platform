// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #690: on a phone, Items and My stock put the search first and a row of category chips under it. A chip filters the list
// through the page's own category filter and is remembered per page; a search that finds nothing offers Add it, which opens
// Quick add with the name; on a phone an item is one row with its category, its stock in packs and its selling price, and the
// row opens the item. Fabricated names, codes and amounts.

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const listStock = vi.fn();
const listCatalogue = vi.fn();
const getProduct = vi.fn();
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
      listUnits: () => Promise.resolve([]),
      listCategories: () => Promise.resolve([
        { id: 'c1', name: 'Test Cables', active: true, product_count: 2 },
        { id: 'c2', name: 'Test Lighting', active: true, product_count: 1 },
      ]),
    },
  };
});

const { StockPage } = await import('./stock');
const { ProductsPage } = await import('./catalogue-products');

const me = mockMe('admin');
const hq = me.branches?.[0]?.id ?? '';

function show(node: ReactElement) {
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

const chip = (name: string) => within(screen.getByRole('group', { name: 'Categories' })).getByRole('button', { name: new RegExp(`^${name} `) });
const lastQuery = (fn: ReturnType<typeof vi.fn>) => fn.mock.calls.at(-1)?.[0] as Record<string, unknown>;

beforeEach(() => {
  localStorage.clear();
  stockRows = [{ product_id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm', category: 'Test Cables', unit: 'roll', qty: '4.000', sell_minor: 230000 }];
  catalogue = [{
    id: 'p1', code: 'CB-0001', description: 'Test cable 2.5mm', category: 'Test Cables', category_id: 'c1', unit: 'pc', sell_minor: 4500, qty: '27.000', active: true,
    packs: [{ id: 'k1', unit_id: 'u2', unit: 'Carton', factor: '12.000', active: true, standard: true }],
  }];
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  for (const f of [listStock, listCatalogue, getProduct]) f.mockReset();
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
    expect(chip('All').textContent).toBe('All 3');
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

  it('forgets a remembered category that has gone', async () => {
    localStorage.setItem('bms.findChip.stock', 'gone');
    show(<StockPage />);
    await waitFor(() => expect(chip('Test Cables')).toBeTruthy());
    expect(chip('All').getAttribute('aria-pressed')).toBe('true');
    await waitFor(() => expect(lastQuery(listStock).categoryId).toBe(''));
  });

  it('offers Add it with the name typed when a search finds nothing', async () => {
    stockRows = [];
    show(<StockPage />);
    fireEvent.change(screen.getByLabelText('Search by name, code or category'), { target: { value: 'bulb' } });
    expect(await screen.findByText('No item called "bulb".')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add bulb' }).getAttribute('href')).toBe('/staff/retail/quick-add?name=bulb');
    expect(lastQuery(listStock).query).toBe('bulb');
    const none = screen.getByText('No item called "bulb".').parentElement as HTMLElement;
    fireEvent.click(within(none).getByRole('button', { name: 'Clear search' }));
    await waitFor(() => expect(screen.queryByText('No item called "bulb".')).toBeNull());
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

  it('on a phone shows each item as one row with its category, stock in packs and selling price, and the row opens it', async () => {
    phoneWidth(true);
    getProduct.mockReturnValue(new Promise(() => undefined));
    show(<ProductsPage />);
    const row = await screen.findByRole('button', { name: /Test cable 2\.5mm/ });
    expect(row.textContent).toContain('Test Cables · 2 Cartons and 3 pieces (27 pieces)');
    expect(row.textContent).toContain('Selling price');
    expect(row.textContent).toContain('UGX 4,500');
    expect(screen.queryByRole('button', { name: 'Edit Test cable 2.5mm' })).toBeNull();
    fireEvent.click(row);
    await waitFor(() => expect(getProduct).toHaveBeenCalledWith('p1'));
  });

  it('on a laptop keeps the cards with their buttons', async () => {
    phoneWidth(false);
    show(<ProductsPage />);
    expect(await screen.findByRole('button', { name: 'Edit Test cable 2.5mm' })).toBeTruthy();
  });

  it('offers Add it when a search finds nothing', async () => {
    catalogue = [];
    show(<ProductsPage />);
    fireEvent.change(screen.getByLabelText('Search by name, code or category'), { target: { value: 'test lamp' } });
    expect(await screen.findByText('No item called "test lamp".')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add test lamp' }).getAttribute('href')).toBe('/staff/retail/quick-add?name=test+lamp');
  });
});
