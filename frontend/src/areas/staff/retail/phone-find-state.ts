import type { Category, Product } from '../../../api/retail';
import { packsAndPieces, standardPack } from './units-state';

// Finding items on a phone (issue #690): the category chips under the search box of Items and My stock, the chip each page
// remembers, and the words of an item's row and of a search that finds nothing.

export type FindPage = 'items' | 'stock';

/** `count` is every item filed in the category, as the Categories page counts them; All has none, since a list never shows them all. */
export type Chip = { id: string; name: string; count?: number };

/**
 * All, then each category in the server's order with its item count. A switched-off category shows only while it still holds
 * items or is the one chosen, so a chip never leads to a list that cannot be reached.
 */
export function chipsOf(categories: Category[], chosen: string): Chip[] {
  const shown = categories.filter((c) => c.active !== false || (c.product_count ?? 0) > 0 || c.id === chosen);
  return [{ id: '', name: 'All' }, ...shown.map((c) => ({ id: c.id ?? '', name: c.name ?? '', count: c.product_count ?? 0 }))];
}

/** A search of digits only, 8 to 14 of them, is looked up as a barcode first (EAN-8 to the 14 of a carton's GTIN). */
export function looksLikeBarcode(term: string): boolean {
  return /^\d{8,14}$/.test(term.trim());
}

/** Digits only, of any length: a code or a barcode, never a name to add. */
const digitsOnly = (term: string) => /^\d+$/.test(term.trim());

/**
 * What an empty answer offers. With a chip chosen the item may be in another category and with another filter on it may be
 * hidden, so both offer Search all categories, whatever was typed; digits are a code or a barcode, never a name to add; Add it
 * only for a name that nothing narrowed.
 */
export type NoItemCase = { kind: 'category'; category: string } | { kind: 'filtered' } | { kind: 'digits' } | { kind: 'add' };

export function noItemCase(term: string, category: string | undefined, filtered: boolean): NoItemCase {
  if (category) return { kind: 'category', category };
  if (filtered) return { kind: 'filtered' };
  if (digitsOnly(term)) return { kind: 'digits' };
  return { kind: 'add' };
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

/** The empty answer to a search, with the name as typed: No item called "bulb", in a category or with filters when they narrowed it. */
export function noItemText(term: string, why: NoItemCase = { kind: 'add' }): string {
  const t = term.trim();
  const what = digitsOnly(t) ? `with the code or barcode "${t}"` : `called "${t}"`;
  if (why.kind === 'category') return `No item ${what} in ${why.category}.`;
  if (why.kind === 'filtered') return `No item ${what} with these filters.`;
  return `No item ${what}.`;
}

/** An item's stock in the branch in use, in its standard pack and pieces; empty when no branch was asked for. */
export function itemStock(product: Product): string {
  if (product.qty === undefined) return '';
  return packsAndPieces(product.qty, product.unit, standardPack(product));
}
