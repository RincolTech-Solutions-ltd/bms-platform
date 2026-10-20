import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { RETAIL_CURRENCY, businessToday, retail, type Product, type VariantLine } from '../../../api/retail';
import { MAX_VARIANTS, planVariants, repeatedCodes, splitOptions, variantKey, variantLabel, type PlannedVariant } from '../../../api/retail-variants';
import { parseMinor } from '../../../components/money';
import { useStaff } from '../context';
import { Gate, Problem, money, useProfitAccess } from './ui';

// Items with sizes and colours (issue #384, ADR-051): the owner of a baby clothes shop chooses the sizes and the prints once and
// every combination becomes a variant with its own code, price and stock. A variant is an item of its own everywhere else (sale,
// restock, stock-take, move stock, returns), named with its size and colour. This file holds the screen that makes a new item with
// sizes, the panel that gives an existing item sizes, the preview both show, and the hooks that tell the grouped lists whether to
// offer the "Size and colour" level. The item's own page is catalogue-family.tsx.

export const SIZES_NEW = '/staff/retail/catalogue/products/sizes/new';
export const familyPath = (id: string) => `/staff/retail/catalogue/products/sizes/${id}`;

/** True when the business has an item with sizes and colours: the sales lists then offer the "Size and colour" level. */
export function useSalesVariantLevel(): boolean {
  const today = businessToday();
  const levels = useQuery({
    queryKey: ['retail', 'sales', 'summary', 'variant-level'],
    queryFn: ({ signal }) => retail.salesSummary({ level: 'day', chain: ['day'], from: today, to: today, signal }),
    staleTime: 300_000,
    retry: 1,
  });
  return (levels.data?.available_levels ?? []).includes('variant');
}

/** The same for Past restocks, read from the restocks summary so a person who cannot read sales is asked nothing of sales. */
export function useRestockVariantLevel(enabled = true): boolean {
  const today = businessToday();
  const levels = useQuery({
    queryKey: ['retail', 'purchases', 'summary', 'variant-level'],
    queryFn: ({ signal }) => retail.purchasesSummary({ level: 'day', chain: ['day'], from: today, to: today, signal }),
    enabled,
    staleTime: 300_000,
    retry: 1,
  });
  return (levels.data?.available_levels ?? []).includes('variant');
}

/** A variant's size and colour on its card, with the way to the item's page. */
export function VariantNote({ product }: { product: Product }) {
  const v = product.variant;
  if (!v?.family_id) return null;
  return (
    <p className="hint">
      <span className="badge badge-info">{variantLabel(v.size, v.colour)}</span>{' '}
      <Link to={familyPath(v.family_id) as never} aria-label={`All sizes and colours of ${v.family_name ?? 'this item'}`}>All sizes and colours</Link>
    </p>
  );
}

/** One row of the preview as the owner edits it: the code, the selling price and (with cost read) the cost, as typed. */
export interface PreviewRow extends PlannedVariant {
  codeText: string;
  sellText: string;
  costText: string;
}

const rowsOf = (plan: PlannedVariant[]): PreviewRow[] => plan.map((p) => ({ ...p, codeText: p.code, sellText: String(p.sellMinor), costText: String(p.costMinor) }));

/** What the server is sent: every row with its own code and prices, so the preview the owner checked is exactly what is made. */
export function linesOf(rows: readonly PreviewRow[], profit: boolean): VariantLine[] {
  return rows.map((r) => ({
    ...(r.size ? { size: r.size } : {}),
    ...(r.colour ? { colour: r.colour } : {}),
    code: r.codeText.trim(),
    sell_minor: parseMinor(r.sellText, RETAIL_CURRENCY) ?? 0,
    ...(profit ? { cost_minor: parseMinor(r.costText, RETAIL_CURRENCY) ?? 0 } : {}),
  }));
}

/** Why the preview cannot be saved yet, in plain words, or null when it can. */
export function previewProblem(rows: readonly PreviewRow[]): string | null {
  if (rows.length === 0) return 'Type at least one size or one colour.';
  if (rows.length > MAX_VARIANTS) return `That makes ${rows.length}. Make at most ${MAX_VARIANTS} at a time, then add the rest on the item's page.`;
  if (rows.some((r) => r.codeText.trim() === '')) return 'Every size and colour needs a code.';
  if (rows.some((r) => parseMinor(r.sellText, RETAIL_CURRENCY) === null)) return 'Every size and colour needs a selling price.';
  const repeated = repeatedCodes(rows.map((r) => ({ code: r.codeText })));
  if (repeated.length > 0) return `Two of them would have the code ${repeated[0]}. Change one of the codes.`;
  return null;
}

/**
 * The list of what will be made, each row with its code and prices the owner may change. The rows follow the sizes and colours
 * typed; a code or price the owner changed is kept while the same size and colour stays in the list.
 */
export function usePreview(plan: PlannedVariant[]) {
  const [edits, setEdits] = useState<Record<string, Partial<Pick<PreviewRow, 'codeText' | 'sellText' | 'costText'>>>>({});
  const rows = rowsOf(plan).map((r) => ({ ...r, ...(edits[variantKey(r.size, r.colour)] ?? {}) }));
  const edit = (r: PreviewRow, change: Partial<Pick<PreviewRow, 'codeText' | 'sellText' | 'costText'>>) =>
    setEdits((e) => ({ ...e, [variantKey(r.size, r.colour)]: { ...(e[variantKey(r.size, r.colour)] ?? {}), ...change } }));
  return { rows, edit, reset: () => setEdits({}) };
}

export function VariantPreview({ rows, profit, onEdit }: { rows: PreviewRow[]; profit: boolean; onEdit: (r: PreviewRow, change: Partial<Pick<PreviewRow, 'codeText' | 'sellText' | 'costText'>>) => void }) {
  if (rows.length === 0) return <p className="empty-state">Type the sizes or the colours above and the list of what will be made shows here.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }}>
      {rows.map((r) => {
        const label = variantLabel(r.size, r.colour);
        const id = `variant-${r.position}`;
        return (
          <li key={variantKey(r.size, r.colour)} className="rt-card">
            <p><strong>{label}</strong></p>
            <div className="rt-row">
              <div>
                <label htmlFor={`${id}-code`}>Code</label>
                <input id={`${id}-code`} value={r.codeText} maxLength={40} autoComplete="off" onChange={(e) => onEdit(r, { codeText: e.target.value })} aria-label={`Code of ${label}`} />
              </div>
              <div>
                <label htmlFor={`${id}-sell`}>Selling price ({RETAIL_CURRENCY})</label>
                <input id={`${id}-sell`} inputMode="numeric" value={r.sellText} autoComplete="off" onChange={(e) => onEdit(r, { sellText: e.target.value })} aria-label={`Selling price of ${label}`} aria-invalid={parseMinor(r.sellText, RETAIL_CURRENCY) === null} />
              </div>
              {profit && (
                <div>
                  <label htmlFor={`${id}-cost`}>Cost price ({RETAIL_CURRENCY})</label>
                  <input id={`${id}-cost`} inputMode="numeric" value={r.costText} autoComplete="off" onChange={(e) => onEdit(r, { costText: e.target.value })} aria-label={`Cost price of ${label}`} aria-invalid={parseMinor(r.costText, RETAIL_CURRENCY) === null} />
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** The two boxes where the owner types the sizes and the colours once. */
export function OptionBoxes({ sizes, colours, onSizes, onColours, prefix }: { sizes: string; colours: string; onSizes: (v: string) => void; onColours: (v: string) => void; prefix: string }) {
  return (
    <>
      <label htmlFor={`${prefix}-sizes`}>Sizes</label>
      <input id={`${prefix}-sizes`} value={sizes} onChange={(e) => onSizes(e.target.value)} autoComplete="off" placeholder="0-3m, 3-6m, 6-12m" />
      <p className="hint">Separate them with commas, smallest first. Leave empty if it comes in one size.</p>
      <label htmlFor={`${prefix}-colours`}>Colours or prints</label>
      <input id={`${prefix}-colours`} value={colours} onChange={(e) => onColours(e.target.value)} autoComplete="off" placeholder="Blue, Pink, Stars" />
      <p className="hint">Separate them with commas. Leave empty if it comes in one colour.</p>
    </>
  );
}

/** Add an item with sizes and colours: the name, a short code, category and unit, the prices, then the sizes and colours once. */
export function NewFamilyPage() {
  const profit = useProfitAccess();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const [name, setName] = useState('');
  const [baseCode, setBaseCode] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [unitId, setUnitId] = useState('');
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('0');
  const [sizes, setSizes] = useState('');
  const [colours, setColours] = useState('');
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = profit ? parseMinor(cost, RETAIL_CURRENCY) : 0;
  const plan = planVariants({ baseCode: baseCode.trim() || 'ITEM', sizes: splitOptions(sizes), colours: splitOptions(colours), costMinor: costMinor ?? 0, sellMinor: sellMinor ?? 0 });
  const preview = usePreview(plan);
  const problem = previewProblem(preview.rows);
  const complete = name.trim() !== '' && baseCode.trim() !== '' && categoryId !== '' && unitId !== '' && sellMinor !== null && costMinor !== null && problem === null;
  const sending = useRef(false);
  const save = useMutation({
    mutationFn: () => retail.createFamily({
      name: name.trim(), base_code: baseCode.trim(), category_id: categoryId, unit_id: unitId, sell_minor: sellMinor ?? 0, cost_minor: costMinor ?? 0,
      sizes: splitOptions(sizes), colours: splitOptions(colours), variants: linesOf(preview.rows, profit),
    }),
    onSuccess: (family) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: familyPath(family.id ?? '') as never });
    },
    onSettled: () => { sending.current = false; },
  });
  const submit = () => {
    if (!complete || save.isPending || sending.current) return;
    sending.current = true;
    save.mutate();
  };
  return (
    <Gate screen="products" title="Add an item with sizes and colours">
      <p><Link to={'/staff/retail/catalogue/products' as never}>Back to Items</Link></p>
      <p>Use this for something you sell in several sizes, colours or prints, such as a romper in 0-3m, 3-6m and 6-12m. Each size and colour gets its own code, price and stock, and you choose them once here.</p>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <div data-tour="sizes-name">
          <label htmlFor="sizes-item-name">What is it called?</label>
          <input id="sizes-item-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoComplete="off" placeholder="Baby romper" />
          <label htmlFor="sizes-item-code">Short code</label>
          <input id="sizes-item-code" value={baseCode} maxLength={24} onChange={(e) => setBaseCode(e.target.value)} autoComplete="off" placeholder="ROMP" />
          <p className="hint">Every size and colour gets a code that starts with this, such as ROMP-03M-BLUE. You can change each one below.</p>
          <label htmlFor="sizes-item-category">Category</label>
          <select id="sizes-item-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">Choose a category</option>
            {(categories.data ?? []).filter((c) => c.active !== false).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <label htmlFor="sizes-item-unit">Unit</label>
          <select id="sizes-item-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
            <option value="">Choose a unit</option>
            {(units.data ?? []).filter((u) => u.active !== false).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select>
          <label htmlFor="sizes-item-sell">Selling price ({RETAIL_CURRENCY})</label>
          <input id="sizes-item-sell" inputMode="numeric" value={sell} onChange={(e) => setSell(e.target.value)} autoComplete="off" aria-invalid={sell.trim() !== '' && sellMinor === null} />
          {profit && (
            <>
              <label htmlFor="sizes-item-cost">Cost price ({RETAIL_CURRENCY})</label>
              <input id="sizes-item-cost" inputMode="numeric" value={cost} onChange={(e) => setCost(e.target.value)} autoComplete="off" aria-invalid={costMinor === null} />
              <p className="hint">Leave 0 if you do not know it yet. A restock sets the cost.</p>
            </>
          )}
          <p className="hint">Every size and colour starts at these prices. Change any of them in the list below, for example when a bigger size costs more.</p>
        </div>
        <div data-tour="sizes-options">
          <OptionBoxes prefix="sizes-item" sizes={sizes} colours={colours} onSizes={setSizes} onColours={setColours} />
        </div>
        <section data-tour="sizes-preview" aria-label="What will be made">
          <h2>{preview.rows.length === 0 ? 'What will be made' : `${preview.rows.length} to be made`}</h2>
          <VariantPreview rows={preview.rows} profit={profit} onEdit={preview.edit} />
        </section>
        {problem && preview.rows.length > 0 && <p role="note" className="alert alert-warning">{problem}</p>}
        <Problem error={save.error} />
        <button type="submit" className="rt-primary" data-tour="sizes-save" disabled={!complete || save.isPending}>
          {save.isPending ? 'Saving' : preview.rows.length > 0 ? `Make these ${preview.rows.length}` : 'Make them'}
        </button>
      </form>
    </Gate>
  );
}

/**
 * Give an item without sizes its sizes and colours (FR-RET-51). The stock it holds now is one of them, which the owner names; it
 * keeps its code, prices, stock and history, and the other combinations are made at its prices.
 */
export function MakeFamilyPanel({ product }: { product: Product }) {
  const { me } = useStaff();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [size, setSize] = useState('');
  const [colour, setColour] = useState('');
  const [sizes, setSizes] = useState('');
  const [colours, setColours] = useState('');
  const canManage = (me.permissions ?? []).includes('retail.catalogue.manage');
  const own = { size: size.trim() || undefined, colour: colour.trim() || undefined };
  const plan = planVariants({
    baseCode: product.code ?? '', sizes: [own.size ?? '', ...splitOptions(sizes)].filter((s) => s !== ''), colours: [own.colour ?? '', ...splitOptions(colours)].filter((s) => s !== ''),
    costMinor: product.cost_minor ?? 0, sellMinor: product.sell_minor ?? 0, existing: new Set([variantKey(own.size, own.colour)]), nextPosition: 1,
  });
  const save = useMutation({
    mutationFn: () => retail.makeFamily(product.id ?? '', { ...(own.size ? { size: own.size } : {}), ...(own.colour ? { colour: own.colour } : {}), sizes: [own.size ?? '', ...splitOptions(sizes)].filter((s) => s !== ''), colours: [own.colour ?? '', ...splitOptions(colours)].filter((s) => s !== '') }),
    onSuccess: (family) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: familyPath(family.id ?? '') as never });
    },
  });
  if (!canManage || product.variant) return null;
  if (!open) return <p><button type="button" onClick={() => setOpen(true)}>Give this item sizes and colours</button></p>;
  const ready = (own.size !== undefined || own.colour !== undefined) && !save.isPending;
  return (
    <section className="rt-card" aria-label="Give this item sizes and colours">
      <h3>Give this item sizes and colours</h3>
      <p className="hint">The stock you hold now becomes one of them. It keeps its code, prices, stock and history. The others start with no stock, at its prices.</p>
      <label htmlFor="make-size">Size of the stock you hold now</label>
      <input id="make-size" value={size} maxLength={30} onChange={(e) => setSize(e.target.value)} autoComplete="off" placeholder="0-3m" />
      <label htmlFor="make-colour">Colour or print of the stock you hold now</label>
      <input id="make-colour" value={colour} maxLength={30} onChange={(e) => setColour(e.target.value)} autoComplete="off" placeholder="Blue" />
      <OptionBoxes prefix="make" sizes={sizes} colours={colours} onSizes={setSizes} onColours={setColours} />
      <p>{plan.length === 0 ? 'Nothing else will be made yet.' : `Also made, at ${money(product.sell_minor ?? 0)}: ${plan.map((p) => `${variantLabel(p.size, p.colour)} (${p.code})`).join(', ')}.`}</p>
      <Problem error={save.error} />
      <button type="button" className="rt-primary" disabled={!ready} onClick={() => save.mutate()}>{save.isPending ? 'Saving' : 'Give it sizes and colours'}</button>
      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
    </section>
  );
}

export const NewFamilyRoute = createLazyRoute(SIZES_NEW)({ component: NewFamilyPage });
