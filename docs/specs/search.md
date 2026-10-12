# Search on every screen

**Status:** Draft · **Task:** #241 · **ADR:** ADR-039 · **Scope:** the staff PWA and the lending and retail list endpoints

The owner requires search on all screens to be robust, comprehensive and realtime. This spec records
the audit that started the work (part 1), then the convention the build follows (parts 2 to 4).

## 1. Audit (before this change)

### 1.1 Screens

"Trigger" is what makes the request go out. "Cancel" is what happens to a slower, older answer.

| Screen (file under `frontend/src/areas/staff/`) | Box | Trigger | Backend call | Paging when the text changes | Cancel and flicker |
|---|---|---|---|---|---|
| `lending/loans.tsx` | Loan number, member number or name | Submit button or Enter | `GET /lending/loans?q=` | Cursor list keyed on `q`, so page 1 | Key change shows the spinner state, list vanishes |
| `lending/savings.tsx` | Account number, member number or name | Submit | `GET /lending/savings-accounts?q=` | As above | As above |
| `lending/investments.tsx` | Account number, member number or name | Submit | `GET /lending/investments?q=` | As above | As above |
| `lending/investment-new.tsx` (member picker) | Member number or name | Submit, then only when 2 or more characters | `GET /lending/members?q=&limit=10` | No paging, first 10 | Result replaced on arrival |
| `retail/sales.tsx` credit buyer | Buyer | Every keystroke | `GET /retail/sales?buyer=` | Keyed filter, page 1 | No debounce: one request per key; list vanishes between keys |
| `retail/sales.tsx` all sales, buyer | Buyer | Every keystroke | `GET /retail/sales?buyer=` | As above | As above |
| `retail/sales.tsx` all sales, item | Item (name or code) | Every keystroke | `GET /retail/products?query=` (first 50, no paging) | Not paged | No debounce |
| `retail/stock.tsx` (also the All branches view, the by-location view of #193) | Name, code or category | Every keystroke | `GET /retail/stock?query=` and `/stock/all-branches?query=` | Keyed, page 1 | No debounce. **Left for the category-groups branch** |
| `retail/stocktake.tsx` | Name, code or category | Every keystroke, in the browser over the full stock list | none (filters `GET /retail/stock` rows in memory) | Not paged | Instant, but `toLowerCase().includes`: no accents, no multi-term |
| `retail/usage.tsx` | Item | Every keystroke | `GET /retail/products?query=&branch_id=` (first 50) | Not paged | No debounce |
| `retail/restock.tsx` | Item | Every keystroke | `GET /retail/products?query=` (first 50) | Not paged | No debounce |
| `retail/ui.tsx` `ProductPicker` (sale, transfer) | Item | Every keystroke | `GET /retail/products?query=&branch_id=` (first 50, 20 shown) | Not paged | No debounce |
| `retail/catalogue-people.tsx` (credit buyers, cash parties) | Name | Debounced 300 ms (local `useDebounced`) | `GET /retail/customers?query=`, `GET /retail/cash-parties?query=` | Cursor list keyed on text, page 1 | Debounce only; list vanishes while the next arrives |
| `retail/catalogue-products.tsx` (Items) | Name, code or category | Every keystroke | `GET /retail/products?query=` | Keyed, page 1 | No debounce. **Left for the category-groups branch** |
| `insights/page.tsx` | none | none | none | n/a | No search box today (filters are dropdowns) |
| `platform/application.tsx` | none | none | none | n/a | A sign-up form, no search |
| `platform/applications.tsx` | none (status filter only) | n/a | `applications?status=` | n/a | n/a |

No screen announced its result count to assistive technology, none had a clear button or an Escape
key, none showed "Searching", and the empty result was a bare "No ... matches" or nothing.

### 1.2 Backend queries

Every list is keyset paged on a cursor (`sortKey|id`, base64url). Branch scope is a separate
`branch_id IN (...)` predicate, ANDed with the search, and RLS applies on top.

| Endpoint (param) | Repository | Fields matched | How | Order |
|---|---|---|---|---|
| `GET /lending/members` (`q`) | `MemberRepository.page` | `member_no` and `national_id` exact (NIN upper-cased), `full_name` substring, `phone_e164` suffix when 4 or more digits | `=` and `ILIKE` with a backslash escape; the whole text is one phrase | `member_no` |
| `GET /lending/loans` (`q`) | `LoanRepository.page` | `loan_no` and `member_no` prefix, member `full_name` substring | `ILIKE`; escape of `\ % _`; one phrase | `created_at, id` |
| `GET /lending/savings-accounts` (`q`) | `SavingsRepository.page` | `account_no` and `member_no` prefix, member `full_name` substring | as loans | `account_no` |
| `GET /lending/investments` (`q`) | `InvestmentRepository.page` | `account_no`, `member_no`, member `full_name`, all substring | `ILIKE`; escapes `% _` but **not the backslash** | `account_no` descending |
| `GET /retail/products` (`query`) | `CatalogueRepository.page` | `code`, `description`, category name, substring | `ILIKE`; escape of `\ % _`; one phrase | `code, id` |
| `GET /retail/stock` (`query`) | `StockRepository.page` | as products | as products | `code, id` |
| `GET /retail/stock/all-branches` (`query`) | `StockRepository.allBranches` | as products | as products | `code, id` |
| `GET /retail/customers` (`query`) | `SalesRepository.customers` | `name` substring | `ILIKE`; escape of `\ % _` | `lower(name), id` |
| `GET /retail/sales` (`buyer`) | `SalesRepository.page` | `buyer_name` and the customer's `name`, substring | `ILIKE`; escape of `\ % _` | `created_at, id` (newest or oldest first) |
| `GET /retail/cash-parties` (`query`) | `CashbookRepository.parties` | `name` substring | `lower(name) LIKE`; **strips** `%` and `_` instead of escaping them, so "a_b" finds "ab" | `created_at, id` |
| `GET /core/users` (cursor on `lower(full_name)`) | `UserService` | no search parameter | n/a | `lower(full_name)` |

Gaps the audit found:

* **Multi-word queries are one phrase.** "john doe" fails against "Doe, John"; "led bulb" fails against
  "LED screw bulb".
* **Phone numbers** match only on members, and only as a suffix of the stored `+256...` form, so
  "0700 000 011" (spaces) and "+256 700 000 011" never match. Credit buyers' and parties' `contact`, and the
  member behind a loan, savings account or investment, cannot be found by phone at all.
* **National ID** only on members, and only exactly.
* **Sale number** is not searchable; the sales box means "buyer" only.
* **Accents** are not folded anywhere (`ILIKE` is case-insensitive only).
* **Escaping** is inconsistent: the investments query does not escape the backslash, the cash parties
  query deletes the metacharacters.
* **Ranking:** none. Order is by number or date, so an exact code can sit below fifty substring hits.
* **Indexes:** a trigram index exists on `lending_members.full_name` (V1) and on
  `retail_products.description` (V10); they serve a bare `ILIKE` on the raw column, which stops being the
  query once folding is added. Nothing indexes loan numbers, product codes, credit buyer names or phones.
* **Cancellation:** none of the API functions pass an `AbortSignal`; a slow, old answer can only be ignored
  because its query key differs.

## 2. Frontend convention

(Completed with the build; see section 4.)

## 3. Backend convention

(Completed with the build; see section 4.)

## 4. Decisions

(Completed with the build; ADR-039.)
