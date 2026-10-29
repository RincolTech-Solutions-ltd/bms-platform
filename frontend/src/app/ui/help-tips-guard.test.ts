import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { HELP_TIPS, type HelpTipKey } from '../help-tips';

// The help tips guard (issue #707): every label the owner found confusing renders a HelpTip. Each tip names the screen file
// and the words that show the label there; the guard reads the file and finds the tip on the label's line or the line after
// it, so moving a label away from its tip, or a tip without a place, fails here. The labels on Add an item, the item's packages
// and Count stock wait for pull request #710 to merge (issue #707); each then gets its words in help-tips.ts and a place here.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

const PLACES: Record<HelpTipKey, { file: string; shows: string }[]> = {
  averageBuyingPrice: [{ file: 'areas/staff/retail/stock.tsx', shows: 'Average buying price' }],
  sellingPrice: [{ file: 'areas/staff/retail/stock.tsx', shows: '{WORDS.sellingPrice}' }],
  lowStockLevel: [{ file: 'areas/staff/retail/stock-minimum.tsx', shows: 'Warn me when stock is below' }],
  priceList: [{ file: 'areas/staff/retail/customer-groups.tsx', shows: '>Price list</label>' }],
  onCredit: [{ file: 'areas/staff/retail/dashboard.tsx', shows: 'label="On credit"' }],
  joinSizes: [{ file: 'areas/staff/retail/catalogue-variants.tsx', shows: 'Put them together as one item' }],
  appraisal: [{ file: 'areas/staff/lending/application-view.tsx', shows: "'Appraisal'" }],
  guarantor: [{ file: 'areas/staff/lending/application-form.tsx', shows: 'title="Guarantors"' }],
  collateral: [{ file: 'areas/staff/lending/member-page.tsx', shows: '>Collateral' }],
  outstanding: [{ file: 'areas/staff/lending/loan.tsx', shows: '>Outstanding' }],
  inArrears: [{ file: 'areas/staff/home-cards.tsx', shows: 'label="In arrears"' }],
};

// A component that draws a HelpTip from a `tip` prop, so `tip="onCredit"` on it renders one.
const DRAWS_TIP = /\{tip && <> <HelpTip tip=\{tip\} \/><\/>\}/;

describe('the help tips guard', () => {
  it('places every tip, and names no place without words', () => {
    expect(Object.keys(PLACES).sort()).toEqual(Object.keys(HELP_TIPS).sort());
  });

  for (const [tip, places] of Object.entries(PLACES)) {
    for (const { file, shows } of places) {
      it(`renders a HelpTip on ${HELP_TIPS[tip as HelpTipKey].label} in ${file}`, () => {
        const source = readFileSync(`${SRC}${file}`, 'utf8');
        const lines = source.split('\n');
        const at = lines.findIndex((line, i) => line.includes(shows) && [line, lines[i + 1] ?? ''].some((l) => l.includes(`tip="${tip}"`)));
        expect(at, `${shows} with tip="${tip}"`).toBeGreaterThanOrEqual(0);
        const line = `${lines[at]}\n${lines[at + 1] ?? ''}`;
        // Either the HelpTip itself, or a prop of a component in the file that draws one.
        expect(line.includes(`<HelpTip tip="${tip}"`) || DRAWS_TIP.test(source) || /<HelpTip tip=\{tip\}/.test(readSharedGroup(source)), file).toBe(true);
      });
    }
  }
});

/** The lending Group draws the tip in form-fields.tsx; a file that uses it reads that file too. */
function readSharedGroup(source: string): string {
  return source.includes("from './form-fields'") ? readFileSync(`${SRC}areas/staff/lending/form-fields.tsx`, 'utf8') : '';
}
