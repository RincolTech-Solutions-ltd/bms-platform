import type { LoanRenewalQuote, PaymentMethod } from '../../../api/lending';
import { amountRule, isIsoDate, money, parseAmount } from './loan-state';

// The checks of the Renew form (issue #918, ADR-128), apart from the screen so they are tested alone. The server checks them
// again: the interest due is collected first when the product asks for it, the amount never pays the whole loan off, the term
// stays inside the product's limits and the rate is never above the loan's own.

export interface RenewDraft {
  valueDate: string;
  /** Collected now as a repayment; empty means nothing (the interest was paid already). */
  collect: string;
  method: PaymentMethod;
  term: string;
  /** The new rate as a percent typed by a person ("10" or "7.5"); empty keeps the loan's rate. */
  rate: string;
  reason: string;
  startedOn: string;
}

export interface RenewStep {
  collectMinor: number;
  termCount: number | null;
  rateBp: number | null;
  dateProblem: string | null;
  collectProblem: string | null;
  termProblem: string | null;
  rateProblem: string | null;
  ready: boolean;
}

/** "10" or "7.5" percent as basis points (1000 or 750); null when it is not a percent with at most two decimals. */
export function percentToBp(text: string): number | null {
  const t = text.trim();
  const m = /^(\d{1,4})(?:\.(\d{1,2}))?$/.exec(t);
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** Basis points as the percent a person reads: 1000 is "10", 750 is "7.5". */
export const bpToPercent = (bp: number | undefined): string => {
  const v = bp ?? 0;
  const whole = Math.floor(v / 100);
  const rest = v % 100;
  return rest === 0 ? String(whole) : `${whole}.${String(rest).padStart(2, '0').replace(/0$/, '')}`;
};

const UNIT_WORDS: Record<string, string> = { per_term: 'for the term', per_day: 'a day', per_week: 'a week', per_month: 'a month', per_year: 'a year' };

/** "10% a month". */
export const rateWords = (bp: number | undefined, unit: string | undefined): string => `${bpToPercent(bp)}% ${UNIT_WORDS[unit ?? ''] ?? ''}`.trim();

/** "1 month", "3 months". */
export const termWords = (count: number | undefined, unit: string | undefined): string =>
  `${count ?? 0} ${unit ?? 'month'}${count === 1 ? '' : 's'}`;

/**
 * The draft against the loan as it stands (`base`, the quote with nothing collected): the amount, the term and the rate. Without
 * the base quote yet, nothing is ready.
 */
export function renewStep(draft: RenewDraft, base: LoanRenewalQuote | undefined, currency: string | undefined, today: string): RenewStep {
  const dateProblem = !isIsoDate(draft.valueDate) ? 'Enter the date of the renewal.' : draft.valueDate > today ? 'The date must be today or earlier.' : null;

  let collectMinor = 0;
  let collectProblem: string | null = null;
  if (draft.collect.trim() !== '' && draft.collect.trim() !== '0') {
    const parsed = parseAmount(draft.collect, currency);
    if (parsed === null) collectProblem = `Enter the amount collected, ${amountRule(currency)}.`;
    else collectMinor = parsed;
  }
  if (base && collectProblem === null) {
    const owed =
      (base.principal_outstanding_minor ?? 0) + (base.interest_due_minor ?? 0) + (base.fees_due_minor ?? 0) + (base.penalties_due_minor ?? 0);
    if (base.interest_first && collectMinor < (base.minimum_collect_minor ?? 0)) {
      collectProblem = `Collect at least ${money(base.minimum_collect_minor, currency)}: the interest due is paid before the loan is renewed.`;
    } else if (collectMinor >= owed) {
      collectProblem = 'That pays the whole loan off, so there is nothing to renew. Record it as a repayment instead.';
    }
  }

  let termCount: number | null = null;
  let termProblem: string | null = null;
  const min = base?.min_term_count ?? 1;
  const max = base?.max_term_count ?? 3660;
  if (!/^\d{1,4}$/.test(draft.term.trim())) termProblem = 'Enter the new term as a whole number.';
  else {
    termCount = Number(draft.term.trim());
    if (termCount < min || termCount > max) {
      termProblem = `The product allows ${min} to ${max} ${base?.term_unit ?? 'month'}s.`;
      termCount = null;
    }
  }

  let rateBp: number | null = null;
  let rateProblem: string | null = null;
  if (draft.rate.trim() !== '') {
    rateBp = percentToBp(draft.rate);
    if (rateBp === null) rateProblem = 'Enter the rate as a percent, for example 10 or 7.5.';
    else if (base && rateBp > (base.interest_rate_bp ?? 0)) {
      rateProblem = `At most ${bpToPercent(base.interest_rate_bp)}%, the loan's rate now.`;
      rateBp = null;
    }
  }

  return {
    collectMinor,
    termCount,
    rateBp,
    dateProblem,
    collectProblem,
    termProblem,
    rateProblem,
    ready: !!base && dateProblem === null && collectProblem === null && termProblem === null && rateProblem === null,
  };
}
