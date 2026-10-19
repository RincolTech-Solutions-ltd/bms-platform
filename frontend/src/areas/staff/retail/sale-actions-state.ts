import { RETAIL_CURRENCY, type Sale, type SalePaymentRequest } from '../../../api/retail';
import { formatMinor, parseMinor } from '../../../components/money';
import { MIN_REASON, reasonOf } from './account-state';

// Recording a payment on a credit sale (issue #326) and voiding a sale (issue #327): the pure rules the two forms show
// before anything is sent. The server decides every one of them again under the sale's lock (payment_exceeds_balance,
// sale_not_payable, sale_voided, sale_has_payments, sale_has_returns), so these only save a round trip.

export const PAY_METHODS: { value: SalePaymentRequest['method']; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

export { MIN_REASON };

/** An amount as a person types it, with no currency and no grouping: "12000". */
export const typedAmount = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY).replace(/^[A-Z]+ /, '').replace(/,/g, '');

/** What is still owed on the sale, never below zero. */
export const owedMinor = (sale: Pick<Sale, 'balance_minor'>): number => Math.max(0, sale.balance_minor ?? 0);

/** True for a live credit sale that still owes money; an imported (historical) sale takes no payment here. */
export function takesPayment(sale: Pick<Sale, 'payment_method' | 'status' | 'balance_minor' | 'historical'>): boolean {
  return sale.payment_method === 'credit' && sale.status === 'completed' && owedMinor(sale) > 0 && sale.historical !== true;
}

/** True for an imported credit sale that still owes: the action is not offered and a note says why. */
export const importedAndOwing = (sale: Pick<Sale, 'payment_method' | 'status' | 'balance_minor' | 'historical'>): boolean =>
  sale.payment_method === 'credit' && sale.status === 'completed' && owedMinor(sale) > 0 && sale.historical === true;

export interface PayForm {
  amount: string;
  method: SalePaymentRequest['method'];
  paidOn: string;
}

/** A fresh form: the whole balance, in cash, paid today. */
export const blankPay = (sale: Pick<Sale, 'balance_minor'>, today: string): PayForm => ({ amount: typedAmount(owedMinor(sale)), method: 'cash', paidOn: today });

/** What stops the payment being saved, in plain words, or null. */
export function payProblem(form: PayForm, sale: Pick<Sale, 'balance_minor' | 'sale_date'>, today: string): string | null {
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null || amount <= 0) return 'Enter the amount received.';
  if (amount > owedMinor(sale)) return `This sale owes only ${formatMinor(owedMinor(sale), RETAIL_CURRENCY)}.`;
  if (form.paidOn === '') return 'Choose the date the money was received.';
  if (form.paidOn > today) return 'The date cannot be in the future.';
  if (sale.sale_date && form.paidOn < sale.sale_date) return 'The date cannot be before the sale.';
  return null;
}

export function buildPayRequest(form: PayForm): SalePaymentRequest {
  const amount = parseMinor(form.amount, RETAIL_CURRENCY);
  if (amount === null) throw new Error('invalid amount');
  return { amount_minor: amount, method: form.method, paid_on: form.paidOn };
}

/** The shortcut: the whole balance, in the method and on the date already chosen. */
export const fullPay = (form: PayForm, sale: Pick<Sale, 'balance_minor'>): PayForm => ({ ...form, amount: typedAmount(owedMinor(sale)) });

/** A sentence for the result of a payment. */
export const paidWords = (paidMinor: number, balanceMinor: number): string =>
  balanceMinor <= 0
    ? `Recorded ${formatMinor(paidMinor, RETAIL_CURRENCY)}. This sale is now paid in full.`
    : `Recorded ${formatMinor(paidMinor, RETAIL_CURRENCY)}. Still owes ${formatMinor(balanceMinor, RETAIL_CURRENCY)}.`;

/**
 * Why a sale cannot be voided, in words, or null when it can (as far as the screen knows). A credit sale that has taken a
 * payment is refused by the server; returns made against a sale are only known to it, and it says so when asked.
 */
export function voidBlocked(sale: Pick<Sale, 'status' | 'payment_method' | 'paid_minor'>): string | null {
  if (sale.status === 'voided') return 'This sale is already voided.';
  if (sale.payment_method === 'credit' && (sale.paid_minor ?? 0) > 0) return 'A credit sale that has had a payment cannot be voided.';
  return null;
}

/** The reason to send, or null while it is too short. */
export const voidReasonOf = (text: string): string | null => reasonOf(text);
