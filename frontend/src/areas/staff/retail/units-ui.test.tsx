import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PackEditor } from './catalogue-packs';
import { Receipt, SaleForm } from './sale';

// Units of measure on the screens (ADR-033, issue #194). With the capability off nothing is drawn:
// "all off equals today". Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const withUnits = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };

const page = (me: ReturnType<typeof mockMe>, node: ReactElement, client = new QueryClient()) =>
  renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const pen: Product = { id: 'p1', code: 'T-PEN', description: 'Test pen', unit: 'piece', sell_minor: 100, cost_minor: 83, active: true, qty: '100.000' };

describe('pack sizes on the item', () => {
  it('draws nothing while the units capability is off', () => {
    expect(page(mockMe('admin'), <PackEditor product={pen} />)).toBe('');
  });

  it('draws nothing for a user who cannot manage the catalogue', () => {
    const seller = { ...mockMe('sales'), module_capabilities: { retail: ['units'] } };
    expect(page(seller, <PackEditor product={pen} />)).toBe('');
  });

  it('is offered to a catalogue manager once the capability is on', () => {
    const html = page(withUnits, <PackEditor product={pen} />);
    expect(html).toContain('Pack sizes');
    expect(html).toContain('Add pack');
    expect(html).toContain('for="pack-factor"');
  });
});

describe('the sale form', () => {
  const withPacks: Product = { ...pen, packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'carton', factor: '12.000', active: true, version: 1 }] };
  const client = new QueryClient();
  client.setQueryData(['retail', 'products', branch, ''], [withPacks]);

  it('offers no unit choice on an empty sale', () => {
    expect(page(withUnits, <SaleForm branchId={branch} />, client)).not.toContain('Sell by');
  });
});

describe('the receipt', () => {
  const sale: Sale = {
    id: 's1', branch_id: branch, sale_date: '2026-10-05', payment_method: 'cash',
    lines: [
      { product_id: 'p1', description: 'Test pen', qty: '24.000', unit_price_minor: 100, line_total_minor: 2400, sale_unit_id: 'u1', sale_unit: 'carton', sale_qty: '2.000', sale_unit_price_minor: 1200, unit_factor: '12.000' },
      { product_id: 'p2', description: 'Test bulb', qty: '3.000', unit_price_minor: 500, line_total_minor: 1500 },
    ],
    total_minor: 3900, paid_minor: 3900, balance_minor: 0,
  };

  it('names the unit used on a pack line and shows an older line as it was', () => {
    const html = page(mockMe('sales'), <Receipt sale={sale} />);
    expect(html).toContain('2 carton');
    expect(html).toContain('UGX 1,200');
    expect(html).toContain('UGX 2,400');
    expect(html).toContain('>3<');
    expect(html).toContain('UGX 500');
  });
});
