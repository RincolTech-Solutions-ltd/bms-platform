// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { pickItem } from '../../../test-utils/item-picker';
import { StaffContext } from '../context';
import { RestockForm } from './restock';
import { SaleForm } from './sale';

// Issue #699 (ADR-107): every level of the ladder sells and is bought at its own price, and Buy stock opens on the level the
// shop usually buys the item in. The retail adapter is the fabricated mock; the item and its packages are invented.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const LAMP = '00000000-0000-4000-8000-0000000b0003';
const NAME = 'LED bulb 9W screw';
const me = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
let box = '';
let carton = '';

beforeEach(async () => {
  Object.assign(retail, createMockRetail());
  const lamp = await retail.getProduct(LAMP);
  box = (await retail.createUnit('Test Box')).id ?? '';
  carton = (await retail.createUnit('Test Carton')).id ?? '';
  await retail.addPack(LAMP, { unit_id: box, holds_unit_id: lamp.unit_id, holds_qty: 10, purpose: 'both' });
  await retail.addPack(LAMP, { unit_id: carton, holds_unit_id: box, holds_qty: 12, purpose: 'both', sell_minor: 690_000, usual_buy: true });
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
});

function open(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: BRANCH }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('selling and buying at every level (issue #699)', () => {
  it('works the carton out from the boxes it holds', async () => {
    const packs = await retail.listPacks(LAMP);
    expect(packs.find((k) => k.unit_id === carton)?.factor).toBe('120.000');
  });

  it('offers every level on Sell', async () => {
    open(<SaleForm branchId={BRANCH} />);
    await pickItem(NAME);
    const unit = (await waitFor(() => document.getElementById('unit-0'))) as HTMLSelectElement;
    const options = within(unit).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(expect.arrayContaining(['Test Carton (120 pieces)', 'Test Box (10 pieces)']));
  });

  it('opens Buy stock on the usual buying level', async () => {
    open(<RestockForm />);
    await pickItem(NAME);
    const unit = (await waitFor(() => document.getElementById('unit-0'))) as HTMLSelectElement;
    await waitFor(() => expect(unit.value).toBe(carton));
    // The size question opens on the carton's usual size, 120 pieces.
    expect((screen.getByLabelText(/^How many pieces in each test carton/i) as HTMLInputElement).value).toBe('120');
  });
});
