import type { Pack, Product } from '../../../api/retail';
import { qtyString } from './maths';
import { activePacks, factorMilli, packsAndPieces, quantityIn, unitWord, unitWords } from './units-state';

// Issue #699 (ADR-107): an item's packages as a ladder, biggest first. A pack may say what it holds one level down (a carton
// holds 12 boxes, a box 10 pieces); a pack made before says nothing and holds its factor of the smallest unit. The server keeps
// every factor right; these are the words and the sums the screens show, and the before and after of a change, so the person
// never does arithmetic. Every number is whole thousandths as in units-state.ts.

/** One level of the ladder: its unit, what one holds and how many, and the pieces in one. */
export type Rung = {
  unitId: string;
  unit: string;
  holdsUnitId: string;
  holdsUnit: string;
  /** How many of the level held, as typed ("12"); a pack made before holds its factor ("120", or "12.5"). */
  holdsQty: string;
  /** Pieces in one, as thousandths. */
  pieces: bigint;
  pack?: Pack;
};

/** "carton" for Carton, "piece" for Pc: a unit as a word in a sentence. */
export const one = (unit: string | undefined) => unitWord(unit).toLowerCase();
/** "cartons" for Carton, "pieces" for Pc. */
export const many = (unit: string | undefined) => unitWords(unit).toLowerCase();

/** A number of thousandths for people: 120000n is "120", with thousands grouped. */
export function shown(milli: bigint): string {
  const whole = milli / 1000n;
  const part = milli % 1000n;
  const text = Number(whole).toLocaleString('en-GB');
  return part === 0n ? text : `${text}.${String(part).padStart(3, '0').replace(/0+$/, '')}`;
}

/** The item's active packs as rungs, biggest first; a tie goes to the name. */
export function ladderOf(product: Pick<Product, 'unit' | 'unit_id'>, packs: Pack[]): Rung[] {
  return packs
    .filter((k) => k.active !== false && k.unit_id)
    .map((k): Rung => {
      const pieces = factorMilli(k.factor) ?? 1000n;
      const holdsSmallest = !k.holds_unit_id || k.holds_unit_id === product.unit_id;
      return {
        unitId: k.unit_id ?? '',
        unit: k.unit ?? '',
        holdsUnitId: k.holds_unit_id ?? product.unit_id ?? '',
        holdsUnit: holdsSmallest ? (product.unit ?? '') : (k.holds_unit ?? ''),
        holdsQty: k.holds_qty !== undefined ? String(k.holds_qty) : shown(pieces).replace(/,/g, ''),
        pieces,
        pack: k,
      };
    })
    .sort((a, b) => (a.pieces === b.pieces ? a.unit.localeCompare(b.unit) : a.pieces > b.pieces ? -1 : 1));
}

/** The rungs of a product's packs as the reads send them (`product.packs`). */
export function ladderOfProduct(product: Product): Rung[] {
  return ladderOf(product, activePacks(product));
}

/**
 * A rung and the rungs it holds, one level down each time, ending at the smallest unit. Review of #710: it keeps what it has
 * seen, so a loop written straight to the table (a box holding the carton that holds it) ends the walk instead of freezing
 * the page; every walk of what a level holds goes through here.
 */
export function holdsChain(rungs: Rung[], top: Rung): Rung[] {
  const out: Rung[] = [];
  const seen = new Set<string>();
  for (let at: Rung | undefined = top; at && !seen.has(at.unitId); at = rungs.find((r) => r.unitId === at?.holdsUnitId)) {
    seen.add(at.unitId);
    out.push(at);
  }
  return out;
}

/** Review of #710: true when a level's chain comes back to a level it has passed, which only a direct write could store. */
export function hasLoop(rungs: Rung[]): boolean {
  return rungs.some((top) => {
    const walked = holdsChain(rungs, top);
    const last = walked[walked.length - 1];
    return last !== undefined && walked.some((r) => r.unitId === last.holdsUnitId);
  });
}

/** What the page says over a stored loop instead of freezing (review of #710, round 3). */
export const LOOP_WORDS = 'These packages point at each other. Fix the sizes on this page.';

/** "So 1 carton = 120 pieces." */
export function soOne(rung: Pick<Rung, 'unit' | 'pieces'>, smallest: string): string {
  return `So 1 ${one(rung.unit)} = ${shown(rung.pieces)} ${rung.pieces === 1000n ? one(smallest) : many(smallest)}`;
}

/**
 * The chain from the biggest level down, as the eye follows it: "1 carton → 12 boxes → each 10 pieces", and the pieces in
 * the biggest. Follows what each level holds, so a dozen kept beside a box is not in a carton's chain.
 */
export function chain(rungs: Rung[], smallest: string): { words: string; total: string } | null {
  const top = rungs[0];
  if (!top) return null;
  const steps = [`1 ${one(top.unit)}`];
  for (const at of holdsChain(rungs, top)) {
    const qty = at.holdsQty;
    steps.push(`${steps.length > 1 ? 'each ' : ''}${quantityIn(qty, unitWord(at.holdsUnit)).toLowerCase()}`);
  }
  return { words: steps.join(' → '), total: `${shown(top.pieces)} ${many(smallest)} in one ${one(top.unit)}` };
}

/** Why a typed number of a level held is refused, in plain words, or null: a whole number, 2 or more. */
export function holdsProblem(value: string, rung: Pick<Rung, 'unit' | 'holdsUnit'>): string | null {
  const text = value.trim();
  if (/^\d+$/.test(text) && Number(text) >= 2 && Number(text) <= 1_000_000) return null;
  return `A ${one(rung.unit)} must hold 2 or more whole ${many(rung.holdsUnit)}. Type a whole number like 10.`;
}

/** One line of the check sheet: a level, the pieces in one before and after. */
export type LevelChange = { unitId: string; unit: string; before: bigint; after: bigint };

/**
 * The levels a new number held changes, the one changed first, then every level holding it, any depth: "1 carton will be
 * 144 pieces. It was 120." The same sums the server makes (PackLadder), so the sheet says what will be saved.
 */
export function changesOf(rungs: Rung[], unitId: string, holds: { unitId: string; qty: string }, smallestId: string): LevelChange[] {
  const after = new Map<string, bigint>();
  const pieces = (id: string, seen: Set<string>): bigint => {
    if (id === smallestId) return 1000n;
    const known = after.get(id);
    if (known !== undefined) return known;
    const r = rungs.find((x) => x.unitId === id);
    if (!r || seen.has(id)) return 0n;
    seen.add(id);
    const held = id === unitId ? holds : { unitId: r.holdsUnitId, qty: r.holdsQty };
    // A pack made before holds its factor of the smallest unit, which may have three places.
    const value = held.unitId === smallestId || !held.unitId ? (factorMilli(held.qty) ?? 0n) : pieces(held.unitId, seen) * BigInt(held.qty);
    after.set(id, value);
    return value;
  };
  return rungs
    .map((r) => ({ unitId: r.unitId, unit: r.unit, before: r.pieces, after: pieces(r.unitId, new Set()) }))
    .filter((c) => c.after !== c.before)
    .sort((a, b) => (a.before < b.before ? -1 : 1));
}

/** "1 carton will be 144 pieces. It was 120." */
export function changeWords(c: LevelChange, smallest: string): string {
  return `1 ${one(c.unit)} will be ${shown(c.after)} ${many(smallest)}. It was ${shown(c.before)}.`;
}

/** A level the server sends for stock: a unit and the pieces in one ("120.000"). */
export type StockLevel = { unit?: string; factor?: string };

/** The whole levels of a stock row, biggest first, one per size (a dozen and a box of 12 count once). */
export function wholeLevels(levels: StockLevel[] | undefined): { unit: string; size: bigint }[] {
  const out: { unit: string; size: bigint }[] = [];
  for (const l of levels ?? []) {
    const size = factorMilli(l.factor);
    if (!l.unit || size === null || size <= 1000n || size % 1000n !== 0n || out.some((o) => o.size === size)) continue;
    out.push({ unit: l.unit, size });
  }
  return out.sort((a, b) => (a.size > b.size ? -1 : 1));
}

/**
 * Stock at every level, as a person says it: "2 Cartons, 3 Boxes and 5 Pieces (275 Pieces)", the words of packsAndPieces
 * (ADR-103) with every level of the ladder. A level with none is left out; below zero says how far short.
 */
export function everyLevel(qty: string, unit: string | undefined, levels: StockLevel[] | undefined): string {
  const top = wholeLevels(levels)[0];
  return packsAndPieces(qty, unit, top ? { unit: top.unit, factor: qtyString(Number(top.size)), levels } : undefined);
}


/** The level Buy stock opens on: the item's usual buying package while it is still bought, else none (the smallest unit). */
export function usualBuyingPack(product: Product): Pack | undefined {
  return activePacks(product).find((k) => k.usual_buy === true && k.purpose !== 'sell');
}

/** "That is UGX 5,000 a piece": a price of one level, per piece, rounded half up. */
export function perPieceMinor(priceMinor: number, pieces: bigint): number {
  if (pieces <= 0n) return priceMinor;
  return Number((BigInt(priceMinor) * 2000n + pieces) / (2n * pieces));
}
