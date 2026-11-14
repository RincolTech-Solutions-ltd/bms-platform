# UX audit of every staff route (issue #336)

The audit of every staff route against the three rules of `docs/ui/ux-rules.md`: (1) show little, let people open more;
(2) every name opens its record, breadcrumbs always; (3) no training needed. It was in `docs/specs/retail-ui-notes.md`
("Navigation and tour coverage" and "The three rules, screen by screen"), where it began on 2026-10-20 with 105 routes; it
moved here on 2026-11-14 and now covers all 149 staff routes in `frontend/src/app/router.tsx` (the sign-in, sign-up,
platform console, member area and style routes are not staff screens). A new screen adds its row here.

## How to read it

- **Rule 1** meets when a list that can grow is grouped with a summary on each header, paged with Show more, or bounded by
  a date range or a top count, and a record keeps its long sections closed. A one record page or a short form meets it.
- **Rule 2** meets when each sale number, item name, branch name, buyer name and total the screen shows opens its record
  for a person who may open it (`retail/screen-link.tsx`, `retail/analytics-ui.tsx`). Breadcrumbs are structural: every
  route declares a `crumb` and a test fails without one, so all 149 have them and the column does not repeat it.
- **Rule 3** meets when the words are plain (a word a shop owner would not use has a help tip), each view has one brand
  filled primary action, and an empty list says what to do next.
- **Tour** is the page tour of ADR-049 (`frontend/src/tour/`), with its version.
- "gap: ..." names what breaks the rule; the tickets at the end take the gaps in order.

## Result

Counted over the 148 work routes (the component kit is a review page, outside the rules):

| Rule | Meets | Gap |
|---|---|---|
| 1 Progressive disclosure | 145 of 148 | 3: Feedback, Payment reconciliation, Unallocated payments |
| 2 Cross links | 138 of 148 | 10, all buyer names (and All sales opening a sale in place): the sale page, All sales, Credit sales, Customer returns, Quotations, Documents, Orders, an Order, Delivery fees and cancellations, and the saved sale on Record a sale. Every sale number, item name and branch name now opens its record |
| 3 No training needed | 141 of 148 | 7: Payment reconciliation, Unallocated payments, Orders, Notifications and app, Bring in your data, Delivery fees and cancellations, Imports to sign off |

**Closed by this change (rule 2).** A sale number, an item name and a branch name could not open their record, because the
routes took no id (triage of 2 Nov 2026). Now:

- `/staff/retail/sales/$saleId` is the sale's own page (the sale view of All sales: lines, payments, documents, returns and
  the actions), with page tour `page-sale-record`. `SaleLink` opens it for a person who may read sales; a person who may
  only open Credit sales keeps `/staff/retail/credit-sales?sale=<id>`.
- `/staff/retail/items/$productId` (the item page of #813) takes `?branch=` and starts on that shop's movements. `ItemLink`
  and `ToStock` with `item` open it, with `branch` when the row is of one branch.
- `/staff/retail/branches/$branchId` is the branch page: what the branch is, See its stock (the one primary), its past
  usage and damage, and every store and shop; page tour `page-branch`. `BranchLink` and `ToStock` with `branch` open it.
  It reads the branches of the session (`me.branches`), so no endpoint was needed; a branch the person does not work at is
  said in plain words.
- Newly linked: the lines of a delivery, a purchase order, a supplier invoice, a supplier credit note, a landed cost, a past
  purchase, a move on the way, a saved stock move, a price list and Past usage and damage open the item; Stores and shops
  links each item and each branch column; the branch of a sale, of a customer order, of a payment on account, of each shop
  on the item page and its movements, of each size's stock, of the shop rows on Retail home, and the shop names on Stock
  health, slow movers and Daily profit open the branch page; the sale of a delivered order and of a delivery fee on an
  account open the sale. After review: the item of each line of a sale, the items named on a move on the way, the branch on
  the saved summary of Advances, Banking, Expenses, Savings and Cash withdrawals, the branch facts of a purchase order, a
  delivery, a supplier invoice, a supplier credit note, a supplier payment and a landed cost, the branch headings of
  Business evaluation, Sells better at on slow movers, and the two branches of each Stock moves row (beside its button).
- `BranchLink` is drawn only for a branch the person works at (`me.branches`), so a link never ends on a refusal. The branch
  page tells an unknown address from a branch the person does not work at when it can (someone who works at every branch).
- The sale page says "This sale was not found" in plain words for an unknown sale and for one at a branch the person may not
  read; the server answers 404 for both (`SalesService.get`, proven for another branch in `RetailOwnerDecisionsIT`).
- A name that stands alone, in a table head or on a line of its own (`.target-line`), is at least `--tap` (48px) tall and
  takes that room in the layout, so it never overlaps the button or link beside it. A name inside a sentence keeps the height
  of its text, so a hint line does not grow (`docs/ui-rules.md` rule 12; both proven in `layout/record-links.layout.test.ts`
  at 412px). The design guard fails a `::before` or `::after` that would widen a target by an invisible box.
- After the second review: the lines of a customer order, a credit note and a quotation open their item, and the branch on
  a quotation, a delivery row, a credit note row, a supplier payment row and a document row opens the branch page. A sale's
  lines keep one link each (the name); the separate Open the item button there is gone. Documents show no item lines, so
  there is nothing there to link.
- Tested in `retail/record-pages.dom.test.tsx`, `retail/record-names.dom.test.tsx`, `retail/record-links.dom.test.tsx`,
  `retail/links-retail-reports.dom.test.tsx` and the page tour test `tour/tours-retail-reports.test.tsx`;
  `tour/ux-audit-coverage.test.ts` fails when a staff route has no row here, or a row names a route that is gone.

No backend change: every record already had its id endpoint (`GET /retail/sales/{sale_id}`, the item page's endpoints of
#813), and the branch page needs none.

## The audit

| Route | Screen | Tour | Rule 1 | Rule 2 | Rule 3 |
|---|---|---|---|---|---|
| `/staff` | Home | `page-staff-home` v5 | meets: summary cards, each opens its page | meets: member name opens the member; each card opens its page | meets: KYC has a tip (#798) |
| `/staff/approvals` | Approvals | `page-approvals` v1 | meets: cards, paged | meets: Open the item on collateral releases | meets |
| `/staff/assistant` | AI assistant | `page-assistant` v2 | meets | meets: no record shown | meets: one primary per state (#798) |
| `/staff/feedback` | Feedback | `page-feedback` v1 | gap: What you sent lists every message in full, with no paging | meets: no record shown | meets: one primary, Send |
| `/staff/help` | How do I...? | `page-help` v1 | meets: answers grouped by job | meets: each answer opens | meets: no answer offers Ask us |
| `/staff/help/$articleId` | Answer | `page-help-article` v1 | meets: one answer | meets: a missing answer offers All the answers | meets |
| `/staff/insights` | Insights | `page-insights` v1 | meets: bounded by the filter | meets: loan and member numbers open their record (#863) | meets: tips on the terms (#798) |
| `/staff/kit` | Component kit | none (exempt, ADR-049) | not a work screen | not a work screen | not a work screen |
| `/staff/lending` | Lending | `page-loans` v2 | meets: paged | meets: each loan opens | meets: no match says what to do (#798) |
| `/staff/lending/applications/new` | New application | `page-application-new` v1 | meets: one form | meets: member opens the member | meets: one primary |
| `/staff/lending/collateral` | Collateral | `page-collateral` v1 | meets: paged | meets: each item opens | meets |
| `/staff/lending/collateral/$collateralId` | Item | `page-collateral-item` v1 | meets: one record | meets | meets |
| `/staff/lending/collateral/$collateralId/edit` | Change details | `page-collateral-edit` v1 | meets: one form | meets: Cancel returns to the item | meets |
| `/staff/lending/collateral/new` | Register collateral | `page-collateral-new` v1 | meets: one form | meets | meets |
| `/staff/lending/existing-loans` | Existing loans | `page-existing-loans` v1 | meets | meets | meets: Check is plain once there is something to record (#798) |
| `/staff/lending/existing-loans/import` | Import | `page-existing-loans-import` v2 | meets | meets | meets (#798) |
| `/staff/lending/investments` | Investments | `page-investments` v1 | meets: paged | meets: each investment opens | meets (#798) |
| `/staff/lending/investments/$investmentId` | Investment | `page-investment` v1 | meets: one record | meets: member and certificate open | meets: Return earned so far (#798) |
| `/staff/lending/investments/$investmentId/certificate` | Certificate | `page-investment-certificate` v1 | meets | meets | meets |
| `/staff/lending/investments/maturities` | Maturities | `page-investment-maturities` v1 | meets: bounded by dates | meets: largest investors open their investments | meets |
| `/staff/lending/investments/member/$memberId` | Member investments | `page-member-investments` v1 | meets | meets: member and savings links | meets |
| `/staff/lending/investments/member/$memberId/new` | New investment | `page-investment-new-member` v1 | meets: one form | meets: member opens the member | meets |
| `/staff/lending/investments/new` | New investment | `page-investment-new` v1 | meets: one form | meets: member opens the member | meets |
| `/staff/lending/investments/products` | Investment products | `page-investment-products` v1 | meets: short list | meets | meets (#798) |
| `/staff/lending/loans/$loanId` | Loan | `page-loan` v2 | meets: one record | meets | meets |
| `/staff/lending/loans/$loanId/appraise` | Appraise | `page-loan-appraise` v1 | meets: one form | meets: back to the loan | meets |
| `/staff/lending/loans/$loanId/decide` | Decision | `page-loan-decide` v1 | meets: one form | meets: back to the loan | meets |
| `/staff/lending/loans/$loanId/edit` | Change application | `page-application-edit` v1 | meets: one form | meets | meets |
| `/staff/lending/members` | Members | `page-members` v1 | meets: paged | meets: each member opens | meets |
| `/staff/lending/members/$memberId` | Member | `page-member` v1 | meets: one record | meets: loans, savings, investments open | meets: one primary per state (#798) |
| `/staff/lending/members/$memberId/edit` | Change details | `page-member-edit` v1 | meets: one form | meets | meets |
| `/staff/lending/members/$memberId/savings` | Member savings | `page-member-savings` v1 | meets | meets: member and investments links | meets |
| `/staff/lending/members/new` | New member | `page-member-new` v1 | meets: one form | meets | meets |
| `/staff/lending/savings` | Savings | `page-lending-savings` v1 | meets: paged | meets: each account opens | meets (#798) |
| `/staff/lending/savings/accounts/$accountId` | Account | `page-savings-account` v1 | meets: one record | meets | meets |
| `/staff/lending/savings/accounts/$accountId/statement` | Statement | `page-savings-statement` v1 | meets: bounded by dates | meets: account number opens the account | meets |
| `/staff/lending/savings/products` | Savings products | `page-savings-products` v1 | meets: short list | meets: account count opens the list | meets (#798) |
| `/staff/money` | Money | `page-money` v2 | meets: a short list of links | meets | meets |
| `/staff/more` | More | `page-more` v2 | meets: links grouped by section | meets | meets |
| `/staff/notifications` | Notifications and app | `page-notifications` v3 | meets: four short cards | meets: no record shown | gap: "No device receives notifications for you yet." does not point to the switch above it |
| `/staff/payments` | Receive payments | `page-payments` v1 | meets | meets | meets: one primary per state (#798) |
| `/staff/payments/reconciliation` | Payment reconciliation | `page-reconciliation` v1 | gap: up to 1,000 statement lines and 500 findings shown flat; past statements not limited | meets: no sale, item or branch shown | gap: Upload, each row's Save and Run it now are all brand buttons; "No statement has been uploaded yet." names no next step |
| `/staff/payments/unallocated` | Unallocated payments | `page-unallocated` v1 | gap: every receipt shown as a full card, no paging or dates | meets: no sale, item or branch shown | gap: a brand button on every row; "Nothing is waiting." names no next step |
| `/staff/reports` | Reports | `page-reports` v2 | meets: a list of reports | meets: each report opens | meets |
| `/staff/retail` | Retail | `page-retail-home` v5 | meets: summary cards | meets: each shop row opens the branch page (this change) | meets |
| `/staff/retail/accounts` | Customer accounts | `page-accounts` v1 | meets: paged | meets: each buyer opens the account | meets |
| `/staff/retail/accounts/$customerId` | Account | `page-account` v1 | meets: payments paged by month (#798) | meets: sales and delivery fees open the sale; the branch of a payment opens the branch (this change) | meets |
| `/staff/retail/advances` | Advances | `page-advances` v1 | meets: grouped | meets: the branch on the saved summary and the receiving branch open the branch page (this change) | meets |
| `/staff/retail/banking` | Banking | `page-banking` v1 | meets: grouped | meets: the saved summary's branch opens the branch page (this change) | meets |
| `/staff/retail/banking-report` | Banking report | `page-banking-report` v1 | meets: bounded by dates | meets: link back to Banking | meets |
| `/staff/retail/branch-roles` | Branch roles | `page-branch-roles` v2 | meets: one row per branch | meets: each branch opens Stores and shops | meets |
| `/staff/retail/branches/$branchId` | Branch (new) | `page-branch` v1 | meets: one record | meets: stock, past usage, every store and shop | meets: one primary, See its stock; an unknown branch is said |
| `/staff/retail/cash-summary` | Cash summary | `page-cash-summary` v2 | meets: Show every line (#798) | meets: each figure opens the rows behind it | meets |
| `/staff/retail/catalogue` | Catalogue | `page-catalogue` v4 | meets: tiles | meets: each tile opens its list | meets |
| `/staff/retail/catalogue/buyers` | Customers who owe | `page-buyers` v2 | meets: paged | meets: Open account | meets |
| `/staff/retail/catalogue/categories` | Categories | `page-categories` v4 | meets: short list | meets: each opens its page and its items | meets |
| `/staff/retail/catalogue/categories/$categoryId` | Category | `page-category` v2 | meets: one record | meets: each item's Stock | meets |
| `/staff/retail/catalogue/import` | Import items | `page-import` v5 | meets | meets | meets: one primary after a check |
| `/staff/retail/catalogue/labels` | Barcode labels | `page-labels` v2 | meets: only the items added | meets | meets: empty says to type an item |
| `/staff/retail/catalogue/products` | Items | `page-products` v10 | meets: grouped by category | meets: each item's Stock; `?category=` and `?unit=` | meets |
| `/staff/retail/catalogue/products/sizes/$familyId` | Sizes and colours | `page-sizes` v4 | meets: one record | meets: each shop's stock opens the branch (this change) | meets |
| `/staff/retail/catalogue/products/sizes/join` | Put items together as sizes | `page-sizes-join` v4 | meets: one form | meets | meets |
| `/staff/retail/catalogue/products/sizes/new` | Add an item with sizes and colours | `page-sizes-new` v4 | meets: one form | meets | meets |
| `/staff/retail/catalogue/set-up` | Set up your shop | `page-shop-setup` v1 | meets: a fixed checklist | meets | meets: one primary |
| `/staff/retail/catalogue/suppliers` | Suppliers | `page-suppliers` v3 | meets: searched | meets: each supplier opens | meets |
| `/staff/retail/catalogue/suppliers/$supplierId` | Supplier | `page-supplier` v1 | meets: deliveries paged | meets: each delivery opens | meets (#798) |
| `/staff/retail/catalogue/units` | Units | `page-units` v3 | meets: short list | meets: Items on each unit (#863) | meets |
| `/staff/retail/controls` | Controls | `page-controls` v1 | meets: grouped | meets: Open on each sale row | meets |
| `/staff/retail/credit-control` | Credit control | `page-credit-control` v2 | meets: top lists | meets: buyer opens the account, sale opens the sale | meets |
| `/staff/retail/credit-sales` | Credit sales | `page-credit-sales` v3 | meets: grouped by year, month, day (#328) | gap: each row's buyer name is plain text (`?sale=` opens a sale) | meets |
| `/staff/retail/customer-ageing` | Customer ageing | `page-customer-ageing` v2 | meets: grouped by age | meets: buyer opens the account | meets |
| `/staff/retail/customer-groups` | Customer groups | `page-customer-groups` v2 | meets: short list | meets: Credit buyers and Price lists links | meets |
| `/staff/retail/documents` | Documents | `page-documents` v1 | meets: paged | gap: the buyer name is plain text (each document's branch opens, this change; no item lines are shown) | meets |
| `/staff/retail/evaluation` | Business evaluation | `page-evaluation` v1 | meets: one section per branch, top lists | meets: each branch heading opens the branch page (this change); items and categories open | meets |
| `/staff/retail/expense-lists` | Expense lists | `page-expense-lists` v2 | meets: closed until tapped (#798) | meets: link to Expenses | meets |
| `/staff/retail/expenses` | Expenses | `page-expenses` v2 | meets: grouped | meets: the saved summary's branch opens the branch page (this change) | meets |
| `/staff/retail/expenses-report` | Expenses report | `page-expenses-report` v1 | meets: bounded by dates | meets: link back to Expenses | meets |
| `/staff/retail/goods-received` | Goods received | `page-goods-received` v1 | meets: paged | meets: each row opens the delivery | meets |
| `/staff/retail/goods-received/$receiptId` | Delivery | `page-goods-receipt` v1 | meets: one record | meets: supplier opens; each item opens the item page (this change) | meets |
| `/staff/retail/goods-received/new` | Receive without an order | `page-receive-blind` v1 | meets: one form | meets | meets |
| `/staff/retail/in-transit` | On the way | `page-in-transit` v2 | meets: forms open on a tap (#798) | meets: both branches and each item open (this change) | meets |
| `/staff/retail/items/$productId` | Item | `page-item` v1 | meets: movements paged, by shop and days | meets: each shop opens the branch; `?branch=` (this change) | meets |
| `/staff/retail/landed-costs` | Landed costs | `page-landed-costs` v1 | meets: paged | meets: each opens | meets |
| `/staff/retail/landed-costs/$landedCostId` | Landed cost | `page-landed-cost` v1 | meets: one record | meets: supplier, delivery and each item open (this change) | meets |
| `/staff/retail/landed-costs/new` | New landed cost | `page-landed-cost-new` v1 | meets: one form | meets | meets |
| `/staff/retail/margins` | Margins | `page-margins` v1 | meets: top lists | meets: sales total opens All sales; items open the item page | meets |
| `/staff/retail/orders` | Orders | `page-orders` v1 | meets: by step, Show more | gap: the customer's name is plain text (the shop opens the branch, each line its item, a delivered order's sale opens it, this change) | gap: while Delivered is open, New order and Save: delivered and paid are both brand buttons |
| `/staff/retail/orders-report` | Delivery fees and cancellations | `page-orders-report` v1 | meets: bounded by dates, grouped | gap: the customer's name on a cancelled order is plain text (each order opens) | gap: "No orders delivered on these days." names no next step |
| `/staff/retail/orders/$orderId` | Order | `page-order` v2 | meets: one record | gap: the customer's name is plain text (Open sale; the shop opens the branch and each line its item, this change) | meets |
| `/staff/retail/price-lists` | Price lists | `page-price-lists` v1 | meets: one list at a time | meets: each item opens the item page (this change) | meets |
| `/staff/retail/profit` | Daily profit | `page-profit` v2 | meets: bounded by dates, grouped | meets: branches open the branch page; See the sales behind it | meets |
| `/staff/retail/purchase-orders` | Purchase orders | `page-purchase-orders` v1 | meets: paged | meets: each order opens | meets |
| `/staff/retail/purchase-orders/$orderId` | Purchase order | `page-purchase-order` v1 | meets: one record | meets: supplier and each item open (this change) | meets |
| `/staff/retail/purchase-orders/$orderId/edit` | Change order | `page-purchase-order-edit` v1 | meets: one form | meets | meets |
| `/staff/retail/purchase-orders/$orderId/receive` | Receive goods | `page-order-receive` v1 | meets: one form | meets | meets |
| `/staff/retail/purchase-orders/new` | New purchase order | `page-purchase-order-new` v1 | meets: one form | meets | meets |
| `/staff/retail/quick-add` | Quick add | `page-quick-add` v3 | meets: one form | meets: next steps open the item | meets |
| `/staff/retail/quotations` | Quotations | `page-quotations` v1 | meets: paged | gap: the buyer name is plain text (each line's item, the branch and a converted quotation's sale open, this change) | meets |
| `/staff/retail/restock` | Buy stock | `page-restock` v6 | meets: one form | meets | meets |
| `/staff/retail/restocks` | Past purchases | `page-restocks` v3 | meets: grouped by a chain (#350) | meets: each item opens the item page (this change) | meets |
| `/staff/retail/restocks/$purchaseId` | Purchase | `page-purchase` v1 | meets: one record | meets: each item opens the item page (this change) | meets |
| `/staff/retail/returns` | Customer returns | `page-returns` v1 | meets: paged | gap: the buyer name is plain text (the sale and the branch open, this change) | meets |
| `/staff/retail/returns/$returnId` | Credit note | `page-credit-note` v1 | meets: one record | meets: buyer, sale and each line's item open (this change) | meets |
| `/staff/retail/returns/new/$saleId` | Take goods back | `page-return-new` v1 | meets: one form | meets: buyer opens the account | meets |
| `/staff/retail/sale` | Record a sale | `page-sale` v4 | meets: one form | gap: the buyer named on the saved sale is plain text | meets |
| `/staff/retail/sales` | All sales | `page-sales` v3 | meets: grouped by year, month, day (#328, #341) | gap: a sale opens in place (`?sale=`), not at its own address `/sales/$saleId`; each row's buyer name is plain text | meets |
| `/staff/retail/sales-analysis` | Sales analysis | `page-sales-analysis` v1 | meets: top lists | meets: totals open All sales; items and branches open their page | meets |
| `/staff/retail/sales/$saleId` | Sale (new) | `page-sale-record` v1 | meets: one record | gap: the buyer name is plain text (the branch and each line's item open, this change) | meets: an unknown sale, or one at a branch the person does not work at, reads as not found |
| `/staff/retail/savings` | Savings | `page-savings` v1 | meets: grouped | meets: the saved summary's branch opens the branch page (this change) | meets |
| `/staff/retail/settings` | Retail settings | `page-retail-settings` v5 | meets: sections | meets | meets |
| `/staff/retail/stock` | Stock | `page-stock` v4 | meets: grouped by category | meets: Open the item on each row; `?item=` and `?branch=` | meets |
| `/staff/retail/stock-by-location` | Stores and shops | `page-stock-by-location` v1 | meets: branches behind Show branches on a phone | meets: each item and each branch open (this change) | meets |
| `/staff/retail/stock-health` | Stock health | `page-stock-health` v4 | meets: top lists, grouped by shop | meets: items and shops open their page; counts open the rows (#798, #863) | meets |
| `/staff/retail/stocktake` | Count stock | `page-stocktake` v8 | meets: one sheet | meets | meets |
| `/staff/retail/supplier-bills` | Supplier invoices | `page-supplier-bills` v1 | meets: paged | meets: each opens | meets |
| `/staff/retail/supplier-bills/$billId` | Supplier invoice | `page-supplier-bill` v1 | meets: one record | meets: supplier, delivery and each item open (this change) | meets |
| `/staff/retail/supplier-bills/new` | New supplier invoice | `page-supplier-bill-new` v1 | meets: one form | meets | meets |
| `/staff/retail/supplier-credits` | Supplier credit notes | `page-supplier-credits` v1 | meets: paged | meets: each opens | meets |
| `/staff/retail/supplier-credits/$creditId` | Supplier credit note | `page-supplier-credit` v1 | meets: one record | meets: supplier and each item open (this change) | meets |
| `/staff/retail/supplier-credits/new` | New credit note | `page-supplier-credit-new` v1 | meets: one form | meets | meets |
| `/staff/retail/supplier-payables` | What I owe suppliers | `page-supplier-payables` v2 | meets: one row per supplier | meets: Pay and Statement per supplier | meets |
| `/staff/retail/supplier-payments` | Supplier payments | `page-supplier-payments` v2 | meets: paged | meets: each opens | meets |
| `/staff/retail/supplier-payments/$paymentId` | Supplier payment | `page-supplier-payment` v2 | meets: one record | meets: supplier opens | meets |
| `/staff/retail/supplier-payments/new` | Pay a supplier | `page-supplier-payment-new` v2 | meets: one form | meets: payables, payments, statement | meets |
| `/staff/retail/suppliers/$supplierId/statement` | Supplier statement | `page-supplier-statement` v1 | meets: bounded by dates | meets: Pay this supplier | meets |
| `/staff/retail/transfer` | Move stock | `page-transfer` v4 | meets: one form | meets: the saved move's branches and items open (this change) | meets |
| `/staff/retail/transfers` | Stock moves | `page-transfers` v1 | meets: by month, Show more (#798) | meets: each row's two branches open the branch page, beside the row button (this change) | meets |
| `/staff/retail/usage` | Usage and damage | `page-usage` v3 | meets: one form | meets: See the stock | meets |
| `/staff/retail/usage-history` | Past usage and damage | `page-usage-history` v1 | meets: grouped, Show older records | meets: each item opens the item page at that branch (this change) | meets |
| `/staff/retail/valuation` | Stock value | `page-valuation` v3 | meets: grouped by category (#798) | meets: items, branches and categories open | meets |
| `/staff/retail/withdrawals` | Cash withdrawals | `page-withdrawals` v1 | meets: grouped | meets: the saved summary's branch opens the branch page (this change) | meets |
| `/staff/setup` | Business set-up | `page-setup` v2 | meets: sections | meets: each opens its page | meets |
| `/staff/setup/approvals` | Approval rules | `page-approval-rules` v1 | meets: short list | meets | meets |
| `/staff/setup/billing` | Plan and billing | `page-billing` v2 | meets | meets | meets (#798) |
| `/staff/setup/branches` | Branches | `page-branches` v4 | meets: one row per branch | meets | meets |
| `/staff/setup/close` | Close the business | `page-close` v1 | meets: one step at a time | meets | meets: one action per step |
| `/staff/setup/imports` | Imports to sign off | `page-imports-sign-off` v1 | meets: item lists closed | meets | gap: "reconciliation" has no help tip |
| `/staff/setup/imports/runs` | Bring in your data | `page-import-runs` v1 | meets: a short list | meets: each run opens | gap: "No import yet." names no next step |
| `/staff/setup/imports/runs/$runId` | Import | `page-import-run` v1 | meets: findings closed | meets: Problems opens | meets |
| `/staff/setup/imports/runs/$runId/problems` | Problems | `page-import-problems` v1 | meets: grouped by kind, closed | meets | meets |
| `/staff/setup/notifications` | Notifications for the business | `page-business-notifications` v1 | meets: a fixed set | meets | meets |
| `/staff/setup/referrals` | Referrals | `page-referrals` v1 | meets | meets | meets (#798) |
| `/staff/setup/two-step` | Two-step sign-in | `page-two-step` v1 | meets: one form | meets | meets |
| `/staff/sms` | SMS | `page-sms-settings` v1 | meets | meets | meets (#798) |
| `/staff/sms/opt-outs` | Opt-outs | `page-sms-opt-outs` v1 | meets: paged | meets | meets (#798) |
| `/staff/sms/wording` | Wording | `page-sms-wording` v1 | meets | meets | meets |
| `/staff/two-step` | Your two-step sign-in | `page-own-two-step` v1 | meets: one form | meets | meets |
| `/staff/users` | Staff users | `page-users` v5 | meets | meets | meets |
| `/staff/users/new` | Invite someone | `page-invite-user` v3 | meets: one form | meets | meets |

The routes the audit of 2026-11-03 and #798 checked keep that verdict here; the twenty routes added since, or not named
there (Feedback, Help, Money, More, Notifications and app, the two payment screens, Set up your shop, Close the business,
the imports screens, business notifications, Past usage and damage, Orders, its report, Barcode labels and Quick add), were
read in the code on 2026-11-14 for this audit.

## Screen tickets, in order

Each is one screen ticket, most valuable first: the gaps that a new person meets on a daily screen come before the rare
ones.

1. All sales: a sale opened from the list goes to `/staff/retail/sales/$saleId`, so the address is the record (rule 2).
2. Unallocated payments: page the receipts by month with Show more, one brand button per view, and an empty line that says where a payment comes from (rules 1 and 3).
3. Payment reconciliation: group findings by kind with closed rows, page the statement lines, keep Run it now the one primary, say what to upload when empty (rules 1 and 3).
4. Orders: New order is plain while a delivery is being recorded, so Save: delivered and paid is the one primary (rule 3).
5. Buyer names: where the buyer has an account, the name opens it, through one shared `BuyerLink` beside `SaleLink`. It covers the sale page, the All sales and Credit sales rows, Customer returns, Quotations, Documents, Orders and an Order, Delivery fees and cancellations, and the saved sale on Record a sale (rule 2).
6. Feedback: What you sent shows the last ten with Show more, each message closed to its first line (rule 1).
7. Notifications and app: the empty device line points to the switch above it (rule 3).
8. Bring in your data: the empty line names Start a new import (rule 3).
9. Delivery fees and cancellations: the empty lines say to choose other days (rule 3).
10. Imports to sign off: "reconciliation" gets a help tip, or a plainer word (rule 3).
