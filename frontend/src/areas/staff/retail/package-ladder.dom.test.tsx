// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Pack, Product, Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #699 (ADR-107): the package ladder on the item page. Single unit first since issue #709 (D99 = A), each level says what it holds and the pieces in
// words; a change shows the before and after in words before it is saved; a size that is not whole is refused in plain words;
// a bigger package is added on top and the biggest removed. Example items, not a real shop.

vi.setConfig({ testTimeout: 20000 });

const piece: Unit = { id: 'u-pc', name: 'Piece', active: true, version: 1 };
const box: Unit = { id: 'u-box', name: 'Box', active: true, version: 1 };
const carton: Unit = { id: 'u-ctn', name: 'Carton', active: true, version: 1 };
const bale: Unit = { id: 'u-bale', name: 'Bale', active: true, version: 1 };
const dozen: Unit = { id: 'u-dz', name: 'Dozen', active: true, version: 1, is_package: true, standard: true, fixed_count: 12 };
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
      listUnits: () => Promise.resolve([piece, dozen, box, carton, bale]),
      listPacks: () => Promise.resolve(packs),
      updatePack: (...a: unknown[]) => updatePack(...a),
      addPack: (...a: unknown[]) => addPack(...a),
    },
  };
});

const { ItemLadder, drawPackage, lookInsideWords } = await import('./package-ladder');
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
  // Issue #709 (D99 = A, ADR-107 rule 8): built smallest first, the single unit with its HelpTip, then each bigger package.
  it('shows the single unit first, then every bigger level with the pieces in words, and the chain', () => {
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    const names = [...document.querySelectorAll('.ladder-name')].map((n) => n.textContent);
    expect(names).toEqual(['1 Piece Single unit', '1 Box', '1 Carton']);
    expect(screen.getByRole('list', { name: 'What one package holds, single unit first' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'More about Single unit' })).toBeTruthy();
    expect(screen.getByLabelText('So 1 carton = 120 pieces')).toBeTruthy();
    expect(screen.getByText(/1 carton → 12 boxes → each 10 pieces/)).toBeTruthy();
    expect(screen.getByText('The one thing a customer can buy on its own. Stock is kept in pieces.')).toBeTruthy();
    expect(screen.getByText('Does it come in a bigger pack?')).toBeTruthy();
  });

  // Issue #709 (ADR-110, D98): a dozen always holds 12, filled in and never typed.
  it('adds a dozen holding 12 of the biggest, with no number to type', async () => {
    addPack.mockResolvedValue({});
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    fireEvent.click(screen.getByRole('button', { name: 'Add a bigger package' }));
    const form = screen.getByTestId('ladder-add');
    const picker = await within(form).findByLabelText(/^The bigger package/);
    await waitFor(() => expect(within(picker).queryByRole('option', { name: 'Dozen' })).toBeTruthy());
    fireEvent.change(picker, { target: { value: 'u-dz' } });
    expect(within(form).getByTestId('ladder-add-fixed').textContent).toBe('1 dozen holds 12 cartons. A dozen always holds 12.');
    expect(within(form).queryByLabelText('How many cartons in one dozen')).toBeNull();
    fireEvent.click(within(form).getByRole('button', { name: 'Add it' }));
    await waitFor(() => expect(addPack).toHaveBeenCalledWith('p-1', { unit_id: 'u-dz', holds_unit_id: 'u-ctn', holds_qty: 12, purpose: 'both' }));
  });

  it('keeps Look inside biggest first (D99 = A)', () => {
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    const open = screen.getByLabelText('Open one');
    expect(within(open).getAllByRole('option').map((o) => o.textContent)).toEqual(['1 Carton', '1 Box']);
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

  // JUSTIFICATION-A3: the review of #710 asks for the drawing cap and the price warning; these are their tests.
  it('draws at most 60 of what a package holds and 1,500 marks in all, saying how many more (review of #710)', () => {
    const huge: Pack[] = [
      { unit_id: 'u-box', unit: 'Box', factor: '100000.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-pc', holds_unit: 'Piece', holds_qty: 100_000 },
      { unit_id: 'u-ctn', unit: 'Carton', factor: '200000.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-box', holds_unit: 'Box', holds_qty: 2 },
    ];
    const calls = { arc: 0, text: [] as string[] };
    const ctx = new Proxy({}, {
      get: (_t, key) => {
        if (key === 'arc') return () => { calls.arc += 1; };
        if (key === 'fillText') return (t: string) => { calls.text.push(t); };
        return () => undefined;
      },
      set: () => true,
    });
    const canvas = Object.assign(document.createElement('canvas'), { width: 960, height: 560 });
    canvas.getContext = (() => ctx) as unknown as HTMLCanvasElement['getContext'];
    drawPackage(canvas, ladderOf(socket, huge), 3);
    expect(calls.arc).toBeLessThanOrEqual(1_500);
    expect(calls.text).toContain('+99,941');

    // Round 4: the budget runs out inside a carton of a bale of 60 (each carton says its 41 boxes left), and the bale, drawn
    // last, still says how many cartons it has left: every carton drawn and those left make 60.
    const deep: Pack[] = [
      { unit_id: 'u-box', unit: 'Box', factor: '100.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-pc', holds_unit: 'Piece', holds_qty: 100 },
      { unit_id: 'u-ctn', unit: 'Carton', factor: '10000.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-box', holds_unit: 'Box', holds_qty: 100 },
      { unit_id: 'u-bale', unit: 'Bale', factor: '600000.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-ctn', holds_unit: 'Carton', holds_qty: 60 },
    ];
    calls.arc = 0;
    calls.text = [];
    drawPackage(canvas, ladderOf(socket, deep), 4);
    expect(calls.arc).toBeLessThanOrEqual(1_500);
    const baleLeft = Number((calls.text.at(-1) ?? '').replace(/[^\d]/g, ''));
    const cartonsDrawn = calls.text.length - 1;
    expect(baleLeft).toBeGreaterThan(0);
    expect(cartonsDrawn + baleLeft).toBe(60);
  });

  it('says before saving that a level with its own price needs someone who may change prices (review of #710)', () => {
    const priced = packs.map((k) => (k.unit_id === 'u-ctn' ? { ...k, has_own_price: true } : k));
    draw(<ItemLadder product={socket} packs={priced} mayManage mayPrice={false} />);
    fireEvent.click(screen.getByRole('button', { name: 'One more piece in a box' }));
    fireEvent.click(screen.getByRole('button', { name: 'One more piece in a box' }));
    expect(screen.getByTestId('ladder-priced').textContent).toContain('The carton has its own price');
    expect(within(screen.getByTestId('ladder-check')).getByRole('button', { name: 'OK, save' }).hasAttribute('disabled')).toBe(true);
  });

  it('opens an item whose stored packs loop, a box holding the carton that holds it, without freezing (review of #710)', () => {
    const looped: Pack[] = [
      { unit_id: 'u-box', unit: 'Box', factor: '10.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-ctn', holds_unit: 'Carton', holds_qty: 2 },
      { unit_id: 'u-ctn', unit: 'Carton', factor: '120.000', active: true, version: 1, purpose: 'both', holds_unit_id: 'u-box', holds_unit: 'Box', holds_qty: 12 },
    ];
    draw(<ItemLadder product={socket} packs={looped} mayManage />);
    expect(screen.getByTestId('ladder-loop').textContent).toBe('These packages point at each other. Fix the sizes on this page.');
    expect(screen.getByText(/1 carton → 12 boxes → each 2 cartons/)).toBeTruthy();
    cleanup();
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    expect(screen.queryByTestId('ladder-loop')).toBeNull();
    expect(lookInsideWords(ladderOf(socket, looped), 'u-ctn', 'Piece')).toBe('1 carton holds 12 boxes, each holding 2 cartons. That is 120 pieces.');
  });

  it('says what one package holds when it is opened up', () => {
    expect(lookInsideWords(ladderOf(socket, packs), 'u-ctn', 'Piece')).toBe('1 carton holds 12 boxes, each holding 10 pieces. That is 120 pieces.');
    draw(<ItemLadder product={socket} packs={packs} mayManage />);
    expect(screen.getByRole('img', { name: '1 carton holds 12 boxes, each holding 10 pieces. That is 120 pieces.' })).toBeTruthy();
  });
});
