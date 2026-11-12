import { describe, expect, it } from 'vitest';
import type { LoanRenewalQuote } from '../../../api/lending';
import { bpToPercent, percentToBp, rateWords, renewStep, termWords, type RenewDraft } from './renew-state';

// The Renew form's checks (issue #918). Fabricated figures: a one-month loan of UGX 1,000,000 at 10% a month with the month's
// interest of UGX 100,000 due.

const today = '2026-11-12';

const base: LoanRenewalQuote = {
  principal_outstanding_minor: 1_000_000,
  interest_due_minor: 100_000,
  fees_due_minor: 0,
  penalties_due_minor: 0,
  interest_first: true,
  minimum_collect_minor: 100_000,
  suggested_collect_minor: 100_000,
  interest_rate_bp: 1000,
  rate_unit: 'per_month',
  term_unit: 'month',
  min_term_count: 1,
  max_term_count: 3,
} as LoanRenewalQuote;

const draft = (patch: Partial<RenewDraft> = {}): RenewDraft => ({
  valueDate: today,
  collect: '100000',
  method: 'cash',
  term: '1',
  rate: '',
  reason: '',
  startedOn: today,
  ...patch,
});

describe('renewStep', () => {
  it('is ready with the interest collected, the term in range and the rate kept', () => {
    const s = renewStep(draft(), base, 'UGX', today);
    expect(s.ready).toBe(true);
    expect(s.collectMinor).toBe(100_000);
    expect(s.termCount).toBe(1);
    expect(s.rateBp).toBeNull();
  });

  it('asks for the interest first when the product does', () => {
    expect(renewStep(draft({ collect: '' }), base, 'UGX', today).collectProblem).toMatch(/Collect at least UGX 100,000/);
    expect(renewStep(draft({ collect: '60000' }), base, 'UGX', today).ready).toBe(false);
  });

  it('takes nothing collected when the product renews without the interest first', () => {
    const s = renewStep(draft({ collect: '' }), { ...base, interest_first: false, minimum_collect_minor: 0 }, 'UGX', today);
    expect(s.ready).toBe(true);
    expect(s.collectMinor).toBe(0);
  });

  it('refuses an amount that pays the whole loan off', () => {
    expect(renewStep(draft({ collect: '1100000' }), base, 'UGX', today).collectProblem).toMatch(/pays the whole loan off/);
  });

  it('keeps the term inside the product and the rate at or below the loan', () => {
    expect(renewStep(draft({ term: '4' }), base, 'UGX', today).termProblem).toBe('The product allows 1 to 3 months.');
    expect(renewStep(draft({ term: 'two' }), base, 'UGX', today).termProblem).toMatch(/whole number/);
    expect(renewStep(draft({ rate: '12' }), base, 'UGX', today).rateProblem).toBe("At most 10%, the loan's rate now.");
    expect(renewStep(draft({ rate: '7.5' }), base, 'UGX', today).rateBp).toBe(750);
  });

  it('refuses a date after today and is never ready before the loan is read', () => {
    expect(renewStep(draft({ valueDate: '2026-11-13' }), base, 'UGX', today).dateProblem).toMatch(/today or earlier/);
    expect(renewStep(draft(), undefined, 'UGX', today).ready).toBe(false);
  });
});

describe('the words of a renewal', () => {
  it('turns percents and basis points both ways', () => {
    expect(percentToBp('10')).toBe(1000);
    expect(percentToBp('7.5')).toBe(750);
    expect(percentToBp('7.05')).toBe(705);
    expect(percentToBp('7.555')).toBeNull();
    expect(bpToPercent(750)).toBe('7.5');
    expect(bpToPercent(1010)).toBe('10.1');
  });

  it('says the rate and the term plainly', () => {
    expect(rateWords(1000, 'per_month')).toBe('10% a month');
    expect(termWords(1, 'month')).toBe('1 month');
    expect(termWords(3, 'month')).toBe('3 months');
  });
});
