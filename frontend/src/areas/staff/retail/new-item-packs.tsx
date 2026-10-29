import { RetailError, type Unit } from '../../../api/retail';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { Segmented } from '../../../app/ui/Segmented';
import { icons } from '../../../components/icons';
import { PURPOSES, factorForUnit, purposeForUnit, usualFactor, type Purpose } from './catalogue-packs';
import { UnitPicker } from './unit-picker';
import { unitWord, unitWords } from './units-state';

// Issue #689 (ADR-106): Add an item asks for the smallest unit, then whether the item is bought or sold in packages, and
// takes the packages in the same form, so one save makes the item and its packs together. A package is a pack of
// retail_product_units (ADR-033, ADR-099), with its unit, its size, what it is for and an optional price of its own; nothing
// new is stored. The words follow the units chosen: "How many bulbs in one carton".

export const PACKAGES_QUESTION = 'Do you buy or sell it in boxes, cartons or other packages?';

const ANSWERS = [{ value: 'no', label: 'No' }, { value: 'yes', label: 'Yes' }] as const;

/** Used for in the order the owner asked for on this form (issue #689): Both, Buying, Selling; the values of PURPOSES. */
const USED_FOR = (['both', 'buy', 'sell'] as const).map((v) => PURPOSES.find((o) => o.value === v)!);

export type PackageRow = {
  key: number;
  unitId: string;
  factor: string;
  purpose: Purpose;
  priceMinor: number | null;
  /** The size and Used for last filled in from the unit's usual pack (ADR-100), which another unit replaces. */
  filled: { factor: string; purpose: Purpose };
};

export function newPackageRow(key: number): PackageRow {
  return { key, unitId: '', factor: '', purpose: 'both', priceMinor: null, filled: { factor: '', purpose: 'both' } };
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

export function rowProblem(row: PackageRow): string | null {
  return row.unitId === '' ? 'Choose the package.' : sizeProblem(row.factor);
}

/** The packs the create request sends; a price only from someone who may set one, never on a pack only for buying. */
export function packsToSend(rows: PackageRow[], mayPrice: boolean) {
  return rows.map((r) => ({
    unit_id: r.unitId,
    factor: r.factor.trim(),
    purpose: r.purpose,
    ...(mayPrice && r.purpose !== 'buy' && r.priceMinor !== null ? { sell_minor: r.priceMinor } : {}),
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
  const said = rows.map((r) => `${oneOf(units.find((u) => u.id === r.unitId), 'package')} of ${r.factor.trim()}`);
  if (said.length === 0) return '';
  return `, with ${said.length === 1 ? said[0] : `${said.slice(0, -1).join(', ')} and ${said[said.length - 1]}`}`;
}

export function NewItemPackages({ smallest, units, load, answer, onAnswer, rows, onRows, mayPrice, showErrors, refused = null }: {
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
    const factor = factorForUnit(row.factor, row.filled.factor, usualFactor(unit, { unit_id: smallest?.id }));
    const purpose = purposeForUnit(row.purpose, row.filled.purpose, unit);
    change(row.key, {
      unitId,
      factor,
      purpose,
      filled: { factor: factor === row.factor ? row.filled.factor : factor, purpose: purpose === row.purpose ? row.filled.purpose : purpose },
    });
  };
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
                <Field
                  id={`item-pack-size-${row.key}`}
                  label={`How many ${many} in one ${pack}`}
                  required
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="10"
                  value={row.factor}
                  onChange={(e) => change(row.key, { factor: e.target.value })}
                  error={size ?? (mine?.onSize ? mine.message : null)}
                  showError={showErrors || mine?.onSize === true}
                />
                <p className="field-label">Used for</p>
                <Segmented label={`Used for, ${pack}`} options={USED_FOR} value={row.purpose} onChange={(purpose) => change(row.key, { purpose })} />
                {row.purpose === 'buy' ? (
                  <Hint>A package only for buying is not offered when selling, so it has no selling price.</Hint>
                ) : mayPrice ? (
                  <MoneyInput
                    id={`item-pack-price-${row.key}`}
                    label={`Selling price of one ${pack}`}
                    valueMinor={row.priceMinor}
                    onChange={(priceMinor) => change(row.key, { priceMinor })}
                    hint={size === null
                      ? `Optional. Leave it empty to sell it at ${row.factor.trim()} times the ${one} price.`
                      : `Optional. Leave it empty to sell it at the ${one} price times its size.`}
                  />
                ) : (
                  <Hint>It sells at the {one} price times its size until someone who may change prices sets its own.</Hint>
                )}
                {rows.length > 1 && (
                  <button type="button" className="btn-ghost" onClick={() => onRows(rows.filter((r) => r.key !== row.key))}>
                    Remove the {pack}
                  </button>
                )}
              </fieldset>
            );
          })}
          {rows.length < MAX_PACKAGES ? (
            <button type="button" className="btn-ghost" onClick={() => onRows([...rows, newPackageRow(Math.max(0, ...rows.map((r) => r.key)) + 1)])}>
              {icons.plus}
              <span>Add another package</span>
            </button>
          ) : (
            <Hint>Up to 10 packages</Hint>
          )}
        </>
      )}
    </div>
  );
}
