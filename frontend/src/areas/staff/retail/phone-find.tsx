import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Category, Product } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ListRow } from '../../../app/ui/ListRow';
import { ScreenLink } from './screen-link';
import { money } from './ui';
import { chipsOf, itemStock, knownChip, noItemText, readChip, writeChip, type FindPage } from './phone-find-state';

// Finding items on a phone (issue #690): the search box and the category chips stay at the top while the list scrolls, the
// chips scroll sideways inside their own row, an item is one big row that opens it, and a search that finds nothing offers
// Add it, which opens Quick add with the name typed (its `name` route param), the add form that takes a name from the link.

/** The page's chosen chip, remembered per page; a remembered category that has gone reads as All. */
export function useChosenChip(page: FindPage, categories: Category[] | undefined): [string, (id: string) => void] {
  const [id, setId] = useState(() => readChip(page));
  const choose = (next: string) => {
    setId(next);
    writeChip(page, next);
  };
  return [knownChip(id, categories), choose];
}

/** The search and the chips: sticky under the top bar on a phone (`.find-bar` in retail.css). */
export function FindBar({ children, tour }: { children: ReactNode; tour?: string }) {
  return <div className="find-bar" data-tour={tour}>{children}</div>;
}

/** One row of chips, All first, each with its item count; the chosen one is pressed and scrolled into the row's view. */
export function CategoryChips({ categories, value, onChange }: { categories: Category[]; value: string; onChange: (id: string) => void }) {
  const row = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const box = row.current;
    const chosen = box?.querySelector<HTMLElement>('[aria-pressed="true"]');
    // Only the row scrolls, never the page: the chip is brought in by the row's own scroll position.
    if (box && chosen && (chosen.offsetLeft < box.scrollLeft || chosen.offsetLeft + chosen.offsetWidth > box.scrollLeft + box.clientWidth)) {
      box.scrollLeft = chosen.offsetLeft - 16;
    }
  }, [value, categories.length]);
  return (
    <div className="find-chips" ref={row} role="group" aria-label="Categories">
      {chipsOf(categories, value).map((c) => (
        <button key={c.id || 'all'} type="button" className="find-chip" aria-pressed={c.id === value} onClick={() => onChange(c.id)}>
          {c.name} <span className="find-chip-count num">{c.count}</span>
        </button>
      ))}
    </div>
  );
}

/** A search that found nothing: the name as typed, Add it for whoever may use Quick add, and the way back. */
export function NoItem({ term, onClear }: { term: string; onClear: () => void }) {
  return (
    <div className="empty-state find-none">
      <p>{noItemText(term)}</p>
      <p className="cluster">
        <ScreenLink screen="quickAdd" to="/staff/retail/quick-add" search={{ name: term.trim() }} label={`Add ${term.trim()}`} action>Add it</ScreenLink>
        <button type="button" onClick={onClear}>Clear search</button>
      </p>
    </div>
  );
}

/** An item as one row of at least 56px: its name, the category and the stock under it, the selling price at the right. */
export function ItemRow({ product, onOpen }: { product: Product; onOpen: () => void }) {
  const stock = itemStock(product);
  return (
    <ListRow
      title={<>{product.description}{product.active === false && <span className="badge badge-warning">Inactive</span>}</>}
      meta={[product.category, stock].filter(Boolean).join(' · ')}
      amount={product.sell_minor !== undefined ? <><span className="find-row-word">{WORDS.sellingPrice}</span>{money(product.sell_minor)}</> : undefined}
      onClick={onOpen}
    />
  );
}
