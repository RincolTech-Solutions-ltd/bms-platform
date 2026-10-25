# Closing a business: the Owner's request, the removal, and businesses with no Owner

For the platform operator (issue #571, ADR-090). How an Owner closes their business, what you do after the waiting period, what
removal deletes and keeps, the deletion of the kept money records years later, and how to give an Owner to a business that has none.

## What the Owner does

1. Business set-up > **Close the business**: the Owner reads what closing means, then a review of exactly what happens, and proves
   it is them (the authenticator code, else the password).
2. From that moment the business is **read only**: everyone can sign in, look and download, nobody can add or change anything. A
   full "Download my data" workbook is made for the Owner. The Owner and every operator get an email.
3. Until you remove it the Owner can **Cancel the closing** on the same page, and the business works again at once.

Closing is not cancelling. Cancelling the subscription (Plan and modules) keeps every record, read only. Closing removes the
people's data.

## Remove a business

1. Open **Businesses closing** in the operator portal. Each request shows the day it can be removed: 30 days after it was asked
   for, in the business's own time zone.
2. Before that day there is no Remove button and the API answers 422 `cooling_off`. Do not shorten the wait to oblige anyone: the
   wait is the Owner's chance to change their mind, and the Owner may call to say a stranger asked.
3. On or after that day, call the Owner on the number you know (not one given in the request) to confirm. Then press **Remove the
   business**, type a code from your own authenticator app and a reason, and confirm. It cannot be undone.
4. The answer shows the day the kept money records may be deleted (`keep_until`). The platform history has
   `platform.tenant.removed` with what was erased.

What removal deletes and keeps is listed in ADR-090 decisions 7 and 8. In short: names, phone numbers, ID numbers, emails, notes
about people and every stored file are deleted or replaced by placeholders; sign-in is gone; the money records stay, without names,
until five years after the end of the tax year (1 July to 30 June) of the removal.

If a removal fails, nothing was changed (one transaction). The error log names the business; fix the cause and remove again.

## Delete the kept money records after `keep_until`

Nothing does this by itself. Once a year, list the removed businesses whose day has passed:

```sql
SELECT k.tenant_id, t.slug, k.keep_until FROM tenant_closures k JOIN tenants t ON t.id = k.tenant_id
 WHERE k.status = 'removed' AND k.keep_until < current_date;
```

For each, on the host, as the PostgreSQL superuser (the append-only triggers refuse everyone else), take a backup first
(`docs/runbooks/restore-from-backup.md`), then delete every row of the business except the `tenants` row and the
`tenant_closures` row, which the platform's own history points at:

```sql
BEGIN;
SET LOCAL session_replication_role = replica;  -- the guard and foreign key triggers stay off for this transaction only
DO $$
DECLARE
    v_tenant uuid := '<tenant id>';
    v_table  text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenant_closures WHERE tenant_id = v_tenant AND status = 'removed'
                    AND keep_until < current_date) THEN
        RAISE EXCEPTION 'not a removed business past its keep_until';
    END IF;
    FOR v_table IN
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT IN ('tenant_closures', 'platform_audit_log')
    LOOP
        EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', v_table) USING v_tenant;
    END LOOP;
END
$$;
COMMIT;
```

Write what you did in the platform's own records (the incident or support log), with the date.

## Give a business an Owner

1. Open **Businesses with no owner** in the operator portal. Each business is listed with its active administrators and when they
   last signed in.
2. Confirm with the business who owns it. Press **Set as the owner** beside that administrator, type a code from your own
   authenticator app and a reason. It is ADR-062's Make the owner: audited in the business's history and the platform's.
3. A business with no active administrator says so: open its Business page and send its first administrator a new invitation.
