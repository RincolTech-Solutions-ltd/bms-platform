// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Unit } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #709 (D92, D93, D98, ADR-110): the Units page in two parts, Single units and Packages, each with its heading, its
// HelpTip and its own add button; a package says what it usually holds in plain words, a dozen always 12, and the add form of
// a package asks how many it usually holds. The standard units come in the server's order. Every name is fabricated.

vi.setConfig({ testTimeout: 20000 });

const piece: Unit = { id: 'u-pc', name: 'Piece', active: true, version: 1, standard: true, is_package: false };
const metre: Unit = { id: 'u-m', name: 'Metre', active: true, version: 1, standard: true, is_package: false };
const dozen: Unit = { id: 'u-dz', name: 'Dozen', active: true, version: 1, standard: true, is_package: true, fixed_count: 12 };
const box: Unit = { id: 'u-box', name: 'Box', active: true, version: 1, standard: true, is_package: true };
const roll: Unit = { id: 'u-roll', name: 'Roll', active: true, version: 2, standard: true, is_package: true, usual_pack: { size: '100.000', of_unit_id: 'u-m', of_unit: 'Metre', purpose: 'both' } };
const bulb: Unit = { id: 'u-bulb', name: 'Bulb', active: true, version: 1, standard: false, is_package: false };
const units = [piece, metre, dozen, box, roll, bulb];
const createUnit = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});
vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, listUnits: () => Promise.resolve(units), createUnit: (...a: unknown[]) => createUnit(...a) } };
});

const { UnitsPage } = await import('./catalogue-lists');
const owner = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };

function draw() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: owner, branch: owner.branches?.[0]?.id ?? '' }}><UnitsPage /></StaffContext.Provider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  createUnit.mockReset();
});

const names = (part: HTMLElement) => within(part).getAllByRole('listitem').map((li) => li.querySelector('strong')?.textContent);

describe('the Units page in two parts (issue #709)', () => {
  it('shows Single units and Packages, each with its hint and HelpTip, in the order the server sends', async () => {
    draw();
    const single = await screen.findByRole('region', { name: 'Single units' });
    const packages = screen.getByRole('region', { name: 'Packages' });
    await waitFor(() => expect(names(single)).toEqual(['Piece', 'Metre', 'Bulb']));
    expect(names(packages)).toEqual(['Dozen', 'Box', 'Roll']);
    expect(within(single).getByText('One piece, one metre, one litre.')).toBeTruthy();
    expect(within(packages).getByText('Things that hold single units.')).toBeTruthy();
    expect(within(single).getByRole('button', { name: 'More about Single unit' })).toBeTruthy();
    expect(within(packages).getByRole('button', { name: 'More about Package' })).toBeTruthy();
    expect(within(single).getByRole('button', { name: 'Add a single unit' })).toBeTruthy();
    expect(within(packages).getByRole('button', { name: 'Add a package' })).toBeTruthy();
  });

  it('says what each package holds in plain words, a dozen always 12', async () => {
    draw();
    const packages = await screen.findByRole('region', { name: 'Packages' });
    await within(packages).findByText('Always holds 12. It cannot change.');
    expect(within(packages).getByText('Usually holds 100 metres. Each item can be different.')).toBeTruthy();
    expect(within(packages).getByText('Used for: buying and selling')).toBeTruthy();
    expect(within(packages).getByText('Each item says how many it holds.')).toBeTruthy();
    expect(screen.queryByText(/Usual pack/)).toBeNull();
    // A dozen's 12 is fixed, so it has no Usually holds to change; a roll has.
    expect(screen.queryByRole('button', { name: 'What one Dozen usually holds' })).toBeNull();
    expect(screen.getByRole('button', { name: 'What one Roll usually holds' })).toBeTruthy();
  });

  it('adds a single unit by its name alone', async () => {
    createUnit.mockResolvedValue({ id: 'u-tray', name: 'Tray', active: true, version: 1 });
    draw();
    const single = await screen.findByRole('region', { name: 'Single units' });
    fireEvent.click(within(single).getByRole('button', { name: 'Add a single unit' }));
    fireEvent.change(within(single).getByLabelText('Name of the new single unit'), { target: { value: 'Tray' } });
    expect(within(single).queryByLabelText(/^How many does it usually hold/)).toBeNull();
    fireEvent.click(within(single).getAllByRole('button', { name: 'Add a single unit' }).at(-1)!);
    await waitFor(() => expect(createUnit).toHaveBeenCalledWith('Tray', undefined));
  });

  it('asks how many a new package usually holds, and of which single unit', async () => {
    createUnit.mockResolvedValue({ id: 'u-sack', name: 'Sack', active: true, version: 1 });
    draw();
    const packages = await screen.findByRole('region', { name: 'Packages' });
    await waitFor(() => expect(names(packages)).toHaveLength(3));
    fireEvent.click(within(packages).getByRole('button', { name: 'Add a package' }));
    fireEvent.change(within(packages).getByLabelText('Name of the new package'), { target: { value: 'Sack' } });
    const of = within(packages).getByLabelText('Of which single unit') as HTMLSelectElement;
    expect([...of.options].map((o) => o.text)).toEqual(['Piece', 'Metre', 'Bulb']);
    fireEvent.click(within(packages).getAllByRole('button', { name: 'Add a package' }).at(-1)!);
    expect(createUnit).not.toHaveBeenCalled();
    fireEvent.change(within(packages).getByLabelText(/^How many does it usually hold/), { target: { value: '50' } });
    fireEvent.click(within(packages).getAllByRole('button', { name: 'Add a package' }).at(-1)!);
    await waitFor(() => expect(createUnit).toHaveBeenCalledWith('Sack', { size: '50', of_unit_id: 'u-pc', purpose: 'both' }));
  });
});
