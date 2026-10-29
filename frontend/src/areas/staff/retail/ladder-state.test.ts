import { describe, expect, it } from 'vitest';
import type { Pack } from '../../../api/retail';
import { breakdown, chain, changeWords, changesOf, countMilli, everyLevel, holdsProblem, ladderOf, perPieceMinor, soOne, usualBuyingPack } from './ladder-state';
import { parseCount } from './maths';

// Issue #699 (ADR-107): the ladder's words and sums. Example items, not a real shop.

const product = { unit: 'Piece', unit_id: 'u-piece' };
const pack = (unit: string, factor: string, holds?: [string, string, number], more: Partial<Pack> = {}): Pack => ({
  unit_id: `u-${unit.toLowerCase()}`,
  unit,
  factor,
  active: true,
  purpose: 'both',
  ...(holds ? { holds_unit_id: holds[0], holds_unit: holds[1], holds_qty: holds[2] } : {}),
  ...more,
});
const socket = [
  pack('Box', '10.000', ['u-piece', 'Piece', 10]),
  pack('Bale', '480.000', ['u-carton', 'Carton', 4]),
  pack('Carton', '120.000', ['u-box', 'Box', 12]),
];
const levels = [{ unit: 'Carton', factor: '120.000' }, { unit: 'Box', factor: '10.000' }];

describe('the ladder', () => {
  it('is biggest first, each level saying what it holds', () => {
    const rungs = ladderOf(product, socket);
    expect(rungs.map((r) => `${r.unit} ${r.holdsQty} ${r.holdsUnit}`)).toEqual(['Bale 4 Carton', 'Carton 12 Box', 'Box 10 Piece']);
    expect(soOne(rungs[1]!, 'Piece')).toBe('So 1 carton = 120 pieces');
    expect(soOne(rungs[0]!, 'Piece')).toBe('So 1 bale = 480 pieces');
  });

  it('reads a pack made before as holding its pieces', () => {
    const rungs = ladderOf(product, [pack('Carton', '24.000')]);
    expect(rungs[0]).toMatchObject({ holdsUnitId: 'u-piece', holdsUnit: 'Piece', holdsQty: '24' });
  });

  it('says the chain from the biggest down in words', () => {
    expect(chain(ladderOf(product, socket), 'Piece')).toEqual({
      words: '1 bale → 4 cartons → each 12 boxes → each 10 pieces',
      total: '480 pieces in one bale',
    });
    expect(chain([], 'Piece')).toBeNull();
  });

  it('shows the before and after of a change to a middle level, every level above it too', () => {
    const rungs = ladderOf(product, socket);
    const changes = changesOf(rungs, 'u-box', { unitId: 'u-piece', qty: '12' }, 'u-piece');
    expect(changes.map((c) => changeWords(c, 'Piece'))).toEqual([
      '1 box will be 12 pieces. It was 10.',
      '1 carton will be 144 pieces. It was 120.',
      '1 bale will be 576 pieces. It was 480.',
    ]);
    // What a carton holds, changed to pieces with the same total, changes nothing.
    expect(changesOf(rungs, 'u-carton', { unitId: 'u-piece', qty: '120' }, 'u-piece')).toEqual([]);
  });

  it('refuses a number that is not 2 or more whole of the level below, in plain words', () => {
    const box = ladderOf(product, socket)[2]!;
    expect(holdsProblem('12', box)).toBeNull();
    for (const bad of ['', '1', '2.5', 'ten', '0']) {
      expect(holdsProblem(bad, box)).toBe('A box must hold 2 or more whole pieces. Type a whole number like 10.');
    }
  });
});

describe('stock at every level', () => {
  it('reads 275 pieces as cartons, boxes and pieces', () => {
    expect(everyLevel('275.000', 'Piece', levels)).toBe('2 Cartons, 3 Boxes and 5 Pieces (275 Pieces)');
    expect(everyLevel('240.000', 'Piece', levels)).toBe('2 Cartons (240 Pieces)');
    expect(everyLevel('7.000', 'Piece', levels)).toBe('7 Pieces');
    expect(everyLevel('0.000', 'Piece', levels)).toBe('0 Pieces');
    expect(everyLevel('-130.000', 'Piece', levels)).toBe('1 Carton and 1 Box short (130 Pieces short)');
    expect(everyLevel('12.000', 'Piece', undefined)).toBe('12 Pieces');
  });

  it('splits stock into a box per level and adds a count back up', () => {
    expect(breakdown('275.000', 'Piece', levels).map((p) => `${p.n} ${p.unit}`)).toEqual(['2 Carton', '3 Box', '5000 Piece']);
    expect(countMilli(['2', '3', '5'], levels, parseCount)).toBe(275_000);
    expect(countMilli(['', '', '2.5'], levels, parseCount)).toBe(2_500);
    expect(countMilli(['', '', ''], levels, parseCount)).toBeNull();
    expect(countMilli(['1.5', '', ''], levels, parseCount)).toBeNull();
  });
});

describe('the usual buying level', () => {
  it('is the pack marked usual while it is still bought', () => {
    const packs = [pack('Box', '10.000'), pack('Carton', '120.000', undefined, { usual_buy: true })];
    expect(usualBuyingPack({ packs } as never)?.unit).toBe('Carton');
    expect(usualBuyingPack({ packs: [pack('Carton', '120.000', undefined, { usual_buy: true, purpose: 'sell' })] } as never)).toBeUndefined();
  });

  it('gives the cost of a piece from the price of a carton, rounded half up', () => {
    expect(perPieceMinor(600_000, 120_000n)).toBe(5_000);
    expect(perPieceMinor(240_000, 120_000n)).toBe(2_000);
    expect(perPieceMinor(1_000, 3_000n)).toBe(333);
    expect(perPieceMinor(5, 2_000n)).toBe(3);
  });
});
