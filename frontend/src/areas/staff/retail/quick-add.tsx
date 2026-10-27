import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type QuickAdd, type QuickAddRequest, type Unit } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { WORDS } from '../../../app/glossary';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { parseMinor } from '../../../components/money';
import { useOnline } from '../../../offline/offline-context';
import { useStaff } from '../context';
import { CategoryPicker } from './category-picker';
import { ExistsPanel, existingOf, type ExistingItem } from './catalogue-names';
import { newIdempotencyKey } from './idempotency';
import { PhotoChoice, sendNewPhoto } from './item-photo';
import { parseCount, qtyString, showQty } from './maths';
import { branchesWhere, canUse, holdsAt } from './permissions';
import { UnitPicker } from './unit-picker';
import { Gate, Problem, money, useProfitAccess, useStockingBranch } from './ui';

// Quick add (issue #632): a shop owner needed nine steps on four screens to add one item and its stock, and said AppSheet was
// still easier. One form and one tap: the name, the selling price, the buying price (optional), how many there are here now,
// where (the branch in use, or a store), the category (left out, the item goes under General) and the unit (the shop's piece
// unit), and a photo if wanted. Add makes the item and adds the quantity to its stock at that branch as opening stock, in one
// request (POST /retail/quick-add, ADR-101), so no other item's stock is touched and it is never profit. A name already in the
// list offers to use that item and just add the quantity to its stock. Nothing is kept for later: offline, the form says it needs the internet, as Add it here does.

export const QUICK_ADD = 'Quick add';
export const QUICK_ADD_OFFLINE = 'You are offline. Quick add needs the internet. Connect, then tap Add again.';
export const NO_PLACE = 'You have no branch where you can add stock.';
export const NO_COUNT_HERE = 'The item is added with no stock. Ask your manager to add its stock.';

const COUNT = 'retail.stocktake.commit';
/** The piece unit a shop may already have, lower case, in the order the server prefers them. */
const PIECE_NAMES = ['piece', 'pc', 'pcs'];

/** The shop's piece unit, which the form starts with; undefined when the shop has none yet (the server makes Piece). */
export function pieceUnit(units: Unit[]): Unit | undefined {
  const active = units.filter((u) => u.active !== false);
  return PIECE_NAMES.map((n) => active.find((u) => (u.name ?? '').trim().toLowerCase() === n)).find((u) => u !== undefined);
}

/** The line said once Add has worked: "Test bulb added: 600 pc at Bulb Store", or for an item already in the list what was added and what it has now. */
export function addedLine(done: QuickAdd, where: string): string {
  const name = done.description ?? 'The item';
  if (done.qty === undefined) return `${name} added.`;
  const of = (qty: string | undefined) => `${qty ?? ''} ${done.unit ?? ''}`.trim();
  return done.item_made ? `${name} added: ${of(done.qty)} at ${where}` : `${name}: ${of(done.qty)} added at ${where}, ${of(done.total_qty)} now`;
}

/** The fetch never reached the server: no status and no problem body. */
function unreachable(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof RetailError && error.status === 0);
}

/**
 * The server answered for certain that nothing was done: a 4xx refusal other than the key's own answers. A 5xx, a gateway timeout,
 * a lost answer, a key still being worked on or a key sent with another body may hide a request that went through.
 */
export function refusedForCertain(error: unknown): boolean {
  return error instanceof RetailError && error.status >= 400 && error.status < 500
    && error.code !== 'idempotency_in_progress' && error.code !== 'idempotency_key_reused';
}

export const NOT_SURE = 'We could not tell whether this was saved. Tap Add again: it sends exactly the same, and it is never added twice.';

export function QuickAddForm({ initialName = '', onSell }: {
  /** What the name starts with: on Sell's not-found, what was typed in the search. */
  initialName?: string;
  /** Opens Sell with the item on the sale, at the branch it was counted at. */
  onSell?: (productId: string, branchId: string) => void;
}) {
  const { me } = useStaff();
  const online = useOnline();
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const here = useStockingBranch();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  // Where the stock is: every branch that keeps goods where this person may count, stores too; the branch in use first.
  const places = branchesWhere(me, COUNT).filter((b) => b.id && b.stocks_goods !== false);
  const mayCount = places.length > 0;
  const [chosenWhere, setWhere] = useState<string | null>(null);
  // Until the person picks one, Where follows the branch in use, which may be known only after the first render.
  const where = chosenWhere ?? places.find((b) => b.id === here.branchId)?.id ?? places[0]?.id ?? '';
  const [name, setName] = useState(initialName.trim());
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('');
  const [qty, setQty] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [unitId, setUnitId] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [showErrors, setShowErrors] = useState(false);
  const [offline, setOffline] = useState(false);
  const [exists, setExists] = useState<ExistingItem | null>(null);
  const [done, setDone] = useState<{ added: QuickAdd; where: string; note: string | null } | null>(null);
  // One key per attempt, and the request sent with it. While the answer is not certain (lost, a 5xx, a timeout) both are kept and
  // Add sends exactly that request again, whatever was typed since, so a retry can never add the stock twice; a new key and a new
  // request only once the server has answered for certain (success, or a 4xx refusal).
  const key = useRef(newIdempotencyKey());
  const unsure = useRef<QuickAddRequest | null>(null);
  const [notSure, setNotSure] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const piece = pieceUnit(units.data ?? []);
  // A Piece or a General the owner switched off is never switched back on by the server: the person chooses one instead.
  const pieceOff = piece === undefined && (units.data ?? []).some((u) => u.active === false && (u.name ?? '').trim().toLowerCase() === 'piece');
  const generalOff = (categories.data ?? []).some((c) => c.active === false && (c.name ?? '').trim().toLowerCase() === 'general');
  // What was said when the person kept an item already in the list instead of a duplicate (someone who may not count).
  const [kept, setKept] = useState<string | null>(null);
  // The piece unit is chosen once the units are read, unless the person has chosen another.
  useEffect(() => {
    if (unitId === '' && piece?.id) setUnitId(piece.id);
  }, [piece?.id, unitId]);
  useEffect(() => { document.getElementById('qa-name')?.focus(); }, []);

  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = cost.trim() === '' ? 0 : parseMinor(cost, RETAIL_CURRENCY);
  const counted = mayCount ? parseCount(qty) : null;
  const complete = name.trim() !== '' && sellMinor !== null && costMinor !== null && (!mayCount || (counted !== null && where !== ''))
    && (!pieceOff || unitId !== '') && (!generalOff || categoryId !== '');
  const canAddAnyway = (me.permissions ?? []).includes('core.settings.manage');
  const whereName = branchLabel((me.branches ?? []).find((b) => b.id === where));

  const add = useMutation({
    mutationFn: async (extra: { productId?: string; duplicateReason?: string }) => {
      const body: QuickAddRequest = unsure.current ?? {
          ...(extra.productId
            ? { product_id: extra.productId }
            : {
                description: name.trim(),
                sell_minor: sellMinor ?? 0,
                ...(profit && costMinor ? { cost_minor: costMinor } : {}),
                ...(categoryId ? { category_id: categoryId } : {}),
                ...(unitId ? { unit_id: unitId } : {}),
                ...(extra.duplicateReason ? { duplicate_reason: extra.duplicateReason } : {}),
              }),
          ...(mayCount && counted !== null ? { qty: qtyString(counted), branch_id: where } : {}),
        };
      unsure.current = body;
      const added = await retail.quickAdd(body, key.current);
      // The photo after the item (#617): a failed upload leaves the item and says so under the line.
      const note = added.item_made ? await sendNewPhoto(added.product_id ?? '', photo) : null;
      return { added, note };
    },
    onSuccess: ({ added, note }) => {
      key.current = newIdempotencyKey();
      unsure.current = null;
      setNotSure(false);
      setExists(null);
      setDone({ added, where: whereName, note });
      // The first-run card, the item search, the stock lists and Home all read again with the new item.
      for (const k of [['retail', 'catalogue'], ['retail', 'products'], ['retail', 'stock'], ['retail', 'stocked-branches'], ['retail', 'home']]) {
        void queryClient.invalidateQueries({ queryKey: k });
      }
    },
    onError: (error) => {
      if (!refusedForCertain(error)) {
        // The request may have gone through: the key and the request are kept for the next tap.
        if (unreachable(error)) setOffline(true);
        else setNotSure(true);
        return;
      }
      // The server refused for certain, so the next attempt is a new request.
      key.current = newIdempotencyKey();
      unsure.current = null;
      setNotSure(false);
      const found = existingOf(error);
      if (found) setExists({ ...found, mayAddAnyway: found.mayAddAnyway && canAddAnyway });
    },
  });

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (add.isPending || exists !== null) return;
    setKept(null);
    if (!complete && unsure.current === null) {
      setShowErrors(true);
      if (form.current) focusFirstMissing(form.current);
      return;
    }
    // Read at the press: the browser's flag does not render the form again when the connection drops.
    if (!online || (typeof navigator !== 'undefined' && navigator.onLine === false)) return setOffline(true);
    setOffline(false);
    add.mutate({});
  };
  /** Add another: a clear form, keeping the category and where, so a run of items from one shelf is quick. */
  const another = () => {
    setDone(null);
    setName('');
    setSell('');
    setCost('');
    setQty('');
    setPhoto(null);
    setShowErrors(false);
    add.reset();
    setTimeout(() => document.getElementById('qa-name')?.focus());
  };

  if (done) {
    const sellsThere = (me.branches ?? []).some((b) => b.id === where && b.sells_goods !== false);
    // Sell it only where this person may sell: the sale waits for that branch to be the one in use.
    const canSell = onSell && canUse(me, 'sale') && holdsAt(me, 'retail.sale.create', where) && sellsThere && done.added.qty !== undefined && Number(done.added.qty) > 0;
    return (
      <section className="rt-card stack" aria-labelledby="qa-done">
        <p id="qa-done" role="status" className="alert alert-success" data-testid="qa-done">{addedLine(done.added, done.where)}</p>
        {done.note && <p className="hint" data-testid="qa-photo-note">{done.note}</p>}
        <p className="cluster">
          <button type="button" className="rt-primary" onClick={another}>Add another</button>
          {canSell && <button type="button" onClick={() => onSell(done.added.product_id ?? '', where)}>Sell it</button>}
        </p>
      </section>
    );
  }

  return (
    <form ref={form} className="stack" onSubmit={submit} noValidate aria-label={QUICK_ADD}>
      <FieldsNeededNote />
      <div className="stack" data-tour="quick-add-fields">
      <Field
        id="qa-name"
        label="Name"
        required
        error={name.trim() === '' ? 'Type what the item is.' : null}
        showError={showErrors}
        value={name}
        maxLength={300}
        onChange={(e) => { setName(e.target.value); setExists(null); }}
        autoComplete="off"
      />
      <Field
        id="qa-sell"
        label={`${WORDS.sellingPrice} (${RETAIL_CURRENCY})`}
        required
        error={sellMinor === null ? `Type the price you sell one at, in ${RETAIL_CURRENCY}.` : null}
        showError={showErrors}
        inputMode="numeric"
        value={sell}
        onChange={(e) => setSell(e.target.value)}
        autoComplete="off"
      />
      {profit && (
        <Field
          id="qa-cost"
          label={`${WORDS.buyingPrice} (${RETAIL_CURRENCY})`}
          optional
          error={costMinor === null ? `Type what you pay for one, in ${RETAIL_CURRENCY}, or leave it empty.` : null}
          showError={showErrors}
          hint={sellMinor !== null && costMinor !== null && costMinor > 0 && sellMinor <= costMinor ? `This is not below the selling price, ${money(sellMinor)}.` : 'Leave it empty if you do not know it yet; buying stock sets it.'}
          inputMode="numeric"
          value={cost}
          onChange={(e) => setCost(e.target.value)}
          autoComplete="off"
        />
      )}
      {mayCount ? (
        <>
          <Field
            id="qa-qty"
            label="How many do you have here now?"
            required
            error={counted === null ? 'Type how many you have, or 0.' : null}
            showError={showErrors}
            inputMode="decimal"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            autoComplete="off"
          />
          <Field
            id="qa-where"
            label="Where"
            required
            error={where === '' ? 'Choose where the stock is.' : null}
            showError={showErrors}
            control={(props) => (
              <select {...props} value={where} onChange={(e) => setWhere(e.target.value)}>
                {places.map((b) => (
                  <option key={b.id} value={b.id}>{branchLabel(b)}{b.sells_goods === false ? ' (store)' : ''}</option>
                ))}
              </select>
            )}
          />
        </>
      ) : (
        // No branch where this person may count (rule 25): the field says so and who to ask, and the item is added with no stock.
        <EmptyChoice id="qa-where" label="Where" text={NO_PLACE} ask={NO_COUNT_HERE} />
      )}
      </div>
      <div className="stack" data-tour="quick-add-more">
      <CategoryPicker
        id="qa-category"
        label={generalOff ? 'Category' : <>Category <span className="field-optional">(optional)</span></>}
        categories={(categories.data ?? []).filter((c) => c.active !== false)}
        value={categoryId}
        onChange={setCategoryId}
        {...(generalOff
          ? { required: true, error: categoryId === '' ? 'The General category is switched off. Choose a category.' : null, showError: showErrors }
          : { noneLabel: 'General', hint: 'Leave it to put the item under General.' })}
      />
      <UnitPicker
        id="qa-unit"
        units={(units.data ?? []).filter((u) => u.active !== false)}
        loading={units.data === undefined}
        load={units}
        value={unitId}
        onChange={(next) => { setUnitId(next); setExists(null); }}
        required={pieceOff}
        error={pieceOff && unitId === '' ? 'The Piece unit is switched off. Choose a unit.' : null}
        showError={showErrors}
      />
      {units.data !== undefined && piece === undefined && !pieceOff && unitId === '' && (
        <p className="hint" data-testid="qa-piece-made">Left empty, the item is counted in Piece, made for you.</p>
      )}
      <PhotoChoice id="qa-photo" file={photo} onChange={setPhoto} />
      </div>
      {exists && mayCount && counted !== null && (
        <p className="hint" data-testid="qa-adds-to">The {showQty(qtyString(counted))} you typed is added to the stock it already has there.</p>
      )}
      {exists && (
        <ExistsPanel
          item={exists}
          canAddAnyway={exists.mayAddAnyway}
          busy={add.isPending}
          openLabel={mayCount ? 'Use the existing one and just add stock' : 'Keep the existing one'}
          onOpen={(id) => {
            if (mayCount) return add.mutate({ productId: id });
            // Nothing to add for someone who may not count: the existing item stays as it is and the form is cleared, so Add
            // never sends the same name again.
            setKept(`${exists.name} is already in your items. Nothing new was added. To add stock to it, ask your manager.`);
            setExists(null);
            setName('');
            setSell('');
            setCost('');
            setPhoto(null);
            setShowErrors(false);
            setTimeout(() => document.getElementById('qa-name')?.focus());
          }}
          onAddAnyway={(reason) => add.mutate({ duplicateReason: reason })}
          onChangeName={() => { setExists(null); setTimeout(() => document.getElementById('qa-name')?.focus()); }}
        />
      )}
      {kept && <p role="status" className="hint" data-testid="qa-kept">{kept}</p>}
      {offline ? (
        <p role="alert" className="alert alert-warning" data-testid="qa-offline">{QUICK_ADD_OFFLINE}</p>
      ) : notSure ? (
        <p role="alert" className="alert alert-warning" data-testid="qa-not-sure">{NOT_SURE}</p>
      ) : (
        !exists && <Problem error={add.error} />
      )}
      <p className="cluster" data-tour="quick-add-go">
        <BusyButton busy={add.isPending} type="submit" className="rt-primary" disabled={exists !== null}>Add</BusyButton>
      </p>
    </form>
  );
}

function QuickAddScreen() {
  const { chooseBranch } = useStaff();
  const navigate = useNavigate();
  const { name } = useSearch({ strict: false }) as { name?: string };
  return (
    <Gate screen="quickAdd" title={QUICK_ADD}>
      <p>An item, its prices and how many you have, in one tap.</p>
      <QuickAddForm
        initialName={name}
        onSell={(productId, branchId) => {
          chooseBranch?.(branchId);
          void navigate({ to: '/staff/retail/sale', search: { item: productId, branch: branchId } as never });
        }}
      />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/quick-add')({ component: QuickAddScreen });
