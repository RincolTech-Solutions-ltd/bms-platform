import type { DraftReason, ItemDraftRow, LoanDraftRow } from '../../../api/setup-helper';
import { IMPORT_COLUMNS } from '../lending/existing-loans-state';

// Pure helpers of the setup helper (issue #626, ADR-102): the drafted rows as the owner edits them (amounts as typed, in the
// currency's major units), the import file they become, and why there are none, in plain words. The file is exactly what a
// person could have typed into the import, so the import's check and its rules decide what is added; nothing here saves.

/** One drafted item, as the owner edits it. */
export interface ItemEdit {
  description: string;
  category: string;
  unit: string;
  sell: string;
  cost: string;
  packUnit: string;
  packFactor: string;
}

export interface PaymentEdit {
  date: string;
  amount: string;
}

/** One drafted loan, as the owner edits it. A member in the app is named by number; a new one by name and phone. */
export interface LoanEdit {
  ref: string;
  memberNo: string;
  memberFullName: string;
  memberName: string;
  memberPhone: string;
  principal: string;
  date: string;
  term: string;
  agreedTotal: string;
  totalWorkedOut: boolean;
  ratePercent: string;
  payments: PaymentEdit[];
}

/** Minor units as the import file writes them: 5500, or 120.50 for a currency with two decimals; empty for none. */
export function majorText(minor: number | null | undefined, exponent: number): string {
  if (minor === null || minor === undefined) return '';
  if (exponent <= 0) return String(minor);
  const s = String(Math.abs(minor)).padStart(exponent + 1, '0');
  return `${minor < 0 ? '-' : ''}${s.slice(0, -exponent)}.${s.slice(-exponent)}`;
}

export function fromItemDraft(rows: ItemDraftRow[], exponent: number): ItemEdit[] {
  return rows.map((r) => ({
    description: r.description,
    category: r.category,
    unit: r.unit,
    sell: majorText(r.sell_price_minor, exponent),
    cost: majorText(r.cost_price_minor, exponent),
    packUnit: r.pack_unit ?? '',
    packFactor: r.pack_factor ?? '',
  }));
}

export function fromLoanDraft(loans: LoanDraftRow[], exponent: number): LoanEdit[] {
  return loans.map((l) => ({
    ref: l.ref,
    memberNo: l.member_no ?? '',
    memberFullName: l.member_full_name ?? '',
    memberName: l.member_name ?? '',
    memberPhone: l.member_phone ?? '',
    principal: majorText(l.principal_minor, exponent),
    date: l.disbursement_date ?? '',
    term: l.term_months === null || l.term_months === undefined ? '' : String(l.term_months),
    agreedTotal: majorText(l.agreed_total_minor, exponent),
    totalWorkedOut: l.total_worked_out,
    ratePercent: l.rate_percent ?? '',
    payments: (l.payments ?? []).map((p) => ({ date: p.date ?? '', amount: majorText(p.amount_minor, exponent) })),
  }));
}

/** One cell of a CSV file: quoted when it holds a comma, a quote or a line break. */
export function csvCell(value: string): string {
  const v = value.replace(/\r?\n/g, ' ').trim();
  return /[",;\t]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

const line = (cells: string[]) => cells.map(csvCell).join(',');

/**
 * The item import file of the drafted items: the buying price column only for someone who may see buying prices (the import
 * refuses it otherwise), and the pack columns only when an item has a pack (they need the pack capability).
 */
export function itemsCsv(rows: ItemEdit[], withCost: boolean): string {
  const withPack = rows.some((r) => r.packUnit.trim() !== '' || r.packFactor.trim() !== '');
  const header = ['description', 'category', 'unit', ...(withCost ? ['cost_price'] : []), 'sell_price', ...(withPack ? ['pack_unit', 'pack_factor'] : [])];
  const body = rows.map((r) =>
    line([r.description, r.category, r.unit, ...(withCost ? [r.cost] : []), r.sell, ...(withPack ? [r.packUnit, r.packFactor] : [])]),
  );
  return [line(header), ...body].join('\n') + '\n';
}

/** The columns of the loans file the helper writes: the import's own, and a new member's name and phone. */
export const DRAFT_LOAN_COLUMNS = [...IMPORT_COLUMNS.slice(0, 3), 'member_name', 'member_phone', ...IMPORT_COLUMNS.slice(3)];

/** The existing loans import file of the drafted loans: a loan row each, then a repayment row for each payment, in cash. */
export function loansCsv(loans: LoanEdit[], productCode: string): string {
  const row = (values: Record<string, string>) => line(DRAFT_LOAN_COLUMNS.map((c) => values[c] ?? ''));
  const out = [line(DRAFT_LOAN_COLUMNS)];
  for (const l of loans) {
    const known = l.memberNo.trim() !== '';
    out.push(row({
      kind: 'loan',
      ref: l.ref,
      member_no: l.memberNo,
      member_name: known ? '' : l.memberName,
      member_phone: known ? '' : l.memberPhone,
      product_code: productCode,
      principal: l.principal,
      disbursement_date: l.date,
      term: l.term,
      agreed_total: l.agreedTotal,
    }));
    for (const p of l.payments) {
      if (p.date.trim() === '' && p.amount.trim() === '') continue;
      out.push(row({ kind: 'repayment', ref: l.ref, repayment_date: p.date, amount: p.amount, method: 'cash' }));
    }
  }
  return out.join('\n') + '\n';
}

/** Why there are no rows, in plain words, always with the way on: type it or import a file. */
export function reasonText(reason: DraftReason | null | undefined, noun: 'items' | 'loans'): string {
  const list = noun === 'items' ? 'your items' : 'the loans';
  switch (reason) {
    case 'no_provider':
      return `The app cannot read lists right now. Type ${list} below or import a file.`;
    case 'all_failed':
      return `The app could not read your list this time. Try again, or type ${list} below or import a file.`;
    case 'daily_cap':
      return `Your business has used today's lists. Type ${list} below or import a file, or try again tomorrow.`;
    case 'unreadable':
      return `There was nothing to read in that file. Take a photo of the page, or type ${list} below.`;
    default:
      return `No ${noun} were found in your list. Type them below or import a file.`;
  }
}

/** What a failed request says under the box: the server's words, then the same way on. */
export const FALLBACK = 'You can still type it below or import a file.';
