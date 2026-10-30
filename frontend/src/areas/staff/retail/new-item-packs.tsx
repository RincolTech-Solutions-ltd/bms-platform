import type { ReactNode } from 'react';
import { RetailError, type Unit } from '../../../api/retail';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { Segmented } from '../../../app/ui/Segmented';
import { icons } from '../../../components/icons';
import { PURPOSES, factorForUnit, purposeForUnit, usualFactor, type Purpose } from './catalogue-packs';
import { UnitPicker } from './unit-picker';
import { perPieceMinor, shown, type Rung } from './ladder-state';
import { money } from './ui';
import { LadderRungs, LadderSum, LookInside } from './package-ladder';
import { unitWord, unitWords } from './units-state';

// Issue #689 (ADR-106): Add an item asks for the smallest unit, then whether the item is bought or sold in packages, and
// takes the packages in the same form, so one save makes the item and its packs together. A package is a pack of
// retail_product_units (ADR-033, ADR-099), with its unit, its size, what it is for and an optional price of its own; nothing
// new is stored. The words follow the units chosen: "How many bulbs in one carton".

export const PACKAGES_QUESTION = 'Do you buy or sell it in boxes, cartons or other packages?';
export const USUAL_BUY_QUESTION = 'How do you usually buy it, and for how much?';

const ANSWERS = [{ value: 'no', label: 'No' }, { value: 'yes', label: 'Yes' }] as const;

/** Used for in the order the owner asked for on this form (issue #689): Both, Buying, Selling; the values of PURPOSES. */
const USED_FOR = (['both', 'buy', 'sell'] as const).map((v) => PURPOSES.find((o) => o.value === v)!);

export type PackageRow = {
  key: number;
  unitId: string;
  /** How many of what it holds are in one (issue #699): of the smallest unit, or of the package `holdsKey`. */
  factor: string;
  /** Issue #699 (ADR-107): the row this package holds, or 0 for the smallest unit. */
  holdsKey: number;
  purpose: Purpose;
  priceMinor: number | null;
  /** The size and Used for last filled in from the unit's usual pack (ADR-100), which another unit replaces. */
  filled: { factor: string; purpose: Purpose };
};

export function newPackageRow(key: number, holdsKey = 0): PackageRow {
  return { key, unitId: '', factor: '', holdsKey, purpose: 'both', priceMinor: null, filled: { factor: '', purpose: 'both' } };
}

/** Issue #699: the smallest units in one package of a row, worked out through what it holds; null until it can be. */
export function rowPieces(rows: PackageRow[], row: PackageRow, seen: Set<number> = new Set()): bigint | null {
  if (sizeProblem(row.factor) !== null || seen.has(row.key)) return null;
  const own = BigInt(row.factor.trim());
  if (row.holdsKey === 0) return own;
  const held = rows.find((r) => r.key === row.holdsKey);
  const inner = held ? rowPieces(rows, held, new Set([...seen, row.key])) : null;
  return inner === null ? null : own * inner;
}

/** Issue #699: the rows a package may hold: not itself, and not one that holds it, however deep. */
export function holdable(rows: PackageRow[], row: PackageRow): PackageRow[] {
  const holdsIt = (r: PackageRow, seen: Set<number> = new Set()): boolean => {
    if (r.holdsKey === 0 || seen.has(r.key)) return false;
    if (r.holdsKey === row.key) return true;
    const next = rows.find((x) => x.key === r.holdsKey);
    return next ? holdsIt(next, new Set([...seen, r.key])) : false;
  };
  return rows.filter((r) => r.key !== row.key && r.unitId !== '' && !holdsIt(r));
}

/** Issue #699: the rows as a ladder, biggest first, for the picture under them; a row not yet whole is left out. */
export function rowsAsRungs(rows: PackageRow[], units: Unit[], smallest: Unit | undefined): Rung[] {
  const name = (id: string) => units.find((u) => u.id === id)?.name ?? '';
  return rows
    .flatMap((r): Rung[] => {
      const pieces = rowPieces(rows, r);
      if (r.unitId === '' || pieces === null) return [];
      const held = rows.find((x) => x.key === r.holdsKey);
      const holdsUnitId = r.holdsKey === 0 || !held ? (smallest?.id ?? '') : held.unitId;
      return [{ unitId: r.unitId, unit: name(r.unitId), holdsUnitId, holdsUnit: r.holdsKey === 0 ? (smallest?.name ?? '') : name(holdsUnitId), holdsQty: r.factor.trim(), pieces: pieces * 1000n }];
    })
    .sort((a, b) => (a.pieces === b.pieces ? 0 : a.pieces > b.pieces ? -1 : 1));
}

/** "bulb" for a unit named Bulb, "piece" for Pc (the shop words of units-state.ts, #680), or `none` when no unit is chosen. */
export function oneOf(unit: Unit | undefined, none: string): string {
  return unit?.name?.trim() ? unitWord(unit.name.trim()).toLowerCase() : none;
}

/** "bulbs" for a unit named Bulb, "pieces" for Pc, or `none` when no unit is chosen. */
export function manyOf(unit: Unit | undefined, none: string): string {
  return unit?.name?.trim() ? unitWords(unit.name.trim()).toLowerCase() : none;
}

/**
 * The refusal of a package word as the smallest unit, the same sentence the server sends (422 unit_is_package,
 * RetailCatalogue.packageAsUnit): "A box is a package", or "Cartons is a package" for a unit named in the plural.
 */
export function packageAsUnit(unit: Unit | undefined): string | null {
  if (!unit?.is_package) return null;
  // Review of #692: in the shop's words (#680), as the rest of the form names units, so "Ctn" reads "a carton". The server's
  // sentence names the unit as it is spelt; this one is what the form shows before anything is sent.
  const word = unitWord((unit.name ?? '').trim()).toLowerCase();
  const head = word.split(' ')[0] ?? '';
  const named = head.endsWith('s') && !head.endsWith('ss') ? word.charAt(0).toUpperCase() + word.slice(1) : `A ${word}`;
  return `${named} is a package. Choose the smallest unit, like piece, then add the ${word} below.`;
}

/**
 * Review of #692: another smallest unit is never offered as its own package, and a size filled in from a package's usual pack
 * is filled in again for it (100 bulbs is no box of pieces), or cleared when the unit's usual pack counts something else. A
 * typed size stays.
 */
export function forSmallest(rows: PackageRow[], units: Unit[], smallestId: string): PackageRow[] {
  return rows.map((r) => {
    const row = r.unitId === smallestId ? { ...r, unitId: '' } : r;
    const factor = factorForUnit(row.factor, row.filled.factor, usualFactor(units.find((u) => u.id === row.unitId), { unit_id: smallestId }));
    return factor === row.factor ? row : { ...row, factor, filled: { ...row.filled, factor } };
  });
}

/** A package holds a whole number of the smallest unit, 2 or more. */
export function sizeProblem(factor: string): string | null {
  if (!/^\d+$/.test(factor.trim()) || Number(factor.trim()) < 2) return 'Type a whole number, 2 or more.';
  // Review of #692, round 4: no package holds more than this, so a slip of the finger never reaches the server.
  return Number(factor.trim()) > MAX_PACK_SIZE ? 'Type a number up to 100,000.' : null;
}

/** The most packages one new item takes (`packs`, @Size(max = 10) on the server). */
export const MAX_PACKAGES = 10;

/** The most of the smallest unit one package may hold on this form. */
export const MAX_PACK_SIZE = 100_000;

export function rowProblem(row: PackageRow, rows: PackageRow[] = []): string | null {
  if (row.unitId === '') return 'Choose the package.';
  const held = rows.find((r) => r.key === row.holdsKey);
  if (row.holdsKey !== 0 && (!held || held.unitId === '')) return 'Choose the package it holds first.';
  return sizeProblem(row.factor);
}

/**
 * The packs the create request sends; a price only from someone who may set one, never on a pack only for buying. A package
 * of the smallest unit sends its size as before; one that holds another package sends what it holds (issue #699), and the
 * server works its size out.
 */
export function packsToSend(rows: PackageRow[], mayPrice: boolean, usualBuyKey: number | null = null) {
  return rows.map((r) => ({
    unit_id: r.unitId,
    ...(r.holdsKey === 0
      ? { factor: r.factor.trim() }
      : { holds_unit_id: rows.find((x) => x.key === r.holdsKey)?.unitId ?? '', holds_qty: Number(r.factor.trim()) }),
    purpose: r.purpose,
    ...(mayPrice && r.purpose !== 'buy' && r.priceMinor !== null ? { sell_minor: r.priceMinor } : {}),
    ...(usualBuyKey === r.key && r.purpose !== 'sell' ? { usual_buy: true } : {}),
  }));
}

/** The server's refusal of package `index` (its `pack_index`), on its size when it names `packs[i].factor`, else on its unit. */
export type PackError = { index: number; onSize: boolean; message: string };

export function packErrorOf(error: unknown): PackError | null {
  if (!(error instanceof RetailError) || typeof error.problem?.pack_index !== 'number') return null;
  // Review of #692, round 4: the reason is the package's own field problem; the problem's detail is only "invalid fields".
  const index = error.problem.pack_index;
  const fields = ((error.problem.errors as { field?: string; message?: string }[] | undefined) ?? []).filter((f) => f.field?.startsWith(`packs[${index}].`));
  const own = fields.find((f) => f.message)?.message;
  return { index, onSize: fields.some((f) => f.field?.endsWith('.factor') === true), message: own ?? error.message };
}

/** ", with carton of 10 and box of 100", or nothing without packages: the end of "60W bulb added". */
export function withPackages(rows: PackageRow[], units: Unit[]): string {
  const unitOf = (key: number) => units.find((u) => u.id === rows.find((r) => r.key === key)?.unitId);
  // Issue #699: a package of another says so, "carton of 12 boxes"; one of the smallest unit keeps "carton of 10".
  const said = rows.map((r) => `${oneOf(units.find((u) => u.id === r.unitId), 'package')} of ${r.factor.trim()}${r.holdsKey === 0 ? '' : ` ${manyOf(unitOf(r.holdsKey), 'packages')}`}`);
  if (said.length === 0) return '';
  return `, with ${said.length === 1 ? said[0] : `${said.slice(0, -1).join(', ')} and ${said[said.length - 1]}`}`;
}

/**
 * Issue #699 (owner, 29 Oct 2026): a wholesale shop buys in cartons, so setting up the item asks how it is usually bought and
 * what one of that level costs, and works the piece out; the same for selling. An answer is a package row's key (0 for the
 * smallest unit) and the price of one of it.
 * JUSTIFICATION-A3: the owner's new setup questions; no existing control asks for a level and its price.
 */
export type UsualAnswer = { key: number; priceMinor: number | null };

/** The levels a question offers: the smallest unit, then each package used for `use` that is whole, smallest first. */
export function levelChoices(rows: PackageRow[], units: Unit[], smallest: Unit | undefined, use: 'buy' | 'sell') {
  const packs = rows
    .filter((r) => r.unitId !== '' && rowPieces(rows, r) !== null && r.purpose !== (use === 'buy' ? 'sell' : 'buy'))
    .map((r) => ({ value: String(r.key), label: unitWord(units.find((u) => u.id === r.unitId)?.name), pieces: rowPieces(rows, r) ?? 1n }))
    .sort((a, b) => (a.pieces < b.pieces ? -1 : 1));
  return [{ value: '0', label: unitWord(smallest?.name) || 'Piece', pieces: 1n }, ...packs];
}

/** The smallest units in one of the level answered, or null for the smallest unit itself (or a level no longer offered). */
export function answeredPieces(rows: PackageRow[], units: Unit[], smallest: Unit | undefined, use: 'buy' | 'sell', answer: UsualAnswer): bigint | null {
  if (answer.key === 0) return null;
  return levelChoices(rows, units, smallest, use).find((c) => c.value === String(answer.key))?.pieces ?? null;
}

/** The price of one of the smallest unit from an answer at a bigger level, rounded half up; null until a price is typed. */
export function piecePriceOf(answer: UsualAnswer, pieces: bigint): number | null {
  return answer.priceMinor === null ? null : perPieceMinor(answer.priceMinor, pieces * 1000n);
}

/**
 * "How do you usually buy it, and for how much?" with the levels (the owner's words, issue #695), and for a package "What do
 * you pay for one carton?" with the piece worked out. Add an item and Quick add both ask it here.
 */
export function UsualLevel({ id, use, on, rows, units, smallest, answer, onAnswer, showErrors, optional = false, children }: {
  id: string;
  /** Review of #716: the price of one may be left empty (Quick add's buying price when nothing is paid now). */
  optional?: boolean;
  /** Asked only while the item has packages; else the form's own price field alone. */
  on: boolean;
  use: 'buy' | 'sell';
  rows: PackageRow[];
  units: Unit[];
  smallest: Unit | undefined;
  answer: UsualAnswer;
  onAnswer: (answer: UsualAnswer) => void;
  showErrors: boolean;
  /** The question for the smallest unit itself, the form's own price field. */
  children: ReactNode;
}) {
  const choices = levelChoices(rows, units, smallest, use);
  if (!on || choices.length < 2) return <>{children}</>;
  const chosen = choices.find((c) => c.value === String(answer.key)) ?? choices[0]!;
  const question = use === 'buy' ? USUAL_BUY_QUESTION : 'How do you usually sell it, and at what price?';
  const one = oneOf(smallest, 'item');
  const level = chosen.label.toLowerCase();
  const piece = chosen.value === '0' ? null : piecePriceOf(answer, chosen.pieces);
  return (
    <div className="stack" data-testid={`usual-${use}`}>
      <p className="field-label">{question}</p>
      {choices.length <= 5 ? (
        <Segmented label={question} options={choices.map(({ value, label }) => ({ value, label }))} value={chosen.value} onChange={(v) => onAnswer({ key: Number(v), priceMinor: null })} />
      ) : (
        // empty: the smallest unit is always the first choice
        <select id={`${id}-level`} aria-label={question} value={chosen.value} onChange={(e) => onAnswer({ key: Number(e.target.value), priceMinor: null })}>
          {choices.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
      )}
      {chosen.value === '0' ? children : (
        <MoneyInput
          id={`${id}-price`}
          label={use === 'buy' ? `What do you pay for one ${level}?` : `Selling price of one ${level}`}
          required={!optional}
          valueMinor={answer.priceMinor}
          onChange={(priceMinor) => onAnswer({ ...answer, priceMinor })}
          error={answer.priceMinor === null && !optional ? `Type the price of one ${level}.` : null}
          showError={showErrors}
          hint={piece !== null ? <span data-testid={`usual-${use}-piece`}>That is {money(piece)} a {one}.</span> : `1 ${level} is ${String(chosen.pieces)} ${manyOf(smallest, 'items')}. The app works out the price of one.`}
        />
      )}
    </div>
  );
}

export function NewItemPackages({ smallest, units, load, answer, onAnswer, rows, onRows, mayPrice, showErrors, refused = null, soldAt = 0 }: {
  /** Issue #699: the row the item is usually sold by, whose price the selling question below asks; 0 for none. */
  soldAt?: number;
  /** The smallest unit chosen, or none yet. */
  smallest: Unit | undefined;
  /** The switched-on units of the business. */
  units: Unit[];
  load: { isError: boolean; refetch: () => unknown };
  answer: 'no' | 'yes';
  onAnswer: (answer: 'no' | 'yes') => void;
  rows: PackageRow[];
  onRows: (rows: PackageRow[]) => void;
  mayPrice: boolean;
  showErrors: boolean;
  /** The server's refusal of one package, shown on that package's row. */
  refused?: PackError | null;
}) {
  const one = oneOf(smallest, 'item');
  const many = manyOf(smallest, 'items');
  const change = (key: number, next: Partial<PackageRow>) => onRows(rows.map((r) => (r.key === key ? { ...r, ...next } : r)));
  const chooseUnit = (row: PackageRow, unitId: string) => {
    const unit = units.find((u) => u.id === unitId);
    // Issue #699: a unit whose usual pack counts another package of this item (a box of 2 rolls) holds that package.
    const usualOf = rows.find((r) => r.key !== row.key && r.unitId !== '' && r.unitId === unit?.usual_pack?.of_unit_id);
    const holdsKey = usualOf ? usualOf.key : unit?.usual_pack?.of_unit_id === smallest?.id ? 0 : row.holdsKey;
    const heldId = holdsKey === 0 ? smallest?.id : rows.find((r) => r.key === holdsKey)?.unitId;
    const factor = factorForUnit(row.factor, row.filled.factor, usualFactor(unit, { unit_id: heldId }));
    const purpose = purposeForUnit(row.purpose, row.filled.purpose, unit);
    change(row.key, {
      unitId,
      factor,
      holdsKey,
      purpose,
      filled: { factor: factor === row.factor ? row.filled.factor : factor, purpose: purpose === row.purpose ? row.filled.purpose : purpose },
    });
  };
  const nextKey = Math.max(0, ...rows.map((r) => r.key)) + 1;
  // Issue #699: Add a bigger package starts a row holding the biggest package so far.
  const biggest = rowsAsRungs(rows, units, smallest)[0];
  const biggestKey = rows.find((r) => r.unitId === biggest?.unitId)?.key ?? 0;
  const rungs = rowsAsRungs(rows, units, smallest);
  return (
    <div className="stack" data-testid="new-item-packages">
      <p className="field-label">{PACKAGES_QUESTION}</p>
      <Segmented label={PACKAGES_QUESTION} options={ANSWERS} value={answer} onChange={onAnswer} />
      {answer === 'yes' && (
        <>
          {rows.map((row, i) => {
            const used = new Set(rows.filter((r) => r.key !== row.key).map((r) => r.unitId));
            const choices = units.filter((u) => u.id !== smallest?.id && !used.has(u.id ?? ''));
            const pack = oneOf(units.find((u) => u.id === row.unitId), 'package');
            const size = sizeProblem(row.factor);
            const mine = refused?.index === i ? refused : null;
            return (
              <fieldset key={row.key} className="stack" data-testid="package-row">
                <legend>{rows.length > 1 ? `Package ${i + 1}` : 'Package'}</legend>
                <UnitPicker
                  id={`item-pack-unit-${row.key}`}
                  label="Package unit"
                  hint="For example carton, box or dozen."
                  units={choices}
                  loading={false}
                  load={load}
                  value={row.unitId}
                  onChange={(unitId) => chooseUnit(row, unitId)}
                  error={row.unitId === '' ? 'Choose the package.' : mine && !mine.onSize ? mine.message : null}
                  showError={showErrors || (mine !== null && !mine.onSize)}
                />
                {holdable(rows, row).length > 0 && (
                  <>
                    <label htmlFor={`item-pack-holds-${row.key}`}>{`What is inside one ${pack}`}</label>
                    {/* empty: shown only when another package can be held, and the smallest unit is always the first choice */}
                    <select
                      id={`item-pack-holds-${row.key}`}
                      value={row.holdsKey}
                      onChange={(e) => change(row.key, { holdsKey: Number(e.target.value) })}
                    >
                      <option value={0}>{many.charAt(0).toUpperCase() + many.slice(1)}</option>
                      {holdable(rows, row).map((r) => (
                        <option key={r.key} value={r.key}>{unitWords(units.find((u) => u.id === r.unitId)?.name)}</option>
                      ))}
                    </select>
                  </>
                )}
                <Field
                  id={`item-pack-size-${row.key}`}
                  label={`How many ${row.holdsKey === 0 ? many : manyOf(units.find((u) => u.id === rows.find((r) => r.key === row.holdsKey)?.unitId), 'packages')} in one ${pack}`}
                  required
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="10"
                  value={row.factor}
                  onChange={(e) => change(row.key, { factor: e.target.value })}
                  error={size ?? (mine?.onSize ? mine.message : null)}
                  showError={showErrors || mine?.onSize === true}
                />
                {row.unitId !== '' && rowPieces(rows, row) !== null && (
                  <p className="ladder-says" data-testid="package-pieces">
                    So 1 {pack} = <strong>{shown((rowPieces(rows, row) ?? 0n) * 1000n)}</strong> {many}
                  </p>
                )}
                {row.holdsKey !== 0 && showErrors && rowProblem(row, rows) === 'Choose the package it holds first.' && (
                  <p className="rt-flag">Choose the package it holds first.</p>
                )}
                <p className="field-label">Used for</p>
                <Segmented label={`Used for, ${pack}`} options={USED_FOR} value={row.purpose} onChange={(purpose) => change(row.key, { purpose })} />
                {row.purpose === 'buy' ? (
                  <Hint>A package only for buying is not offered when selling, so it has no selling price.</Hint>
                ) : row.key === soldAt ? (
                  <Hint>Its selling price is asked below, under How do you usually sell it.</Hint>
                ) : mayPrice ? (
                  <MoneyInput
                    id={`item-pack-price-${row.key}`}
                    label={`Selling price of one ${pack}`}
                    valueMinor={row.priceMinor}
                    onChange={(priceMinor) => change(row.key, { priceMinor })}
                    hint={size === null && rowPieces(rows, row) !== null
                      ? `Optional. Leave it empty to sell it at ${String(rowPieces(rows, row))} times the ${one} price.`
                      : `Optional. Leave it empty to sell it at the ${one} price times its size.`}
                  />
                ) : (
                  <Hint>It sells at the {one} price times its size until someone who may change prices sets its own.</Hint>
                )}
                {rows.length > 1 && (
                  <button type="button" className="btn-ghost" onClick={() => onRows(rows.filter((r) => r.key !== row.key).map((r) => (r.holdsKey === row.key ? { ...r, holdsKey: 0 } : r)))}>
                    Remove the {pack}
                  </button>
                )}
              </fieldset>
            );
          })}
          {rows.length < MAX_PACKAGES ? (
            <button type="button" className="btn-ghost" onClick={() => onRows([...rows, newPackageRow(nextKey, biggestKey)])}>
              {icons.plus}
              <span>Add a bigger package</span>
            </button>
          ) : (
            <Hint>Up to 10 packages</Hint>
          )}
          {/* Issue #699 (ADR-107): the packages as the ladder they make, biggest first, and one drawn opened up. */}
          {rungs.length > 0 && (
            <div className="ladder-panel stack" data-testid="new-item-ladder">
              <p className="field-label">What one package holds</p>
              <LadderRungs rungs={rungs} smallest={smallest?.name ?? ''} />
              <LadderSum rungs={rungs} smallest={smallest?.name ?? ''} />
              <LookInside rungs={rungs} smallest={smallest?.name ?? ''} />
            </div>
          )}
        </>
      )}
    </div>
  );
}
