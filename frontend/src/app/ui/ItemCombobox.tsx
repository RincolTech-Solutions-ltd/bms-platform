import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import type { LiveSearch } from './useLiveSearch';

// The one item picker (issue #534): a search box whose matches show as a short list under it only while something is typed,
// like a dropdown, and close once one is picked. Picking adds the item, empties the box and puts the cursor back in it for the
// next item. It follows the combobox pattern of the WAI-ARIA practices: the input is the combobox, the list a listbox, the
// highlighted option is announced through aria-activedescendant, Up and Down move it, Enter picks it, Escape closes the list
// and a second Escape empties the box. The search is debounced by useLiveSearch; the screen owns where the matches come from.

/** The most matches the list shows; typing more narrows it. */
export const PICKER_LIMIT = 10;

/** What the status line says, and a screen reader announces. */
export function pickerStatus(s: { text: string; term: string; pending: boolean; fetching: boolean; count: number | undefined; more: boolean }): string {
  if (s.text.trim() === '') return '';
  if (s.pending || s.fetching || s.count === undefined) return 'Searching';
  if (s.count === 0) return `No item matches "${s.term}".`;
  const n = s.count === 1 ? '1 item' : `${s.count} items`;
  return s.more ? `First ${n} shown. Type more to narrow.` : `${n} found`;
}

export function ItemCombobox<T>({ id, label, search, results, fetching = false, error, optionKey, renderOption, onPick, hint }: {
  id: string;
  label: string;
  search: LiveSearch;
  /** The matches of `search.term`, undefined until the first answer. More than PICKER_LIMIT says there are more. */
  results: T[] | undefined;
  /** An answer for the current term is on its way. */
  fetching?: boolean;
  /** Drawn in the list when the search failed. */
  error?: ReactNode;
  optionKey: (item: T) => string;
  renderOption: (item: T) => ReactNode;
  onPick: (item: T) => void;
  hint?: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  const listId = `${id}-list`;
  const optionId = useId();
  const [open, setOpen] = useState(true);
  const [active, setActive] = useState(-1);
  const shown = (results ?? []).slice(0, PICKER_LIMIT);
  const typed = search.text.trim() !== '';
  const expanded = open && typed;
  const status = pickerStatus({ text: search.text, term: search.term, pending: search.pending, fetching, count: results ? shown.length : undefined, more: (results?.length ?? 0) > PICKER_LIMIT });

  // A new answer starts with nothing highlighted, so Enter never picks an item the person has not seen.
  const answer = results ? results.map(optionKey).join('|') : null;
  useEffect(() => setActive(-1), [search.term, answer]);
  useEffect(() => {
    if (active >= 0) document.getElementById(`${optionId}-${active}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, optionId]);

  const pick = (item: T) => {
    onPick(item);
    search.clear();
    setActive(-1);
    input.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setOpen(true);
      if (shown.length === 0) return;
      setActive((a) => (e.key === 'ArrowDown' ? Math.min(a + 1, shown.length - 1) : Math.max(a - 1, 0)));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      // Enter picks only from a settled answer: with more typed and the search still on its way, it settles the search.
      const item = expanded && active >= 0 && !search.pending && !fetching ? shown[active] : undefined;
      if (item !== undefined) pick(item);
      else search.flush();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      if (expanded) setOpen(false);
      else search.clear();
      setActive(-1);
    }
  };

  return (
    <div
      className="sb cb"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpen(false);
      }}
    >
      <label htmlFor={id}>{label}</label>
      <div className="cb-anchor">
        <div className="sb-field">
          <input
            id={id}
            ref={input}
            {...search.inputProps}
            role="combobox"
            aria-expanded={expanded}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={expanded && active >= 0 ? `${optionId}-${active}` : undefined}
            aria-describedby={`${id}-status`}
            onChange={(e) => {
              search.setText(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={onKeyDown}
          />
          {search.text !== '' && (
            <button type="button" className="sb-clear" aria-label="Clear search" onClick={() => { search.clear(); input.current?.focus(); }}>
              Clear
            </button>
          )}
        </div>
        {expanded && (
          <div className="cb-pop" aria-busy={fetching || search.pending}>
            <ul id={listId} role="listbox" aria-label="Matching items" className="cb-list">
              {shown.map((item, i) => (
                <li
                  key={optionKey(item)}
                  id={`${optionId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className="cb-option"
                  // The press keeps the cursor in the box, so the list does not close before the tap lands.
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseMove={() => setActive(i)}
                  onClick={() => pick(item)}
                >
                  {renderOption(item)}
                </li>
              ))}
            </ul>
            {error}
            {results && shown.length === 0 && !search.pending && !fetching && <p className="cb-empty">{`No item matches "${search.term}".`}</p>}
            {(!results || search.pending || fetching) && shown.length === 0 && <p className="cb-empty">Searching</p>}
          </div>
        )}
      </div>
      {!expanded && !typed && hint && <p className="hint">{hint}</p>}
      <p id={`${id}-status`} className="sb-status" role="status" aria-live="polite">
        {status}
      </p>
    </div>
  );
}
