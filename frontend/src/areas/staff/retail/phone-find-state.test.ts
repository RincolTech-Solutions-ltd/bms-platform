// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Category, Product } from '../../../api/retail';
import { chipsOf, itemStock, knownChip, looksLikeBarcode, noItemCase, noItemText, readChip, writeChip } from './phone-find-state';

// Issue #690: the category chips of Items and My stock, the chip each page remembers, and the words of an item's row and of a
// search that finds nothing. Fabricated categories and items.

const cats: Category[] = [
  { id: 'c1', name: 'Test Cables', active: true, product_count: 3 },
  { id: 'c2', name: 'Test Lighting', active: true, product_count: 0 },
  { id: 'c3', name: 'Test Old stock', active: false, product_count: 2 },
  { id: 'c4', name: 'Test Retired', active: false, product_count: 0 },
];

describe('chipsOf', () => {
  it('starts with All, with no count since no list shows every item, then each category with its count in the server order', () => {
    expect(chipsOf(cats, '')).toEqual([
      { id: '', name: 'All' },
      { id: 'c1', name: 'Test Cables', count: 3 },
      { id: 'c2', name: 'Test Lighting', count: 0 },
      { id: 'c3', name: 'Test Old stock', count: 2 },
    ]);
  });

  it('shows a switched-off empty category only while it is the one chosen', () => {
    expect(chipsOf(cats, '').map((c) => c.id)).not.toContain('c4');
    expect(chipsOf(cats, 'c4').map((c) => c.id)).toContain('c4');
  });

  it('is All alone before the categories arrive', () => {
    expect(chipsOf([], '')).toEqual([{ id: '', name: 'All' }]);
  });
});

describe('the remembered chip', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('is kept per page and forgotten when All is chosen', () => {
    writeChip('items', 'c1');
    writeChip('stock', 'c3');
    expect(readChip('items')).toBe('c1');
    expect(readChip('stock')).toBe('c3');
    writeChip('items', '');
    expect(readChip('items')).toBe('');
    expect(localStorage.getItem('bms.findChip.items')).toBeNull();
  });

  it('reads as All and saves nothing when storage is blocked', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    });
    expect(readChip('stock')).toBe('');
    expect(() => writeChip('stock', 'c1')).not.toThrow();
    expect(() => writeChip('stock', '')).not.toThrow();
  });

  it('reads as All once the list shows its category has gone, and stays while the list is loading', () => {
    expect(knownChip('c1', cats)).toBe('c1');
    expect(knownChip('gone', cats)).toBe('');
    expect(knownChip('gone', undefined)).toBe('gone');
    expect(knownChip('', cats)).toBe('');
  });
});

// JUSTIFICATION-A3: new test cases the review of #703 asked for (Add it only for an unnarrowed search, barcodes).
describe('a search that finds nothing', () => {
  it('offers Add it only when nothing narrowed the search', () => {
    expect(noItemCase('bulb', undefined, false)).toEqual({ kind: 'add' });
    expect(noItemCase('bulb', 'Test Cables', false)).toEqual({ kind: 'category', category: 'Test Cables' });
    expect(noItemCase('bulb', undefined, true)).toEqual({ kind: 'filtered' });
    // Digits of any length are never a name to add (review of #703: 7 and 15 digits too).
    expect(noItemCase('6001234567890', undefined, false)).toEqual({ kind: 'barcode' });
    expect(noItemCase('1234567', undefined, false)).toEqual({ kind: 'barcode' });
    expect(noItemCase('123456789012345', undefined, false)).toEqual({ kind: 'barcode' });
    // A chip or filter comes first, so a barcode hidden by it offers Search all categories, never a false "no item".
    expect(noItemCase('6001234567890', 'Test Cables', false)).toEqual({ kind: 'category', category: 'Test Cables' });
    expect(noItemCase('6001234567890', undefined, true)).toEqual({ kind: 'filtered' });
  });

  it('names what was typed, and where it looked', () => {
    expect(noItemText(' bulb ')).toBe('No item called "bulb".');
    expect(noItemText('bulb', { kind: 'category', category: 'Test Cables' })).toBe('No item called "bulb" in Test Cables.');
    expect(noItemText('bulb', { kind: 'filtered' })).toBe('No item called "bulb" with these filters.');
    expect(noItemText('6001234567890', { kind: 'barcode' })).toBe('No item with the code or barcode "6001234567890".');
  });

  it('takes 8 to 14 digits as a barcode, and nothing else', () => {
    expect(['12345678', ' 6001234567890 ', '10061234567890'].every(looksLikeBarcode)).toBe(true);
    expect(['1234567', '123456789012345', 'BLB-0001', '60012 34567', 'bulb 9W'].some(looksLikeBarcode)).toBe(false);
  });
});

describe('the words', () => {

  const item = (over: Partial<Product>): Product => ({ id: 'p1', description: 'Test bulb', unit: 'pc', ...over });

  it('shows the stock in the standard pack and pieces, with full unit words', () => {
    const packs = [{ id: 'k1', unit_id: 'u2', unit: 'Carton', factor: '12.000', active: true, standard: true }];
    expect(itemStock(item({ qty: '27.000', packs }))).toBe('2 Cartons and 3 pieces (27 pieces)');
    expect(itemStock(item({ qty: '1.000' }))).toBe('1 piece');
  });

  it('shows no stock when no branch was asked for', () => {
    expect(itemStock(item({}))).toBe('');
  });
});
