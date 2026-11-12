package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Loan renewal on real PostgreSQL (issue #918, ADR-128): the interest is collected as a normal repayment, then a second
 * person approves and the principal moves to a new loan within loans receivable; the old loan is restructured, linked
 * both ways, and takes no more repayments. Users as the seeded matrix gives them: an officer originates, an appraiser
 * appraises, a branch manager approves and checks, a cashier takes the money. All figures fabricated.
 */
class LoanRenewalIT extends LoanFixtures {

    @Autowired
    BusinessClock clock;

    final UUID appraiser = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();
    String cashierPerms;
    LocalDate today;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    /** A one-month bullet loan product at 1000 bp per month flat, no fees, with these renewal settings (null: defaults). */
    String bulletProduct(String code, Map<String, Object> renewal) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 1000);
        terms.put("rate_unit", "per_month");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 3);
        terms.put("default_term_count", 1);
        terms.put("repayment_pattern", "bullet");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        terms.put("fees", List.of());
        if (renewal != null) {
            terms.put("renewal", renewal);
        }
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    /** Applied, submitted, appraised, approved and disbursed ten days ago: 1,000,000 for one month. */
    String activeLoan(String productId, int memberNo) {
        String member = member(
                "Test Borrower %02d".formatted(memberNo), "07000001%02d".formatted(memberNo), t.headOffice(), true);
        ResponseEntity<JsonNode> created = apply(member, productId, 1_000_000, 1);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String loan = created.getBody().get("id").asString();
        assertThat(asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"1\"", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + loan + "/appraisals",
                                as(appraiser, officerPerms, "*", "\"2\""),
                                Map.of("declared_monthly_income_minor", 2_400_000))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> decided = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/decision",
                as(manager, managerPerms, "*", "\"3\""),
                Map.of("decision", "approve"));
        assertThat(decided.getBody().get("status").asString()).isEqualTo("approved");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disbursement_date", today.minusDays(10).toString());
        body.put("payment_method_key", "cash");
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/disbursements",
                withKey(as(cashier, cashierPerms, "*", null)),
                body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d =
                decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
        return loan;
    }

    static HttpHeaders withKey(HttpHeaders h) {
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        return h;
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    ResponseEntity<JsonNode> renew(String loan, UUID user, String perms, Long collect, Integer term) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value_date", today.toString());
        if (collect != null) {
            body.put("collect_minor", collect);
        }
        body.put("payment_method_key", "cash");
        if (term != null) {
            body.put("term_count", term);
        }
        body.put("reason", "Test borrower asked to renew");
        return send(HttpMethod.POST, LOANS + "/" + loan + "/renewals", withKey(as(user, perms, "*", null)), body);
    }

    JsonNode get(String path) {
        ResponseEntity<JsonNode> r = asOfficer(HttpMethod.GET, path, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode quote(String loan, String query) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.GET,
                LOANS + "/" + loan + "/renewal-quote" + query,
                as(cashier, cashierPerms, "*", null),
                null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** The lines of a journal entry as {@code system_key:debit:credit}, in line order. */
    static List<String> lines(String entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ?::uuid ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    /** The trial balance balances, and the loans receivable subledger equals each loan's principal outstanding. */
    void booksAgree() {
        List<Long> totals = TestDatabase.owner()
                .sql("SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM journal_lines WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .list();
        assertThat(totals).containsExactly(0L);
        List<String> mismatches = TestDatabase.owner()
                .sql("""
                        SELECT l.loan_no || ': ' || l.principal_outstanding_minor || ' vs '
                               || coalesce(sum(j.debit - j.credit), 0)
                          FROM lending_loans l
                          LEFT JOIN journal_lines j ON j.subledger_id = l.id AND j.subledger_type = 'lending.loan'
                               AND j.account_id = (SELECT id FROM gl_accounts WHERE tenant_id = l.tenant_id
                                                    AND system_key = 'loans_receivable')
                         WHERE l.tenant_id = ?
                         GROUP BY l.id, l.loan_no, l.principal_outstanding_minor
                        HAVING l.principal_outstanding_minor <> coalesce(sum(j.debit - j.credit), 0)
                        """)
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(mismatches).as("subledger against loans_receivable").isEmpty();
    }

    // ---- Tests --------------------------------------------------------------------------------

    /**
     * The interest is collected with the request as a repayment; the branch manager approves; the principal moves to a new
     * loan within loans receivable; the two loans link both ways; the books agree.
     */
    @Test
    void renewalAfterTheInterestIsPaidMovesThePrincipalToANewLoan() {
        String old = activeLoan(bulletProduct("RNW1", null), 1);

        JsonNode q = quote(old, "");
        assertThat(q.get("interest_due_minor").asLong()).isEqualTo(100_000);
        assertThat(q.get("minimum_collect_minor").asLong()).isEqualTo(100_000);
        assertThat(q.get("interest_first").asBoolean()).isTrue();
        JsonNode paid = quote(old, "?collect_minor=100000");
        assertThat(paid.get("principal_moved_minor").asLong()).isEqualTo(1_000_000);
        assertThat(paid.get("carried_minor").asLong()).isZero();
        assertThat(paid.get("renewal_fee_minor").asLong()).isZero();
        assertThat(paid.get("interest_rate_bp").asInt()).isEqualTo(1000);
        assertThat(paid.get("term_count").asInt()).isEqualTo(1);
        assertThat(paid.get("new_due_date").asString())
                .isEqualTo(today.plusMonths(1).toString());
        assertThat(paid.get("renewal_no").asInt()).isEqualTo(1);

        // The loan officer may ask for a renewal but not take the money with it.
        ResponseEntity<JsonNode> officerTry = renew(old, officer, officerPerms, 100_000L, null);
        assertThat(officerTry.getStatusCode()).as("%s", officerTry.getBody()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> r = renew(old, cashier, cashierPerms, 100_000L, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = r.getBody().get("approval_request_id").asString();
        // The interest is a normal repayment, recorded now, before anyone approves the renewal.
        JsonNode repayment =
                get(LOANS + "/" + old + "/transactions").get("items").get(0);
        assertThat(repayment.get("txn_type").asString()).isEqualTo("repayment");
        assertThat(repayment.get("amount_minor").asLong()).isEqualTo(100_000);
        assertThat(repayment.get("receipt_no").asString()).startsWith("RC-");
        assertThat(get(LOANS + "/" + old).get("status").asString()).isEqualTo("active");

        // A second request while one waits is refused.
        ResponseEntity<JsonNode> twice = renew(old, cashier, cashierPerms, null, null);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("renewal_pending");

        ResponseEntity<JsonNode> d = decide(approval, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode o = get(LOANS + "/" + old);
        assertThat(o.get("status").asString()).isEqualTo("restructured");
        assertThat(o.get("balances").get("principal_outstanding_minor").asLong())
                .isZero();
        assertThat(o.get("renewal").get("renewed_on").asString()).isEqualTo(today.toString());
        String fresh = o.get("renewal").get("renewed_to_loan_id").asString();

        JsonNode n = get(LOANS + "/" + fresh);
        assertThat(n.get("status").asString()).isEqualTo("active");
        assertThat(n.get("member_id").asString()).isEqualTo(o.get("member_id").asString());
        assertThat(n.get("approved_principal_minor").asLong()).isEqualTo(1_000_000);
        assertThat(n.get("interest_rate_bp").asInt()).isEqualTo(1000);
        assertThat(n.get("renewal").get("renewal_no").asInt()).isEqualTo(1);
        assertThat(n.get("renewal").get("renewed_from_loan_no").asString())
                .isEqualTo(o.get("loan_no").asString());
        assertThat(n.get("balances").get("principal_outstanding_minor").asLong())
                .isEqualTo(1_000_000);
        assertThat(n.get("balances").get("disbursed_on").asString()).isEqualTo(today.toString());
        assertThat(n.get("balances").get("maturity_date").asString())
                .isEqualTo(today.plusMonths(1).toString());

        // No cash: the principal moves between the two loans within loans receivable, in one entry.
        JsonNode out = get(LOANS + "/" + old + "/transactions").get("items").get(0);
        assertThat(out.get("txn_type").asString()).isEqualTo("restructure_out");
        assertThat(out.get("amount_minor").asLong()).isEqualTo(1_000_000);
        assertThat(lines(out.get("journal_entry_id").asString()))
                .containsExactly("loans_receivable:1000000:0", "loans_receivable:0:1000000");
        JsonNode in = get(LOANS + "/" + fresh + "/transactions").get("items").get(0);
        assertThat(in.get("txn_type").asString()).isEqualTo("restructure_in");
        assertThat(in.get("journal_entry_id").asString())
                .isEqualTo(out.get("journal_entry_id").asString());

        // The old schedule shows nothing outstanding; the new one charges the month's interest again.
        JsonNode oldTotals = get(LOANS + "/" + old + "/schedule").get("totals");
        assertThat(oldTotals.get("outstanding_minor").asLong()).isZero();
        assertThat(oldTotals.get("restructured_minor").asLong()).isEqualTo(1_000_000);
        JsonNode newItem = get(LOANS + "/" + fresh + "/schedule").get("items").get(0);
        assertThat(newItem.get("principal_due_minor").asLong()).isEqualTo(1_000_000);
        assertThat(newItem.get("interest_due_minor").asLong()).isEqualTo(100_000);
        assertThat(newItem.get("carried_arrears_minor").asLong()).isZero();

        // The renewed loan takes no more repayments; the new one does.
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("amount_minor", 50_000);
        pay.put("value_date", today.toString());
        pay.put("payment_method_key", "cash");
        ResponseEntity<JsonNode> late = send(
                HttpMethod.POST, LOANS + "/" + old + "/repayments", withKey(as(cashier, cashierPerms, "*", null)), pay);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + fresh + "/repayments",
                                withKey(as(cashier, cashierPerms, "*", null)),
                                pay)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        booksAgree();
    }

    /** With the product's default, a renewal waits for the interest: nothing collected, or too little, is refused. */
    @Test
    void renewalIsRefusedWhileTheInterestIsUnpaidAndNothingIsRecorded() {
        String old = activeLoan(bulletProduct("RNW2", null), 2);

        ResponseEntity<JsonNode> none = renew(old, cashier, cashierPerms, null, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(none.getBody().get("code").asString()).isEqualTo("renewal_interest_unpaid");

        ResponseEntity<JsonNode> some = renew(old, cashier, cashierPerms, 60_000L, null);
        assertThat(some.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(some.getBody().get("code").asString()).isEqualTo("renewal_interest_unpaid");
        // The refusal took the partial collection with it: only the disbursement is on the loan.
        assertThat(get(LOANS + "/" + old + "/transactions").get("items")).hasSize(1);

        ResponseEntity<JsonNode> settles = renew(old, cashier, cashierPerms, 1_100_000L, null);
        assertThat(settles.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(settles.getBody().get("code").asString()).isEqualTo("renewal_settles_loan");

        ResponseEntity<JsonNode> longTerm = renew(old, cashier, cashierPerms, 100_000L, 4);
        assertThat(longTerm.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(longTerm.getBody().get("code").asString()).isEqualTo("renewal_term_out_of_range");
        assertThat(get(LOANS + "/" + old).get("status").asString()).isEqualTo("active");
    }

    /**
     * A product that renews without the interest first, with a fee and one renewal: what is not collected is carried to
     * the new loan's first item as a memorandum, never principal; the fixed fee is collected and is income on approval,
     * never on the schedule; a second renewal is refused; the maker never approves their own renewal.
     */
    @Test
    void uncollectedInterestIsCarriedAsAMemoAndTheProductLimitsTheRenewals() {
        Map<String, Object> renewal = new LinkedHashMap<>();
        renewal.put("interest_first", false);
        renewal.put("max_count", 1);
        renewal.put("fee_minor", 20_000);
        String old = activeLoan(bulletProduct("RNW3", renewal), 3);

        ResponseEntity<JsonNode> r = renew(old, manager, managerPerms, null, 2);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = r.getBody().get("approval_request_id").asString();
        ResponseEntity<JsonNode> self = decide(approval, manager, managerPerms);
        assertThat(self.getStatusCode()).isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.FORBIDDEN);
        assertThat(get(LOANS + "/" + old).get("status").asString()).isEqualTo("active");
        UUID secondManager = UUID.randomUUID();
        ResponseEntity<JsonNode> d = decide(approval, secondManager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);

        String fresh =
                get(LOANS + "/" + old).get("renewal").get("renewed_to_loan_id").asString();
        JsonNode n = get(LOANS + "/" + fresh);
        assertThat(n.get("approved_principal_minor").asLong())
                .as("unpaid interest is never added to principal")
                .isEqualTo(1_000_000);
        JsonNode schedule = get(LOANS + "/" + fresh + "/schedule");
        assertThat(schedule.get("items")).hasSize(1);
        JsonNode first = schedule.get("items").get(0);
        assertThat(first.get("carried_arrears_minor").asLong()).isEqualTo(100_000);
        assertThat(first.get("fees_due_minor").asLong())
                .as("the fee is collected, never lent")
                .isZero();
        assertThat(first.get("interest_due_minor").asLong()).isEqualTo(200_000);
        // The fixed fee was taken at the counter with a receipt and held for the member; the approval made it income.
        JsonNode held = txnOf(old, "renewal_fee_held");
        assertThat(held.get("amount_minor").asLong()).isEqualTo(20_000);
        assertThat(held.get("receipt_no").asString()).startsWith("RC-");
        assertThat(lines(held.get("journal_entry_id").asString()))
                .containsExactly("cash_on_hand:20000:0", "member_overpayments:0:20000");
        assertThat(lines(txnOf(old, "renewal_fee").get("journal_entry_id").asString()))
                .containsExactly("member_overpayments:20000:0", "loan_fee_income:0:20000");
        assertThat(get(LOANS + "/" + old)
                        .get("balances")
                        .get("credit_balance_minor")
                        .asLong())
                .as("nothing is left held once the renewal used the fee")
                .isZero();
        assertThat(first.get("due_date").asString())
                .isEqualTo(today.plusMonths(2).toString());

        // The carried interest is not outstanding on either loan: it is a memorandum.
        assertThat(n.get("balances").get("interest_outstanding_minor").asLong()).isEqualTo(200_000);
        assertThat(get(LOANS + "/" + old + "/schedule")
                        .get("totals")
                        .get("outstanding_minor")
                        .asLong())
                .isZero();

        ResponseEntity<JsonNode> again = renew(fresh, manager, managerPerms, null, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("renewal_limit_reached");
        booksAgree();
    }

    /** A product that allows no renewal refuses the quote and the request. */
    @Test
    void aProductThatAllowsNoRenewalRefusesIt() {
        String old = activeLoan(bulletProduct("RNW4", Map.of("allowed", false)), 4);
        ResponseEntity<JsonNode> r = renew(old, cashier, cashierPerms, 100_000L, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("renewal_not_allowed");
        ResponseEntity<JsonNode> q =
                send(HttpMethod.GET, LOANS + "/" + old + "/renewal-quote", as(cashier, cashierPerms, "*", null), null);
        assertThat(q.getBody().get("code").asString()).isEqualTo("renewal_not_allowed");
    }

    /** The loan's transaction of this type; fails when there is none. */
    JsonNode txnOf(String loan, String type) {
        return java.util.stream.StreamSupport.stream(
                        get(LOANS + "/" + loan + "/transactions").get("items").spliterator(), false)
                .filter(x -> x.get("txn_type").asString().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " on " + loan));
    }

    ResponseEntity<JsonNode> refundFee(String loan) {
        return send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/renewal-fee/refund",
                withKey(as(cashier, cashierPerms, "*", null)),
                Map.of("value_date", today.toString(), "payment_method_key", "cash"));
    }

    /**
     * Issue #852 (ADR-026 decision 5) on the renewal route: the maker's retry replays one request and one repayment;
     * another cashier with the same key is refused, sees none of the stored answer and records nothing.
     */
    @Test
    void idempotency_aRetriedRenewalIsOneRequestAndAnotherPersonsKeyIsRefused() {
        String old = activeLoan(bulletProduct("RNW5", null), 5);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value_date", today.toString());
        body.put("collect_minor", 100_000);
        body.put("payment_method_key", "cash");
        String k = "test-" + UUID.randomUUID();
        HttpHeaders mine = as(cashier, cashierPerms, "*", null);
        mine.add("Idempotency-Key", k);
        ResponseEntity<JsonNode> first = send(HttpMethod.POST, LOANS + "/" + old + "/renewals", mine, body);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.ACCEPTED);

        KeyOwnership.replayed(send(HttpMethod.POST, LOANS + "/" + old + "/renewals", mine, body), first);

        HttpHeaders theirs = as(UUID.randomUUID(), cashierPerms, "*", null);
        theirs.add("Idempotency-Key", k);
        ResponseEntity<JsonNode> other = send(HttpMethod.POST, LOANS + "/" + old + "/renewals", theirs, body);
        KeyOwnership.refused(other, first, old, body);
        // The disbursement and one repayment: neither the replay nor the refusal took the money again.
        assertThat(get(LOANS + "/" + old + "/transactions").get("items")).hasSize(2);
    }

    /**
     * Review of #919: a renewal fee taken at the counter is on record from that moment (a receipt, the till debited, held
     * for the member). A rejected renewal leaves it held, and it is given back with its own receipt.
     */
    @Test
    void aFeeTakenForARejectedRenewalIsHeldAndGivenBackWithAReceipt() {
        String old = activeLoan(bulletProduct("RNW6", Map.of("fee_minor", 20_000)), 6);
        ResponseEntity<JsonNode> r = renew(old, cashier, cashierPerms, 100_000L, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(lines(txnOf(old, "renewal_fee_held").get("journal_entry_id").asString()))
                .containsExactly("cash_on_hand:20000:0", "member_overpayments:0:20000");
        assertThat(refundFee(old).getStatusCode())
                .as("not while the renewal waits")
                .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> rejected = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/reject",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Test borrower to come back next week"));
        assertThat(rejected.getStatusCode()).as("%s", rejected.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(get(LOANS + "/" + old)
                        .get("balances")
                        .get("credit_balance_minor")
                        .asLong())
                .isEqualTo(20_000);

        ResponseEntity<JsonNode> back = refundFee(old);
        assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode refund = back.getBody().get("transaction");
        assertThat(refund.get("txn_type").asString()).isEqualTo("credit_refund");
        assertThat(refund.get("receipt_no").asString()).startsWith("RC-");
        assertThat(lines(refund.get("journal_entry_id").asString()))
                .containsExactly("member_overpayments:20000:0", "cash_on_hand:0:20000");
        assertThat(back.getBody().get("balances").get("credit_balance_minor").asLong())
                .isZero();
        booksAgree();
    }
}
