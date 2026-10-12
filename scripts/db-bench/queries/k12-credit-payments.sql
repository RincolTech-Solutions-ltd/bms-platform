-- CreditRepository.payments, a month by day and method (issue #149, #196). retail_sale_payments_sale leads with
-- the sale, so this reads the tenant's credit payments and filters by date: measured here. A payment on account
-- is joined to its receipt (the receiving branch) and a reversed receipt is left out, as in the repository.
-- tenant: tenant
SELECT p.paid_on, p.method, count(*) AS payments, sum(p.amount_minor) AS amount
  FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
  LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id
  LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id
 WHERE p.paid_on BETWEEN '2026-09-01' AND '2026-09-30' AND s.status = 'completed'
   AND NOT EXISTS (SELECT 1 FROM retail_account_payment_allocations a
                     JOIN retail_account_payments r ON r.tenant_id = a.tenant_id AND r.id = a.receipt_id
                    WHERE a.tenant_id = p.tenant_id AND a.sale_payment_id = p.id AND r.status = 'reversed')
 GROUP BY p.paid_on, p.method ORDER BY p.paid_on, p.method
