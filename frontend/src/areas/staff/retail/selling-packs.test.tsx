import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Pack, Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PackEditor, packChange, shownPackPrice, suggestedHint } from './catalogue-packs';
import { defaultPrice } from './sale-state';
import { buyingPacks, packWalkInPrice, sellingPacks } from './units-state';

// Selling packs with their own prices (issue #624, ADR-099): a walk-in sale takes a pack's own price, else the piece price
// times the size; a pack is offered only where it is used; Edit item shows the packs in a small table. Names and amounts are
// fabricated.

const pack = (unit: string, factor: string, extra: Partial<Pack> = {}): Pack => ({
  id: `k-${unit}`, product_id: 'p1', unit_id: `u-${unit}`, unit, factor, active: true, version: 1, purpose: 'both', ...extra,
});

const carton = pack('carton', '24.000', { sell_minor: 20_000, suggested_sell_minor: 24_000 });
const dozen = pack('dozen', '12.000', { purpose: 'sell', suggested_sell_minor: 12_000 });
const box = pack('box', '10.000', { purpose: 'buy' });
const bulb: Product = { id: 'p1', code: 'T-BULB', description: 'Test bulb', unit: 'piece', unit_id: 'u-piece', sell_minor: 1_000, cost_minor: 750, active: true, qty: '100.000', packs: [carton, dozen, box] };

describe('the walk-in price of a pack', () => {
  it('is the pack\'s own price when it has one, else the piece price times the size', () => {
    expect(packWalkInPrice(1_000, carton)).toBe(20_000);
    expect(packWalkInPrice(1_000, dozen)).toBe(12_000);
    expect(defaultPrice(bulb, 'u-carton')).toBe(20_000);
    expect(defaultPrice(bulb, 'u-dozen')).toBe(12_000);
    expect(defaultPrice(bulb, undefined)).toBe(1_000);
  });

  it('offers a pack only where it is used', () => {
    expect(sellingPacks(bulb).map((k) => k.unit)).toEqual(['carton', 'dozen']);
    expect(buyingPacks(bulb).map((k) => k.unit)).toEqual(['carton', 'box']);
    // A pack read before #624 has no purpose and is used for both, as before.
    const old: Product = { ...bulb, packs: [{ ...carton, purpose: undefined }] };
    expect(sellingPacks(old)).toHaveLength(1);
    expect(buyingPacks(old)).toHaveLength(1);
  });

  it('shows the own price, the suggested one, or none for a pack only for buying', () => {
    expect(shownPackPrice(bulb, carton)).toEqual({ minor: 20_000, own: true });
    expect(shownPackPrice(bulb, dozen)).toEqual({ minor: 12_000, own: false });
    expect(shownPackPrice(bulb, box)).toBeNull();
    expect(suggestedHint(bulb, '12')).toContain('Suggested UGX 12,000');
    expect(suggestedHint(bulb, '0')).toBeNull();
  });
});

describe('saving a pack', () => {
  it('sends only what changed', () => {
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: 20_000 }, true)).toEqual({});
    expect(packChange(carton, { factor: '30', purpose: 'both', priceMinor: 19_500 }, true)).toEqual({ factor: '30', sell_minor: 19_500 });
    // An emptied price goes back to the suggested one.
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: null }, true)).toEqual({ clear_sell_minor: true });
    // A pack made for buying only loses its price on the server; nothing about the price is sent.
    expect(packChange(carton, { factor: '24', purpose: 'buy', priceMinor: 20_000 }, true)).toEqual({ purpose: 'buy' });
    // Someone who may not change prices never sends one.
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: 1 }, false)).toEqual({});
  });
});

describe('the pack table on Edit item', () => {
  const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
  const me = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
  const client = new QueryClient();
  client.setQueryData(['retail', 'packs', 'p1'], [carton, dozen, box, pack('tray', '30.000', { suggested_sell_minor: 30_000, sell_minor: 22_000, below_cost: true })]);
  const html = renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <PackEditor product={bulb} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );

  it('lists each pack with its size and price, the piece first', () => {
    expect(html).toContain('data-testid="pack-table"');
    expect(html).toContain('>Size<');
    expect(html).toContain('>Selling price<');
    expect(html).toContain('UGX 20,000');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('suggested');
    expect(html).toContain('Not sold');
    expect(html).toContain('Buying only');
    expect(html).toContain('Selling only');
    expect(html.indexOf('UGX 1,000')).toBeLessThan(html.indexOf('UGX 20,000'));
  });

  it('marks a pack below cost and names each Change button', () => {
    expect(html).toContain('Below cost');
    expect(html).toContain('aria-label="Change the carton pack"');
  });

  it('asks what a new pack is for and its price', () => {
    expect(html).toContain('aria-label="Used for"');
    expect(html).toContain('Selling price of one pack');
  });
});
