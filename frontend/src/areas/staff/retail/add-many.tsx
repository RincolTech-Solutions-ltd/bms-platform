import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type QuickAddList, type QuickAddListRequest } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { EntryState } from '../../../offline/entry-state';
import { OfflineFormNote, isKept, useEntrySave } from '../../../offline/keep-entry';
import type { OutboxItem } from '../../../offline/outbox';
import { AddsTo } from '../branch-picker';
import { useStaff } from '../context';
import {
  COLUMNS, checkRows, emptyRow, fillRows, groupsOf, isBlank, nameKey, nextKey, resultLine, rowsFromPaste, startRows,
  type ListRow, type RowCheck,
} from './add-many-state';
import { CategoryPicker } from './category-picker';
import { existsText, type ExistingItem } from './catalogue-names';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { branchesWhere } from './permissions';
import { NO_COUNT_HERE, NO_PLACE, notSureLine, pieceUnit, readLastGroup, refusedForCertain, writeLastGroup } from './quick-add';
import { ScreenLink } from './screen-link';
import { Gate, Problem, useProfitAccess, useStockingBranch } from './ui';
import { UnitPicker } from './unit-picker';

// Add many items (issue #870): a pilot shop owner stopped using the app after adding about 20 items one at a time. One screen of
// rows (name, group, unit, how many now at the shop chosen at the top, buying price, selling price), five to start and Add
// another row; Tab or Enter moves to the next cell; on a phone each row is a small card. Paste a list fills the rows from lines
// copied out of Excel, Google Sheets or WhatsApp, for the owner to check. Each row is checked where it is: a missing name, a
// name already in the items (Add to its stock instead), a number that is not one, a comma used as a decimal point; selling
// below buying is a warning only. Save the list sends every good row in one request (POST /retail/quick-add/list, through
// Quick add's own path, ADR-101) under one key, so it is never added twice; the rows that need a look stay on the screen.
// Offline the list is kept on the phone as one entry and sent once (ADR-067 decision 13). The buying price is entered only by
// whoever may see it, as on Quick add.

export const ADD_MANY = 'Add many items';
const COUNT = 'retail.stocktake.commit';
const SAVE = 'Save the list';

interface ListDraft {
  rows: ListRow[];
  /** The shop chosen at the top; null follows the branch in use. */
  where: string | null;
  /** The request sent, with its key, while its answer is not certain: Save sends exactly it again, under that key. */
  pending: Plan | null;
  /**
   * The list's one Idempotency-Key once a list was answered for certain on this screen: a new one after each answer, so the
   * next list is a new request. Absent, the draft's own key (a fresh one when Fix put the draft back).
   */
  listKey?: string;
}

/** One save: the request, the rows it carries (by row key) and the key it goes under. */
interface Plan { body: QuickAddListRequest; keys: number[]; key: string }

/** The name panel's state of a row: the item already in the list under that name, for the name it was found for. */
type Found = Record<number, { forName: string; item: ExistingItem }>;

export function AddManyForm() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const here = useStockingBranch();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const places = branchesWhere(me, COUNT).filter((b) => b.id && b.stocks_goods !== false);
  const mayCount = places.length > 0;
  const { key: draftKey, draft, setDraft, discard, name: draftName } = usePersistedDraft<ListDraft>(
    `add-many:${me.user_id ?? ''}`,
    { rows: startRows(), where: null, pending: null },
  );
  const entries = useEntrySave();
  const where = draft.where ?? places.find((b) => b.id === here.branchId)?.id ?? places[0]?.id ?? '';
  const whereName = places.find((b) => b.id === where)?.name ?? 'this shop';
  const active = (categories.data ?? []).filter((c) => c.active !== false);
  const switchedOn = (units.data ?? []).filter((u) => u.active !== false);
  const piece = pieceUnit(units.data ?? []);
  const lastSaved = readLastGroup(me.user_id ?? '', where || here.branchId || '') ?? '';
  const last = active.some((c) => c.id === lastSaved) ? lastSaved : '';
  const groups = groupsOf(draft.rows, last);
  const [found, setFound] = useState<Found>({});
  const [showErrors, setShowErrors] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [keptItem, setKeptItem] = useState<OutboxItem | null>(null);
  const [notSure, setNotSure] = useState(false);
  const [refusal, setRefusal] = useState<unknown>(null);
  const [paste, setPaste] = useState('');
  const [pasteNote, setPasteNote] = useState<string | null>(null);
  const list = useRef<HTMLOListElement>(null);
  const held = draft.pending !== null;

  const checksWith = (map: Found): RowCheck[] => checkRows(draft.rows, { groups, pieceId: piece?.id, profit, mayCount }).map((c, i) => {
    const r = draft.rows[i];
    const hit = r && !r.addTo ? map[r.key] : undefined;
    // A name already in the items is not saved twice: the row asks to add to its stock, or a new name.
    if (r && hit && hit.forName === nameKey(r.name) && !c.errors.name) return { ...c, errors: { ...c.errors, name: existsText(hit.item) }, send: undefined };
    return c;
  });
  const checks = checksWith(found);
  const filled = draft.rows.filter((r) => !isBlank(r)).length;
  const ready = checks.filter((c) => c.send).length;

  const change = (rowKey: number, patch: Partial<ListRow>) => {
    setResult(null);
    setDraft((d) => ({ ...d, rows: d.rows.map((r) => (r.key === rowKey ? { ...r, ...patch, refused: undefined } : r)) }));
  };

  /** The item already in the list under the row's name and unit, read from the server; nothing offline. */
  const lookUp = async (r: ListRow, map: Found): Promise<Found> => {
    const text = r.name.trim();
    if (text === '' || r.addTo || map[r.key]?.forName === nameKey(text) || entries.offlineMode) return map;
    try {
      const same = await retail.similarProducts({ description: text, unitId: r.unitId || piece?.id });
      const hit = (same.exact ?? [])[0];
      const next = { ...map };
      if (hit?.id) next[r.key] = { forName: nameKey(text), item: { id: hit.id, code: hit.code ?? '', name: hit.description ?? text, mayAddAnyway: false } };
      else delete next[r.key];
      return next;
    } catch {
      // Not known now: the server checks the name again when the list is saved.
      return map;
    }
  };
  const checkName = async (r: ListRow) => setFound(await lookUp(r, found));

  const addRow = () => {
    const k = nextKey(draft.rows);
    setDraft((d) => ({ ...d, rows: [...d.rows, emptyRow(k)] }));
    setTimeout(() => document.getElementById(`am-name-${k}`)?.focus());
  };
  const removeRow = (rowKey: number) => setDraft((d) => {
    const rows = d.rows.filter((r) => r.key !== rowKey);
    return { ...d, rows: rows.length > 0 ? rows : startRows(nextKey(d.rows)) };
  });

  /** Enter moves to the next cell, as Tab does; on the last cell it adds a row. */
  const onKey = (e: KeyboardEvent<HTMLElement>) => {
    const t = e.target;
    if (e.key !== 'Enter' || e.shiftKey || !(t instanceof HTMLInputElement) || t.dataset.cell === undefined) return;
    e.preventDefault();
    const cells = [...(list.current?.querySelectorAll<HTMLInputElement>('input[data-cell]') ?? [])];
    const next = cells[cells.indexOf(t) + 1];
    if (next) next.focus();
    else addRow();
  };

  const fill = () => {
    const pasted = rowsFromPaste(paste, { categories: categories.data ?? [], units: units.data ?? [], profit, firstKey: nextKey(draft.rows) });
    if (pasted.rows.length === 0) {
      setPasteNote('Nothing to fill. Paste one item on each line.');
      return;
    }
    setDraft((d) => ({ ...d, rows: fillRows(d.rows, pasted.rows) }));
    setPaste('');
    setShowErrors(true);
    setPasteNote(`${pasted.rows.length} ${pasted.rows.length === 1 ? 'line' : 'lines'} filled in. Check each row, then tap ${SAVE}.`
      + (pasted.droppedCost ? ' The buying prices were left out: only someone who sees buying prices enters them.' : ''));
  };

  const save = useMutation({
    // An entry kept on the phone is said by the toast itself (doneToasts); a saved list says how many were added.
    meta: { success: (done: QuickAddList) => resultLine((done?.rows ?? []).length, 0).replace(/\.$/, '') },
    mutationFn: async (plan: Plan) => {
      setDraft((d) => ({ ...d, pending: plan }));
      const count = plan.body.rows.length;
      return entries.save(
        {
          kind: 'items', body: plan.body as unknown as Record<string, unknown>, key: plan.key, branchId: where,
          detail: `${count} ${count === 1 ? 'item' : 'items'}`,
          // Fixed later, the list comes back without its spent key, so it is a new request.
          fix: { to: '/staff/retail/add-many', draftName, draft: { rows: draft.rows, where: draft.where, pending: null } },
        },
        () => retail.quickAddList(plan.body, plan.key),
      );
    },
    onSuccess: (answer, plan) => {
      const kept = isKept(answer);
      const sent = new Set(plan.keys);
      const left = draft.rows.filter((r) => !sent.has(r.key) && !isBlank(r));
      const needLook = left.length;
      // The group is remembered as on Quick add, once an item is added with it at that shop.
      const lastGroup = [...draft.rows].reverse().map((r) => (sent.has(r.key) ? groups[draft.rows.indexOf(r)] : '')).find(Boolean);
      if (lastGroup) writeLastGroup(me.user_id ?? '', where || here.branchId || '', lastGroup);
      // A new key for the next list: all saved, a fresh blank list; some left, those rows under a new key.
      if (left.length === 0) {
        discard();
        if (draft.where) setDraft((d) => ({ ...d, where: draft.where }));
      } else setDraft({ rows: left, where: draft.where, pending: null, listKey: newIdempotencyKey() });
      setNotSure(false);
      setRefusal(null);
      setShowErrors(needLook > 0);
      if (kept) {
        setKeptItem(answer.kept);
        setResult(`${plan.keys.length} ${plan.keys.length === 1 ? 'item' : 'items'} saved on this phone. They are sent when the phone is back online.`
          + (needLook > 0 ? ` ${needLook} ${needLook === 1 ? 'needs' : 'need'} a look.` : ''));
        return;
      }
      const done = answer as QuickAddList;
      setKeptItem(null);
      setResult(resultLine((done.rows ?? []).length, needLook));
      for (const k of [['retail', 'catalogue'], ['retail', 'products'], ['retail', 'stock'], ['retail', 'stocked-branches'], ['retail', 'home']]) {
        void queryClient.invalidateQueries({ queryKey: k });
      }
    },
    onError: (error, plan) => {
      if (!refusedForCertain(error)) {
        // It may have gone through: the key and the request are kept, every field is locked, Save sends exactly it again.
        setNotSure(true);
        return;
      }
      setNotSure(false);
      const problem = error instanceof RetailError ? error.problem ?? {} : {};
      const index = typeof problem['row_index'] === 'number' ? (problem['row_index'] as number) : -1;
      const rowKey = plan.keys[index];
      const field = String(((problem['errors'] as { field?: string }[] | undefined) ?? [])[0]?.field ?? '');
      const message = String(((problem['errors'] as { message?: string }[] | undefined) ?? [])[0]?.message ?? (error instanceof Error ? error.message : ''));
      // Nothing was saved: a new key for the next try, and the row the server named shows why.
      setDraft({
        rows: draft.rows.map((r) => (r.key === rowKey && !(error instanceof RetailError && error.code === 'duplicate_product_name') ? { ...r, refused: { field, message } } : r)),
        where: draft.where,
        pending: null,
        listKey: newIdempotencyKey(),
      });
      if (rowKey !== undefined && error instanceof RetailError && error.code === 'duplicate_product_name') {
        const r = draft.rows.find((x) => x.key === rowKey);
        if (r) setFound((m) => ({ ...m, [rowKey]: { forName: nameKey(r.name), item: { id: String(problem['existing_id'] ?? ''), code: String(problem['existing_code'] ?? ''), name: String(problem['existing_name'] ?? r.name), mayAddAnyway: false } } }));
      }
      setShowErrors(true);
      setResult(null);
      setRefusal(rowKey === undefined ? error : null);
      if (rowKey !== undefined) {
        setResult(`Nothing was saved. Row ${draft.rows.findIndex((r) => r.key === rowKey) + 1} needs a look.`);
      }
    },
  });

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    if (save.isPending) return;
    setRefusal(null);
    if (draft.pending) return save.mutate(draft.pending);
    let map = found;
    for (const r of draft.rows) if (!isBlank(r)) map = await lookUp(r, map);
    setFound(map);
    const now = checksWith(map);
    setShowErrors(true);
    const keys = draft.rows.flatMap((r, i) => (now[i]?.send ? [r.key] : []));
    if (keys.length === 0) {
      setResult(filled === 0 ? 'Type or paste at least one item.' : resultLine(0, filled));
      const first = draft.rows.find((r, i) => !isBlank(r) && Object.keys(now[i]?.errors ?? {}).length > 0);
      if (first) setTimeout(() => document.getElementById(`am-name-${first.key}`)?.focus());
      return;
    }
    const body: QuickAddListRequest = { ...(mayCount ? { branch_id: where } : {}), rows: draft.rows.flatMap((_, i) => (now[i]?.send ? [now[i].send] : [])) as QuickAddListRequest['rows'] };
    save.mutate({ body, keys, key: draft.listKey ?? draftKey });
  };

  return (
    <form className="stack am-form" onSubmit={(e) => void submit(e)} noValidate aria-label={ADD_MANY} onKeyDown={onKey}>
      {entries.offlineMode && <OfflineFormNote what="list" />}
      {mayCount ? (
        <AddsTo id="am-where" lead="Stock goes to" value={where} places={places} onChoose={held ? undefined : (id) => setDraft((d) => ({ ...d, where: id }))} />
      ) : (
        <EmptyChoice id="am-where" label="Where" text={NO_PLACE} ask={NO_COUNT_HERE.replace('The item is', 'The items are').replace('its stock', 'their stock')} />
      )}
      <fieldset disabled={held} className="form-lock stack">
        <details className="am-paste" data-tour="add-many-paste">
          <summary className="btn">Paste a list</summary>
          <div className="stack">
            <label htmlFor="am-paste">Paste lines from Excel, Google Sheets or WhatsApp, one item on each line</label>
            <textarea id="am-paste" rows={5} value={paste} onChange={(e) => { setPaste(e.target.value); setPasteNote(null); }} />
            <Hint full>{`Put a tab, a comma or two spaces between the columns, in this order: name, group, unit, how many, ${WORDS.buyingPrice.toLowerCase()}, ${WORDS.sellingPrice.toLowerCase()}. With fewer columns: ${columnsHint()}. With commas between columns, write numbers without commas.`}</Hint>
            <p className="cluster">
              <button type="button" className="btn" onClick={fill} disabled={paste.trim() === ''}>Fill the rows</button>
            </p>
          </div>
        </details>
        {pasteNote && <p role="status" className="hint" data-testid="am-paste-note">{pasteNote}</p>}
        <ol ref={list} className="am-rows" data-tour="add-many-rows" aria-label="Items">
          {draft.rows.map((r, i) => (
            <RowCard
              key={r.key}
              row={r}
              index={i}
              check={checks[i] ?? { errors: {} }}
              group={groups[i] ?? ''}
              categories={active}
              units={switchedOn.filter((u) => u.is_package !== true)}
              unitsLoad={units}
              pieceId={piece?.id ?? ''}
              profit={profit}
              mayCount={mayCount}
              whereName={whereName}
              showErrors={showErrors}
              found={found[r.key]?.forName === nameKey(r.name) ? found[r.key]?.item : undefined}
              onChange={(patch) => change(r.key, patch)}
              onNameDone={() => void checkName(r)}
              onRemove={() => removeRow(r.key)}
            />
          ))}
        </ol>
        <button type="button" className="btn am-another" onClick={addRow}>Add another row</button>
      </fieldset>
      {result && <p role="status" className={`alert ${result.startsWith('Nothing') || result.startsWith('0 ') ? 'alert-warning' : 'alert-success'}`} data-testid="am-result">{result}</p>}
      {keptItem && <EntryState item={keptItem} />}
      {notSure && <p role="alert" className="alert alert-warning" data-testid="am-not-sure">{notSureLine(SAVE)}</p>}
      <Problem error={refusal} />
      <ActionBar testId="am-save-bar">
        <p className="hint am-count" data-tour="add-many-save" data-testid="am-count">
          <span className="num">{ready}</span> ready{filled > ready ? <>, <span className="num">{filled - ready}</span> need a look</> : ''}
        </p>
        <BusyButton busy={save.isPending} type="submit" className="rt-primary">{SAVE}</BusyButton>
      </ActionBar>
    </form>
  );
}

/** "name, selling price" and the others, from COLUMNS. */
function columnsHint(): string {
  const words: Record<string, string> = { name: 'name', category: 'group', unit: 'unit', qty: 'how many', cost: 'buying', sell: 'selling' };
  return [2, 3, 4, 5].map((n) => (COLUMNS[n] ?? []).map((c) => words[c]).join(', ')).join('; or ');
}

function RowCard({ row: r, index, check, group, categories, units, unitsLoad, pieceId, profit, mayCount, whereName, showErrors, found, onChange, onNameDone, onRemove }: {
  row: ListRow;
  index: number;
  check: RowCheck;
  group: string;
  categories: Parameters<typeof CategoryPicker>[0]['categories'];
  units: Parameters<typeof UnitPicker>[0]['units'];
  unitsLoad: { isError: boolean; refetch: () => unknown; data?: unknown };
  pieceId: string;
  profit: boolean;
  mayCount: boolean;
  whereName: string;
  showErrors: boolean;
  found?: ExistingItem;
  onChange: (patch: Partial<ListRow>) => void;
  onNameDone: () => void;
  onRemove: () => void;
}) {
  const id = (f: string) => `am-${f}-${r.key}`;
  const e = check.errors;
  const blank = isBlank(r);
  const shown = showErrors && !blank;
  const label = `Row ${index + 1}`;
  return (
    <li className="am-row" aria-label={label} data-testid={`am-row-${index + 1}`}>
      <div className="am-row-head">
        <span className="am-row-no">{label}</span>
        <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove row ${index + 1}`}>Remove</button>
      </div>
      {e.row && shown && <p role="alert" className="field-error">{e.row}</p>}
      {r.addTo ? (
        <div className="stack am-adds-to">
          <p data-testid={`am-adds-to-${index + 1}`}>Adds to the stock of <strong>{r.addTo.name}</strong> ({r.addTo.code}) at {whereName}.</p>
          <p className="cluster">
            <button type="button" className="btn-ghost" onClick={() => onChange({ addTo: undefined })}>Make it a new item instead</button>
          </p>
        </div>
      ) : null}
      <div className="am-cells">
        {!r.addTo && (
          <Field
            id={id('name')}
            className="am-wide"
            label="Name"
            required
            value={r.name}
            maxLength={300}
            autoComplete="off"
            data-cell=""
            error={blank ? null : e.name ?? null}
            showError={shown || found !== undefined}
            onChange={(ev) => onChange({ name: ev.target.value })}
            onBlur={onNameDone}
          />
        )}
        {found && !r.addTo && (
          <div className="am-full stack" data-testid={`am-exists-${index + 1}`}>
            {mayCount && (
              <p className="cluster">
                <button type="button" className="btn" onClick={() => onChange({ addTo: { id: found.id, code: found.code, name: found.name } })}>Add to its stock instead</button>
              </p>
            )}
            {!mayCount && <p className="hint">Change the name, or remove this row: it is already in your items.</p>}
          </div>
        )}
        {!r.addTo && (
          <div className="am-wide">
            <CategoryPicker
              id={id('category')}
              label="Group"
              thing="group"
              placeholder="General"
              noneLabel="General"
              categories={categories}
              value={group}
              onChange={(next) => onChange({ categoryId: next, categoryChosen: true, pastedCategory: undefined })}
              error={e.category ?? null}
              showError={shown}
              hint={!r.categoryChosen && group ? (index === 0 ? 'The group you used last time.' : 'The same as the row above.') : undefined}
            />
          </div>
        )}
        {!r.addTo && (
          <div>
            <UnitPicker
              id={id('unit')}
              units={units}
              loading={unitsLoad.data === undefined}
              load={unitsLoad}
              value={r.unitId || pieceId}
              onChange={(next) => onChange({ unitId: next === pieceId ? '' : next, pastedUnit: undefined })}
              required={false}
              error={e.unit ?? null}
              showError={shown}
            />
          </div>
        )}
        {mayCount && (
          <Field
            id={id('qty')}
            className="am-num"
            label={r.addTo ? 'How many to add' : 'How many now'}
            required
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.qty}
            error={blank ? null : e.qty ?? null}
            showError={shown}
            onChange={(ev) => onChange({ qty: ev.target.value })}
          />
        )}
        {profit && !r.addTo && (
          <Field
            id={id('cost')}
            className="am-num"
            label={`${WORDS.buyingPrice} (${RETAIL_CURRENCY})`}
            optional
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.cost}
            error={blank ? null : e.cost ?? null}
            showError={shown}
            onChange={(ev) => onChange({ cost: ev.target.value })}
          />
        )}
        {!r.addTo && (
          <Field
            id={id('sell')}
            className="am-num"
            label={`${WORDS.sellingPrice} (${RETAIL_CURRENCY})`}
            required
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.sell}
            error={blank ? null : e.sell ?? null}
            showError={shown}
            hint={check.warning}
            onChange={(ev) => onChange({ sell: ev.target.value })}
          />
        )}
      </div>
    </li>
  );
}

function AddManyScreen() {
  return (
    <Gate screen="addMany" title={ADD_MANY}>
      <p>Type or paste your items, check each row, then save them all at once.</p>
      <p className="cluster"><ScreenLink screen="quickAdd" to="/staff/retail/quick-add" step>Add one item instead</ScreenLink></p>
      <AddManyForm />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/add-many')({ component: AddManyScreen });
