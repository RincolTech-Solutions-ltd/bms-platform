import { useQueries } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactElement, type Ref } from 'react';
import { retail, type EntryNorms } from '../../../api/retail';
import { icons } from '../../../components/icons';
import type { EntryScreen, EntryWarning } from './entry-checks-state';

// The entry warnings on Buy stock, Sell, Move stock and Count stock (issue #353, ADR-093). Each item's history is read once
// (GET /retail/products/{id}/entry-norms, kept for five minutes); a failed or slow read means no warning, never a wait. Saving
// asks `check` first: with no open warning it saves at once; otherwise the warnings show next to their fields, each with
// Confirm (recorded, and once the last is confirmed the save goes on) and Change (the warning goes, the cursor goes to the
// field, nothing is saved). The save button stays enabled throughout: nothing here blocks.

/** The history of each item on the screen, by id; undefined while it loads, when it failed, or for no id. */
export function useEntryNorms(productIds: readonly (string | undefined)[]): (id: string | undefined) => EntryNorms | undefined {
  const ids = [...new Set(productIds.filter((id): id is string => !!id))];
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['retail', 'entry-norms', id],
      queryFn: () => retail.entryNorms(id),
      staleTime: 5 * 60_000,
      retry: false,
    })),
  });
  const byId = new Map(ids.map((id, i) => [id, results[i]?.data]));
  return (id) => (id ? byId.get(id) : undefined);
}

export interface EntryChecks {
  /** Saves at once when every warning is confirmed; otherwise shows the open ones and saves after the last Confirm. */
  check: (warnings: EntryWarning[], save: () => void) => void;
  /** The warnings showing for one line, as notes with Confirm and Change. */
  notes: (lineKey: string) => ReactElement | null;
  /** True while a warning waits for an answer. */
  waiting: boolean;
}

export function useEntryChecks(screen: EntryScreen, branchId?: string): EntryChecks {
  const [shown, setShown] = useState<EntryWarning[]>([]);
  const confirmed = useRef(new Set<string>());
  const pending = useRef<(() => void) | null>(null);
  const first = useRef<HTMLDivElement | null>(null);
  // The first warning comes into view when it shows, so a warning on a line far up the form is seen.
  useEffect(() => {
    if (shown.length > 0) first.current?.scrollIntoView?.({ block: 'center' });
  }, [shown]);

  const check = (warnings: EntryWarning[], save: () => void) => {
    const open = warnings.filter((w) => !confirmed.current.has(w.key));
    if (open.length === 0) {
      pending.current = null;
      setShown([]);
      save();
      return;
    }
    pending.current = save;
    setShown(open);
  };
  const confirm = (w: EntryWarning) => {
    confirmed.current.add(w.key);
    // Recorded for the owner's review; a failed record never stops the save.
    void retail.confirmEntryWarning({ ...w.confirmation, screen, ...(branchId ? { branch_id: branchId } : {}) }).catch(() => undefined);
    const rest = shown.filter((x) => x.key !== w.key);
    setShown(rest);
    if (rest.length === 0 && pending.current) {
      const save = pending.current;
      pending.current = null;
      save();
    }
  };
  const change = (w: EntryWarning) => {
    pending.current = null;
    setShown((s) => s.filter((x) => x.key !== w.key));
    document.getElementById(w.fieldId)?.focus();
  };
  const notes = (lineKey: string) => {
    const mine = shown.filter((w) => w.lineKey === lineKey);
    if (mine.length === 0) return null;
    return (
      <>
        {mine.map((w) => (
          <EntryWarningNote key={w.key} warning={w} onConfirm={() => confirm(w)} onChange={() => change(w)}
            noteRef={w.key === shown[0]?.key ? first : undefined} />
        ))}
      </>
    );
  };
  return { check, notes, waiting: shown.length > 0 };
}

/** One warning next to its field: a tinted note in plain words, Confirm and Change. */
export function EntryWarningNote({ warning, onConfirm, onChange, noteRef }: {
  warning: EntryWarning;
  onConfirm: () => void;
  onChange: () => void;
  noteRef?: Ref<HTMLDivElement>;
}) {
  return (
    <div ref={noteRef} role="alert" className="alert alert-warning entry-warning" data-testid={`entry-warning-${warning.kind}`}>
      <span className="entry-warning-icon" aria-hidden="true">{icons.error}</span>
      <div>
        <p>{warning.text}</p>
        <div className="entry-warning-actions">
          <button type="button" onClick={onConfirm}>Confirm</button>
          <button type="button" onClick={onChange}>Change</button>
        </div>
      </div>
    </div>
  );
}
