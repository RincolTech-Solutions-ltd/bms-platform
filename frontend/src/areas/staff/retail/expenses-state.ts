import type { Expense, ExpenseCategory, ExpenseItem, ExpenseRequest } from '../../../api/retail';
import { amountOrNull } from './cash-state';
import { showDate } from './maths';
import { typedAmount } from './sale-actions-state';

// The expense form's rules (FR-RET-17, FR-RET-24): the items offered depend on the chosen category and only
// active ones are offered, and an item flagged "needs an explanation" makes the explanation field appear and
// be required. The lists are tenant data; none is hard coded.

export const MIN_EXPLANATION = 3;

/** The categories a new expense may use: the active ones. */
export const activeCategories = (categories: ExpenseCategory[]): ExpenseCategory[] => categories.filter((c) => c.active !== false);

/** The items of the chosen category only, active ones only; none until a category is chosen. */
export function itemsOf(categories: ExpenseCategory[], categoryId: string): ExpenseItem[] {
  const category = categories.find((c) => c.id === categoryId);
  return (category?.items ?? []).filter((i) => i.active !== false);
}

/** The list with an item added in place to its category (ADR-118); an item already there is left as it is. */
export function withItem(categories: ExpenseCategory[] | undefined, categoryId: string, item: ExpenseItem): ExpenseCategory[] | undefined {
  return categories?.map((c) => (c.id !== categoryId || (c.items ?? []).some((i) => i.id === item.id) ? c : { ...c, items: [...(c.items ?? []), item] }));
}

/** True when the chosen item is flagged "needs an explanation". */
export function needsExplanation(categories: ExpenseCategory[], categoryId: string, itemId: string): boolean {
  return itemsOf(categories, categoryId).find((i) => i.id === itemId)?.requires_explanation === true;
}

export interface ExpenseInput {
  branchId: string;
  date: string;
  categoryId: string;
  itemId: string;
  partyId: string;
  amount: string;
  explanation: string;
}

export interface ExpensePlan {
  problem: string | null;
  request: ExpenseRequest;
}

/** What to send, and what (if anything) blocks it. The explanation is sent only for an item that needs one. */
export function planExpense(input: ExpenseInput, categories: ExpenseCategory[]): ExpensePlan {
  const minor = amountOrNull(input.amount);
  const explain = needsExplanation(categories, input.categoryId, input.itemId);
  const request: ExpenseRequest = {
    branch_id: input.branchId,
    business_date: input.date,
    category_id: input.categoryId,
    item_id: input.itemId,
    amount_minor: minor ?? 0,
    ...(input.partyId ? { party_id: input.partyId } : {}),
    ...(explain ? { explanation: input.explanation.trim() } : {}),
  };
  if (!input.categoryId) return { problem: 'Choose a category.', request };
  if (!input.itemId || !itemsOf(categories, input.categoryId).some((i) => i.id === input.itemId)) return { problem: 'Choose an item.', request };
  if (minor === null) return { problem: 'Enter the amount in whole shillings.', request };
  if (explain && input.explanation.trim().length < MIN_EXPLANATION) return { problem: `Explain this expense in at least ${MIN_EXPLANATION} characters.`, request };
  return { problem: null, request };
}

// Edit and Delete an expense as in the owner's old app (issue #830). Underneath, Delete is the void of FR-RET-28 and Edit is that
// void and a new expense: the old one is kept, marked Voided, its reason saying it was edited; the new one names it.
// JUSTIFICATION-A3: #830 asks for Edit and Delete on an expense; these are the pure rules of the two forms, beside the form's own.

/** The reason an expense is voided with when it is edited. */
export const EXPENSE_EDIT_REASON = 'Edited: entered again with the changes';

/** The reason kept on a deleted expense when none was typed. */
export const EXPENSE_DELETE_REASON = 'Deleted from the expenses screen';

/** The words that link an edited expense to the one it replaces. */
export const replacesWords = (old: Pick<Expense, 'business_date' | 'item_name'>): string =>
  `Replaces the ${old.item_name ?? 'expense'} entry of ${showDate(old.business_date)}, edited.`;

/** The form filled in with an expense, to be edited. */
export const draftOfExpense = (e: Expense) => ({
  date: e.business_date ?? '',
  categoryId: e.category_id ?? '',
  itemId: e.item_id ?? '',
  partyId: e.party_id ?? '',
  amount: typedAmount(e.amount_minor ?? 0),
  explanation: (e.explanation ?? '').replace(/\s*Replaces the .* entry of .*, edited\.$/, ''),
});

/** The request of an edited expense: the plan's, with the words that name the one it replaces after any explanation. */
export function editedRequest(request: ExpenseRequest, old: Expense): ExpenseRequest {
  const own = request.explanation?.trim() ?? '';
  return { ...request, explanation: `${own ? `${own} ` : ''}${replacesWords(old)}`.slice(0, 500) };
}
