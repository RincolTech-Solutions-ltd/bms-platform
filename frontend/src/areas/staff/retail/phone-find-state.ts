import type { Category, Product } from '../../../api/retail';
import { packsAndPieces, standardPack } from './units-state';

// Finding items on a phone (issue #690): the category chips under the search box of Items and My stock, the chip each page
// remembers, and the words of an item's row and of a search that finds nothing.

export type FindPage = 'items' | 'stock';

export type Chip = { id: string; name: string; count: number };

/**
 * All, with every item, then each category in the server's order with its item count. A switched-off category shows only while
 * it still holds items or is the one chosen, so a chip never leads to a list that cannot be reached.
 */
export function chipsOf(categories: Category[], chosen: string): Chip[] {
  const shown = categories.filter((c) => c.active !== false || (c.product_count ?? 0) > 0 || c.id === chosen);
  const all = categories.reduce((sum, c) => sum + (c.product_count ?? 0), 0);
  return [{ id: '', name: 'All', count: all }, ...shown.map((c) => ({ id: c.id ?? '', name: c.name ?? '', count: c.product_count ?? 0 }))];
}

const chipKey = (page: FindPage) => `bms.findChip.${page}`;

/** The chip this page had last time; blocked or missing storage means All. */
export function readChip(page: FindPage): string {
  try {
    return localStorage.getItem(chipKey(page)) ?? '';
  } catch {
    return '';
  }
}

export function writeChip(page: FindPage, id: string): void {
  try {
    if (id) localStorage.setItem(chipKey(page), id);
    else localStorage.removeItem(chipKey(page));
  } catch {
    // A private window or blocked storage: the chip lasts until the page is left.
  }
}

/** A remembered category that is no longer in the list reads as All, once the list is known. */
export function knownChip(id: string, categories: Category[] | undefined): string {
  if (!id || !categories) return id;
  return categories.some((c) => c.id === id) ? id : '';
}

/** The empty answer to a search, with the name as typed: No item called "bulb". */
export function noItemText(term: string): string {
  return `No item called "${term.trim()}".`;
}

/** An item's stock in the branch in use, in its standard pack and pieces; empty when no branch was asked for. */
export function itemStock(product: Product): string {
  if (product.qty === undefined) return '';
  return packsAndPieces(product.qty, product.unit, standardPack(product));
}
