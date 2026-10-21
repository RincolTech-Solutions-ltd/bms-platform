import { describe, expect, it } from 'vitest';
import { monthsText, moveStockSearch, readPrefill } from './slow-movers-state';

describe('slow movers (issue #414)', () => {
  const item = {
    branch_id: 'hq', product_id: 'p1', unit: 'piece',
    sells_better_at: { branch_id: 'b2', suggested_qty: '7.000' },
  };

  it('builds the Move stock address from the suggestion: from here, to the faster branch, the item and the quantity', () => {
    expect(moveStockSearch(item)).toEqual({ from: 'hq', to: 'b2', item: 'p1', qty: '7.000' });
  });

  it('has no address for a row without a suggestion', () => {
    expect(moveStockSearch({ ...item, sells_better_at: undefined })).toBeNull();
    expect(moveStockSearch({ ...item, sells_better_at: { branch_id: 'b2' } })).toBeNull();
  });

  it('reads a complete prefill from an address and nothing from a partial one', () => {
    expect(readPrefill({ from: 'hq', to: 'b2', item: 'p1', qty: '7.000', other: 'x' })).toEqual({ from: 'hq', to: 'b2', item: 'p1', qty: '7.000' });
    expect(readPrefill({ from: 'hq', to: 'b2', item: 'p1' })).toBeNull();
    expect(readPrefill({ from: 'hq', to: '', item: 'p1', qty: '1' })).toBeNull();
    expect(readPrefill({ from: 1, to: 'b2', item: 'p1', qty: '1' })).toBeNull();
  });

  it('words the months plainly, one month in the singular and a capped figure as over', () => {
    expect(monthsText('20.0', false)).toBe('20.0 months');
    expect(monthsText('1.0', false)).toBe('1.0 month');
    expect(monthsText('120.0', true)).toBe('over 120.0 months');
  });
});
