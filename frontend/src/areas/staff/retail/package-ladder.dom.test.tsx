// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Pack, Product, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #699 (ADR-107): the package ladder on the item page. Biggest first, each level says what it holds and the pieces in
// words; a change shows the before and after in words before it is saved; a size that is not whole is refused in plain words;
// a bigger package is added on top and the biggest removed. Example items, not a real shop.

vi.setConfig({ testTimeout: 20000 });

const piece: Unit = { id: 'u-pc', name: 'Piece', active: true, version: 1 };
const box: Unit = { id: 'u-box', name: 'Box', active: true, version: 1 };
const carton: Unit = { id: 'u-ctn', name: 'Carton', active: true, version: 1 };
const bale: Unit = { id: 'u-bale', name: 'Bale', active: true, version: 1 };
const socket: Product = { id: 'p-1', description: 'Test 13A Socket', unit: 'Piece', unit_id: 'u-pc', sell_minor: 6_500, stock_on_hand: '275.000', version: 1 };
const packs: Pack[] = [
  { unit_id: 'u-box', unit: 'Box', factor: '10.000', active: true, version: 2, purpose: 'both', holds_unit_id: 'u-pc', holds_unit: 'Piece', holds_qty: 10 },
  { unit_id: 'u-ctn', unit: 'Carton', factor: '120.000', active: true, version: 5, purpose: 'both', holds_unit_id: 'u-box', holds_unit: 'Box', holds_qty: 12 },
];
const updatePack = vi.fn();
const addPack = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listUnits: () => Promise.resolve([piece, box, carton, bale]),
      listPacks: () => Promise.resolve(packs),
      updatePack: (...a: unknown[]) => updatePack(...a),
      addPack: (...a: unknown[]) => addPack(...a),
    },
  };
});

const { ItemLadder, lookInsideWords } = await import('./package-ladder');
const { ladderOf } = await import('./ladder-state');
const owner = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
const branch = owner.branches?.[0]?.id ?? '';

function draw(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: owner, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  updatePack.mockReset();
  addPack.mockReset();
});

describe('the package ladder on the item page (issue #699)', () => {
  it('shows every level biggest first with the pieces in words, and the chain', () => {
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    const names = screen.getAllByText(/^1 (Carton|Box|Piece)/).map((n) => n.textContent);
    expect(names[0]).toBe('1 Carton');
    expect(names[1]).toBe('1 Box');
    expect(screen.getByLabelText('So 1 carton = 120 pieces')).toBeTruthy();
    expect(screen.getByText(/1 carton → 12 boxes → each 10 pieces/)).toBeTruthy();
    expect(screen.getByText('The single thing a customer can buy. Stock is kept in pieces.')).toBeTruthy();
  });

  it('shows the before and after of a change, then saves what one box holds', async () => {
    updatePack.mockResolvedValue({ ...packs[0], factor: '12.000', holds_qty: 12, version: 3 });
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    fireEvent.click(screen.getByRole('button', { name: 'One more piece in a box' }));
    fireEvent.click(screen.getByRole('button', { name: 'One more piece in a box' }));
    const check = screen.getByTestId('ladder-check');
    expect(within(check).getByText('1 box will be 12 pieces. It was 10.')).toBeTruthy();
    expect(within(check).getByText('1 carton will be 144 pieces. It was 120.')).toBeTruthy();
    expect(within(check).getByText(/Your stock stays 275 pieces\. Only how it is shown changes\./)).toBeTruthy();
    fireEvent.click(within(check).getByRole('button', { name: 'OK, save' }));
    await waitFor(() => expect(updatePack).toHaveBeenCalledWith('p-1', 'u-box', 2, { holds_unit_id: 'u-pc', holds_qty: 12 }));
  });

  it('refuses a size that is not 2 or more whole of the level below, and keeps it as it was', () => {
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    fireEvent.change(screen.getByLabelText('How many boxes in one carton'), { target: { value: '2.5' } });
    expect(screen.getByRole('alert').textContent).toBe('A carton must hold 2 or more whole boxes. Type a whole number like 10.');
    expect(screen.queryByTestId('ladder-check')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Keep it as it was' }));
    expect((screen.getByLabelText('How many boxes in one carton') as HTMLInputElement).value).toBe('12');
    expect(updatePack).not.toHaveBeenCalled();
  });

  it('adds a bigger package holding the biggest, and removes the biggest', async () => {
    addPack.mockResolvedValue({});
    updatePack.mockResolvedValue({ ...packs[1], active: false, version: 6 });
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    const form = screen.getByTestId('ladder-add');
    const picker = await within(form).findByLabelText(/^The bigger package/);
    await waitFor(() => expect(within(picker).queryByRole('option', { name: 'Bale' })).toBeTruthy());
    expect(within(picker).queryByRole('option', { name: 'Carton' })).toBeNull();
    fireEvent.change(picker, { target: { value: 'u-bale' } });
    fireEvent.change(within(form).getByLabelText('How many cartons in one bale'), { target: { value: '4' } });
    expect(within(form).getByText('480')).toBeTruthy();
    fireEvent.click(within(form).getByRole('button', { name: 'Add it' }));
    await waitFor(() => expect(addPack).toHaveBeenCalledWith('p-1', { unit_id: 'u-bale', holds_unit_id: 'u-ctn', holds_qty: 4, purpose: 'both' }));

    fireEvent.click(screen.getByRole('button', { name: 'Remove the biggest' }));
    await waitFor(() => expect(updatePack).toHaveBeenCalledWith('p-1', 'u-ctn', 5, { active: false }));
  });

  it('says what one package holds when it is opened up', () => {
    expect(lookInsideWords(ladderOf(socket, packs), 'u-ctn', 'Piece')).toBe('1 carton holds 12 boxes, each holding 10 pieces. That is 120 pieces.');
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    expect(screen.getByRole('img', { name: '1 carton holds 12 boxes, each holding 10 pieces. That is 120 pieces.' })).toBeTruthy();
  });
});
