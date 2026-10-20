// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import type { ControlsGroup, ControlsPattern } from '../../../api/retail-controls';
import {
  CONTROLS_LEVELS, csvField, csvOf, dayWords, daysIn, groupLine, groupTitle, groupsCsv, orderedCards, patternSentence, periodRange, previousWords, rateWords,
  readControlsChain, recordsCsv, selectGroup, shareWords, trendWords, whoWords, writeControlsChain,
} from './controls-state';

// The rules behind the Controls page (issue #357). Fabricated names and amounts.

describe('the days of a period', () => {
  const none = { from: '', to: '' };
  it('is today, Monday to today, the 1st to today, or the days typed', () => {
    // 2026-10-21 is a Wednesday.
    expect(periodRange('today', '2026-10-21', none)).toEqual({ from: '2026-10-21', to: '2026-10-21' });
    expect(periodRange('week', '2026-10-21', none)).toEqual({ from: '2026-10-19', to: '2026-10-21' });
    expect(periodRange('week', '2026-10-19', none)).toEqual({ from: '2026-10-19', to: '2026-10-19' });
    // A Sunday belongs to the week that began on the Monday before.
    expect(periodRange('week', '2026-10-25', none)).toEqual({ from: '2026-10-19', to: '2026-10-25' });
    expect(periodRange('month', '2026-10-21', none)).toEqual({ from: '2026-10-01', to: '2026-10-21' });
    expect(periodRange('custom', '2026-10-21', { from: '2026-09-01', to: '2026-09-30' })).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('counts both ends and says what the trend is set against', () => {
    expect(daysIn({ from: '2026-10-21', to: '2026-10-21' })).toBe(1);
    expect(daysIn({ from: '2026-10-19', to: '2026-10-25' })).toBe(7);
    expect(previousWords({ from: '2026-10-21', to: '2026-10-21' })).toBe('the day before');
    expect(previousWords({ from: '2026-10-19', to: '2026-10-25' })).toBe('the 7 days before');
    expect(dayWords('2026-10-19')).toBe('Monday 19 October 2026');
  });
});

describe('the words of a card', () => {
  const before = 'the 7 days before';
  it('says up, down, the same, or none, always against the days before', () => {
    expect(trendWords(0, 0, before)).toBe('None, and none in the 7 days before');
    expect(trendWords(4, 0, before)).toBe('Up: 4, none in the 7 days before');
    expect(trendWords(5, 5, before)).toBe('Same as in the 7 days before (5)');
    expect(trendWords(8, 5, before)).toBe('Up 3, from 5 in the 7 days before');
    expect(trendWords(2, 7, before)).toBe('Down 5, from 7 in the 7 days before');
  });

  it('gives a rate per 100 sales, or says there was nothing to set it against', () => {
    expect(rateWords('2.5')).toBe('2.5 per 100 sales');
    expect(rateWords(undefined)).toBe('No sales to compare with');
    expect(shareWords(125)).toBe('1.25% of the value of sales');
    expect(shareWords(undefined)).toBe('');
  });

  it('draws the cards in the fixed order and leaves out one the server did not send', () => {
    const cards = [{ metric: 'discounts' }, { metric: 'voided_sales' }, { metric: 'returns' }];
    expect(orderedCards(cards).map((c) => c.metric)).toEqual(['voided_sales', 'returns', 'discounts']);
    expect(orderedCards(undefined)).toEqual([]);
  });
});

describe('a group of a table', () => {
  const user = (over: Partial<ControlsGroup> = {}): ControlsGroup => ({ key: 'u1', label: 'Test Seller One', user_status: 'active', count: 5, value_minor: 1500, sales_count: 18, rate_per_100: '27.8', ...over });

  it('names a deactivated person and one who has not signed in yet', () => {
    expect(groupTitle('user', user())).toBe('Test Seller One');
    expect(groupTitle('user', user({ user_status: 'deactivated' }))).toBe('Test Seller One (deactivated)');
    expect(groupTitle('user', user({ user_status: 'invited' }))).toBe('Test Seller One (not yet signed in)');
    expect(groupTitle('day', { key: '2026-10-19', label: '2026-10-19', day: '2026-10-19' })).toBe('Monday 19 October 2026');
    expect(whoWords({ user_name: 'Test Seller Two', user_status: 'deactivated' })).toBe('Test Seller Two (deactivated)');
    expect(whoWords({})).toBe('Unknown person');
  });

  it('writes the count, the value and the rate on one line', () => {
    const money = (m: number) => `UGX ${m}`;
    expect(groupLine(user(), 'sales', money)).toBe('5 sales, UGX 1500, 27.8 per 100 sales');
    expect(groupLine(user({ count: 1, rate_per_100: undefined }), 'sales', money)).toBe('1 sale, UGX 1500, no sales of their own to compare with');
  });

  it('passes what was opened to the level under it; a day narrows to that day', () => {
    expect(selectGroup({}, 'branch', { key: 'b1' })).toEqual({ branchId: 'b1' });
    expect(selectGroup({ branchId: 'b1' }, 'user', { key: 'u1' })).toEqual({ branchId: 'b1', userId: 'u1' });
    expect(selectGroup({}, 'reason', { key: 'wrong item' })).toEqual({ reason: 'wrong item' });
    expect(selectGroup({ userId: 'u1' }, 'day', { key: '2026-10-19', day: '2026-10-19' })).toEqual({ userId: 'u1', from: '2026-10-19', to: '2026-10-19' });
  });
});

describe('a repeat pattern in plain words', () => {
  const base: ControlsPattern = { subject: 'user', key: 'u1', name: 'Test Seller One', user_status: 'active', metric: 'voided_sales', count: 5, out_of: 13, rate_per_100: '38.5', shop_rate_per_100: '14.0', times: '2.8' };
  it('says who, what, out of how many, and how many times the shop', () => {
    expect(patternSentence(base, 'Test Branch Gayaza')).toBe("Test Seller One cancelled 5 of the 13 sales they entered at Test Branch Gayaza (38.5 per 100). The shop's own is 14.0, so 2.8 times.");
    expect(patternSentence({ ...base, user_status: 'deactivated', metric: 'returns' }, 'Test Branch Gayaza')).toContain('Test Seller One (deactivated) took goods back on 5 of the 13 sales');
  });

  it('says it of the lines of an item', () => {
    const item: ControlsPattern = { ...base, subject: 'product', name: 'Test Cable 2m', user_status: undefined, out_of: 20 };
    expect(patternSentence(item, 'Test Branch Gayaza')).toContain('5 of the 20 lines of Test Cable 2m sold at Test Branch Gayaza were on cancelled sales');
    expect(patternSentence({ ...item, metric: 'below_cost' }, 'Test Branch Gayaza')).toContain('had a price under cost');
  });
});

describe('the CSV of what is on screen', () => {
  it('quotes commas, quotes and line breaks', () => {
    expect(csvField('plain')).toBe('plain');
    expect(csvField('a, b')).toBe('"a, b"');
    expect(csvField('say "hi"')).toBe('"say ""hi"""');
    expect(csvField('two\nlines')).toBe('"two\nlines"');
    expect(csvField(1500)).toBe('1500');
    expect(csvField(undefined)).toBe('');
  });

  it('turns a text a spreadsheet would run as a formula into plain text, but leaves a number alone', () => {
    expect(csvField('=1+1')).toBe("'=1+1");
    expect(csvField('+256700000001')).toBe("'+256700000001");
    expect(csvField('-cmd')).toBe("'-cmd");
    expect(csvField('@sum')).toBe("'@sum");
    expect(csvField(-5)).toBe('-5');
  });

  it('writes the header and one line a group, with the deactivated person named', () => {
    const rows: ControlsGroup[] = [{ key: 'u2', label: 'Test Seller Two', user_status: 'deactivated', count: 1, value_minor: 300, sales_count: 30, rate_per_100: '3.3' }];
    expect(groupsCsv('user', rows)).toBe('Person,Count,Value,Sales entered,Per 100 sales\r\nTest Seller Two (deactivated),1,300,30,3.3\r\n');
    expect(csvOf([['a', 'b']])).toBe('a,b\r\n');
  });

  it('writes the records with the sale number, the time and the reason', () => {
    const text = recordsCsv([{ sale_no: 'S-1', at: '2026-10-19T09:15:00Z', branch_name: 'Test Branch Gayaza', user_name: 'Test Seller One', user_status: 'active', reason: 'Test wrong item', value_minor: 300 }]);
    expect(text).toBe('Sale,Credit note,Time,Shop,Person,Reason,Item,Value\r\nS-1,,2026-10-19T09:15:00Z,Test Branch Gayaza,Test Seller One,Test wrong item,,300\r\n');
  });
});

describe('the chain a person chose', () => {
  beforeEach(() => localStorage.clear());
  it('starts with the usual order, remembers a choice per user and ignores a garbled one', () => {
    expect(readControlsChain('u1')).toEqual([...CONTROLS_LEVELS]);
    writeControlsChain('u1', ['reason', 'user']);
    expect(readControlsChain('u1')).toEqual(['reason', 'user']);
    expect(readControlsChain('u2')).toEqual([...CONTROLS_LEVELS]);
    localStorage.setItem('bms.controlsChain.u3', JSON.stringify(['user', 'user']));
    expect(readControlsChain('u3')).toEqual([...CONTROLS_LEVELS]);
    localStorage.setItem('bms.controlsChain.u4', JSON.stringify(['item']));
    expect(readControlsChain('u4')).toEqual([...CONTROLS_LEVELS]);
  });
});
