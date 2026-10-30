import { useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { retail } from '../../../api/retail';
import { READY_COLOURS, SIZE_KINDS, cleanOption, distinctOptions, type SizeKind } from '../../../api/retail-variants';
import { HelpTip } from '../../../app/ui/HelpTip';
import { Hint } from '../../../app/ui/Hint';
import { Segmented } from '../../../app/ui/Segmented';
import { VariantPreview, previewProblem, type PreviewRow } from './catalogue-variants';

// Issue #697 (ADR-111, owner D81 and D98): Add an item asks whether the item comes in sizes, colours or types, in the same one
// form, and the sizes and colours are tapped from ready lists shipped with the app; a shop may still type its own, and what it
// typed before is offered again (GET /retail/product-families/options). An item with sizes is a group of products (ADR-051):
// one save makes the item, each size as its product and each size's packages. The combinations, codes and prices are those of
// retail-variants.ts and the server's VariantGrid; the price rows are the VariantPreview of the sizes screen.

export const SIZES_QUESTION = 'Does it come in different sizes, colours or types?';

// The packages question below has its own No and Yes, so a screen reader hears which question each answers.
const ANSWERS = [
  { value: 'no', label: <>No<span className="visually-hidden">, one size</span></> },
  { value: 'yes', label: <>Yes<span className="visually-hidden">, sizes, colours or types</span></> },
] as const;

export type SizesAnswer = { answer: 'no' | 'yes'; kind: SizeKind | null; sizes: string[]; colours: string[] };

export const NO_SIZES: SizesAnswer = { answer: 'no', kind: null, sizes: [], colours: [] };

/** The values chosen, in the order the chips show them, so the item's sizes keep the order of the list. */
export function inOrder(offered: readonly string[], chosen: readonly string[]): string[] {
  const picked = new Set(chosen.map((c) => c.toLowerCase()));
  return distinctOptions([...offered, ...chosen]).filter((o) => picked.has(o.toLowerCase()));
}

/** A tap adds a value or takes it off, ignoring case. */
export function toggled(chosen: readonly string[], value: string): string[] {
  return chosen.some((c) => c.toLowerCase() === value.toLowerCase()) ? chosen.filter((c) => c.toLowerCase() !== value.toLowerCase()) : [...chosen, value];
}

/** Why the sizes cannot be saved yet, in plain words, or null. An item with sizes has at least two. */
export function sizesProblem(rows: readonly PreviewRow[]): string | null {
  if (rows.length < 2) return 'Tap at least 2 sizes or colours.';
  return previewProblem(rows);
}

/** "in 3 sizes", "in 2 colours" or "in 6 sizes and colours": the middle of "Longtime bulb added in 3 sizes". */
export function inSizes(count: number, sizes: number, colours: number): string {
  return `in ${count} ${sizes > 0 && colours > 0 ? 'sizes and colours' : colours > 0 ? 'colours' : 'sizes'}`;
}

/** One group of big chips that each turn on and off. */
function Chips({ label, values, chosen, onTap }: { label: string; values: readonly string[]; chosen: readonly string[]; onTap: (value: string) => void }) {
  const on = new Set(chosen.map((c) => c.toLowerCase()));
  return (
    <div className="option-chips" role="group" aria-label={label}>
      {values.map((v) => (
        <button key={v} type="button" className="find-chip" aria-pressed={on.has(v.toLowerCase())} onClick={() => onTap(v)}>{v}</button>
      ))}
    </div>
  );
}

/** A box to type a size or colour that is not on the list, and its Add button; Enter adds it too, never saving the form. */
function OwnOption({ id, label, onAdd }: { id: string; label: string; onAdd: (value: string) => void }) {
  const [text, setText] = useState('');
  const add = () => {
    const value = cleanOption(text);
    if (value === undefined) return;
    onAdd(value.slice(0, 30));
    setText('');
  };
  return (
    <div className="rt-row">
      <div>
        <label htmlFor={id}>{label}</label>
        <input id={id} value={text} maxLength={30} autoComplete="off" onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
      </div>
      <div style={{ flex: '0 0 auto' }}>
        <button type="button" onClick={add}>Add</button>
      </div>
    </div>
  );
}

/** The question and, on Yes, the kind of size, the size chips and the colour chips. */
export function NewItemSizes({ value, onChange }: { value: SizesAnswer; onChange: (next: SizesAnswer) => void }) {
  const id = useId();
  const used = useQuery({
    queryKey: ['retail', 'catalogue', 'variant-options'],
    queryFn: () => retail.variantOptions(),
    enabled: value.answer === 'yes',
    staleTime: 60_000,
  });
  const kind = SIZE_KINDS.find((k) => k.key === value.kind);
  const ready: readonly string[] = kind?.sizes ?? [];
  const usedSizes = distinctOptions([...(used.data?.sizes ?? []), ...value.sizes]).filter((s) => !ready.some((r) => r.toLowerCase() === s.toLowerCase()));
  const colours = distinctOptions([...READY_COLOURS, ...(used.data?.colours ?? []), ...value.colours]);
  const set = (change: Partial<SizesAnswer>) => onChange({ ...value, ...change });
  return (
    <div className="stack" data-testid="item-sizes">
      <p className="field-label">{SIZES_QUESTION} <HelpTip tip="itemSizes" /></p>
      <Segmented label={SIZES_QUESTION} options={ANSWERS} value={value.answer} onChange={(answer) => set({ answer })} />
      {value.answer === 'yes' && (
        <>
          <p className="field-label" id={`${id}-kind`}>What kind of size?</p>
          <div className="option-chips" role="group" aria-labelledby={`${id}-kind`}>
            {SIZE_KINDS.map((k) => (
              <button key={k.key} type="button" className="find-chip" aria-pressed={value.kind === k.key} onClick={() => set({ kind: k.key, sizes: value.sizes.filter((s) => !(kind?.sizes as readonly string[] | undefined)?.some((r) => r.toLowerCase() === s.toLowerCase())) })}>{k.label}</button>
            ))}
          </div>
          {kind && kind.sizes.length > 0 && <Chips label="Sizes" values={kind.sizes} chosen={value.sizes} onTap={(s) => set({ sizes: toggled(value.sizes, s) })} />}
          {usedSizes.length > 0 && (
            <>
              <p className="hint">Your sizes</p>
              <Chips label="Your sizes" values={usedSizes} chosen={value.sizes} onTap={(s) => set({ sizes: toggled(value.sizes, s) })} />
            </>
          )}
          {kind && <OwnOption id={`${id}-own-size`} label={kind.key === 'other' ? 'Type a size or type' : 'Type another size'} onAdd={(s) => set({ sizes: value.sizes.some((c) => c.toLowerCase() === s.toLowerCase()) ? value.sizes : [...value.sizes, s] })} />}
          <p className="field-label">Colours</p>
          <Hint>Tap none if it comes in one colour.</Hint>
          <Chips label="Colours" values={colours} chosen={value.colours} onTap={(c) => set({ colours: toggled(value.colours, c) })} />
          <OwnOption id={`${id}-own-colour`} label="Type another colour" onAdd={(c) => set({ colours: value.colours.some((x) => x.toLowerCase() === c.toLowerCase()) ? value.colours : [...value.colours, c] })} />
        </>
      )}
    </div>
  );
}

/** The sizes and colours chosen for planVariants: those of the ready lists in their order, then the shop's own as tapped. */
export function chosenOptions(value: SizesAnswer) {
  if (value.answer === 'no') return { sizes: [], colours: [] };
  const kind = SIZE_KINDS.find((k) => k.key === value.kind);
  return { sizes: inOrder(kind?.sizes ?? [], value.sizes), colours: inOrder(READY_COLOURS, value.colours) };
}

/** The price of each size, under the item's own prices, which every size starts at. */
export function SizePrices({ rows, profit, onEdit, codesKnown, problem, showErrors }: {
  rows: PreviewRow[];
  profit: boolean;
  onEdit: Parameters<typeof VariantPreview>[0]['onEdit'];
  codesKnown: boolean;
  problem: string | null;
  showErrors: boolean;
}) {
  return (
    <section aria-label="Price of each size" data-testid="size-prices">
      <h3>{rows.length === 0 ? 'Price of each size' : `Price of each of the ${rows.length}`}</h3>
      <Hint full>Each one starts at the prices above. Change one if it costs more or less. A package of a dearer size costs more in the same way.</Hint>
      {rows.length > 0 && <VariantPreview rows={rows} profit={profit} onEdit={onEdit} codesKnown={codesKnown} />}
      {problem && (showErrors || rows.length > 0) && <p role="note" className="alert alert-warning">{problem}</p>}
    </section>
  );
}
