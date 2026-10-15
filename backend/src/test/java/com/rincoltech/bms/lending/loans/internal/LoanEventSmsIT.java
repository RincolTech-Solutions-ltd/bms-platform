package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The loan SMS events of issue #221 (FR-NTF-01, FR-NTF-02, FR-NTF-06, ADR-036 third amendment) through the staff routes:
 * a disbursement, a repayment and the repayment that closes a loan each queue one message in the outbox, in the
 * transaction of the money, for the borrower's own phone only; the same request replayed, a rollback, a reversal, the seed's
 * port, a number we would not text, and a tenant without SMS queue nothing; the balance is in a receipt only when the
 * tenant asked; another tenant is untouched. The sending itself (the window, caps, opt-out, backlog, exact texts) is
 * proved with the real channel in {@code SmsEventsIT}. All figures and numbers fabricated.
 */
class LoanEventSmsIT extends LoanFixtures {

    @Autowired
    BusinessClock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LoanServicer servicer;

    @Autowired
    LoanRepository loanRepository;

    @Autowired
    LoanServicing port;

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

    // ---- Fixtures ---------------------------------------------------------------------------------

    void smsOn() {
        smsOn(t);
    }

    static void smsOn(TestDatabase.Fixture tenant) {
        SmsTenants.operator(tenant.tenantId(), true, 1000);
        SmsTenants.enable(tenant.tenantId());
    }

    /** Flat 10 percent a month, three monthly instalments, no fees. */
    String instalmentProduct(String code) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 1000);
        terms.put("rate_unit", "per_month");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 6);
        terms.put("default_term_count", 3);
        terms.put("repayment_pattern", "instalments");
        terms.put("instalment_frequency", "monthly");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        terms.put("fees", List.of());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String memberId;

    /** Applied, submitted, appraised and approved by three different users; the borrower's phone is 0700000{no}. */
    String approved(String productId, long principal, int term, int no) {
        memberId = member("Test Borrower %02d".formatted(no), "07000001%02d".formatted(no), t.headOffice(), true);
        String loan =
                apply(memberId, productId, principal, term).getBody().get("id").asString();
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
        return loan;
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }

    HttpHeaders withKey(HttpHeaders h, String key) {
        h.add("Idempotency-Key", key);
        return h;
    }

    void disburse(String loan) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/disbursements",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("disbursement_date", today.toString(), "payment_method_key", "cash"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/approve",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> repay(String loan, long amount, String key) {
        return send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/repayments",
                withKey(as(cashier, cashierPerms, "*", null), key),
                Map.of("amount_minor", amount, "value_date", today.toString(), "payment_method_key", "cash"));
    }

    JsonNode repaid(String loan, long amount) {
        ResponseEntity<JsonNode> r = repay(loan, amount, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode get(String loan, String suffix) {
        return asOfficer(HttpMethod.GET, LOANS + "/" + loan + suffix, null, null)
                .getBody();
    }

    void reverse(String loan, String txnId) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txnId + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test keying error"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/approve",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    List<Map<String, Object>> rows(TestDatabase.Fixture tenant) {
        return TestDatabase.owner()
                .sql("SELECT idempotency_key, recipient, template_key, category, subject_type, subject_id, status,"
                        + " params::text AS params, created_at, expires_at FROM notification_outbox"
                        + " WHERE channel = 'sms' AND tenant_id = ? ORDER BY created_at, template_key")
                .param(tenant.tenantId())
                .query()
                .listOfRows();
    }

    List<String> templates() {
        return rows(t).stream().map(r -> (String) r.get("template_key")).toList();
    }

    Map<String, Object> only(String template) {
        List<Map<String, Object>> match = rows(t).stream()
                .filter(r -> template.equals(r.get("template_key")))
                .toList();
        assertThat(match).as(template).hasSize(1);
        return match.getFirst();
    }

    JsonNode params(Map<String, Object> row) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) row.get("params"));
    }

    static String grouped(long minor) {
        return String.format(Locale.ROOT, "%,d", minor);
    }

    // ---- Disbursement -----------------------------------------------------------------------------------

    @Test
    void frNtf02_aDisbursementQueuesOneMessageForTheBorrowerAndNobodyElse() {
        smsOn();
        String loan = approved(instalmentProduct("SMSD"), 1_200_000, 3, 1);
        // A next of kin with a phone of its own: it is never texted.
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/lending/members/" + memberId + "/next-of-kin",
                                as(officer, officerPerms, "*", null),
                                Map.of("full_name", "Test Kin 01", "phone", "0700000199", "relationship", "sibling"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(rows(t)).as("nothing before the money moves").isEmpty();

        disburse(loan);

        assertThat(rows(t)).hasSize(1);
        Map<String, Object> row = only("loan.disbursed");
        JsonNode first = get(loan, "/schedule").get("items").get(0);
        assertThat(row.get("recipient")).isEqualTo("+256700000101");
        assertThat(row.get("category")).isEqualTo("transactional");
        assertThat(row.get("subject_type")).isEqualTo("member");
        assertThat(row.get("subject_id")).isEqualTo(UUID.fromString(memberId));
        JsonNode p = params(row);
        assertThat(p.get("loan_no").asString())
                .isEqualTo(get(loan, "").get("loan_no").asString());
        assertThat(p.get("currency").asString()).isEqualTo("UGX");
        assertThat(p.get("amount").asString()).isEqualTo("1,200,000");
        assertThat(p.get("date").asString())
                .isEqualTo("%02d/%02d/%d".formatted(today.getDayOfMonth(), today.getMonthValue(), today.getYear()));
        assertThat(p.get("next_amount").asString())
                .isEqualTo(grouped(first.get("total_due_minor").asLong()));
        assertThat(p.get("next_due_date").asString())
                .isEqualTo(LocalDate.parse(first.get("due_date").asString())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)));
        // No name of the borrower, no purpose, no number of anyone in what is stored.
        assertThat((String) row.get("params"))
                .doesNotContain("Test Borrower")
                .doesNotContain("Test stock")
                .doesNotContain("business")
                .doesNotContain("700000");
        assertThat(rows(t).stream().map(r -> r.get("recipient")))
                .as("never the next of kin")
                .doesNotContain("+256700000199");
    }

    // ---- Repayments ---------------------------------------------------------------------------------------

    @Test
    void frNtf02_aRepaymentQueuesTheReceiptWithAmountDateAndReceiptNumberAndNoBalanceByDefault() {
        smsOn();
        String loan = approved(instalmentProduct("SMSR"), 1_200_000, 3, 2);
        disburse(loan);

        JsonNode paid = repaid(loan, 400_000);

        Map<String, Object> row = only("loan.repayment_received");
        JsonNode p = params(row);
        assertThat(row.get("recipient")).isEqualTo("+256700000102");
        assertThat(p.get("amount").asString()).isEqualTo("400,000");
        assertThat(p.get("receipt_no").asString())
                .isEqualTo(paid.get("transaction").get("receipt_no").asString())
                .startsWith("RC-");
        assertThat(p.get("date").asString())
                .isEqualTo("%02d/%02d/%d".formatted(today.getDayOfMonth(), today.getMonthValue(), today.getYear()));
        assertThat(p.has("balance")).as("default: no balance").isFalse();
        assertThat(only("loan.repayment_received").get("idempotency_key").toString())
                .endsWith(":" + paid.get("transaction").get("id").asString() + ":" + memberId);

        // The tenant asks for the balance: the next receipt carries what is still owed after it.
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        JsonNode second = repaid(loan, 300_000);
        long owed = second.get("balances").get("total_outstanding_minor").asLong();
        assertThat(owed).isPositive();
        List<Map<String, Object>> receipts = rows(t).stream()
                .filter(r -> "loan.repayment_received".equals(r.get("template_key")))
                .toList();
        assertThat(receipts).hasSize(2);
        assertThat(params(receipts.get(1)).get("balance").asString()).isEqualTo(grouped(owed));
        assertThat(params(receipts.get(0)).has("balance")).isFalse();
    }

    @Test
    void frNtf06_aRepaymentRequestSentAgainWithTheSameKeyQueuesNothingNew() {
        smsOn();
        String loan = approved(instalmentProduct("SMSI"), 1_200_000, 3, 3);
        disburse(loan);
        String k = key();

        ResponseEntity<JsonNode> first = repay(loan, 100_000, k);
        ResponseEntity<JsonNode> retry = repay(loan, 100_000, k);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(templates()).containsExactly("loan.disbursed", "loan.repayment_received");
        // A different payment of the same amount is another event, and another message.
        repaid(loan, 100_000);
        assertThat(templates()).hasSize(3);
        // And the key is the transaction's, so even the same transaction queued again would be the same row.
        String txn = first.getBody().get("transaction").get("id").asString();
        assertThat(rows(t).stream()
                        .map(r -> r.get("idempotency_key").toString())
                        .filter(x -> x.contains(txn)))
                .hasSize(1);
    }

    @Test
    void frNtf02_theRepaymentThatSettlesTheLoanQueuesTheReceiptAndTheThanksAndAReversalQueuesNothing() {
        smsOn();
        String loan = approved(instalmentProduct("SMSC"), 1_200_000, 3, 4);
        disburse(loan);
        long total = get(loan, "/payoff-quote").get("total_minor").asLong();

        JsonNode closing = repaid(loan, total);
        assertThat(closing.get("loan_status").asString()).isEqualTo("closed");
        assertThat(templates()).containsExactlyInAnyOrder("loan.disbursed", "loan.repayment_received", "loan.closed");
        Map<String, Object> thanks = only("loan.closed");
        assertThat(params(thanks).get("loan_no").asString())
                .isEqualTo(get(loan, "").get("loan_no").asString());
        // Nothing but the number: no amount and no balance in the thanks.
        assertThat(params(thanks).propertyNames()).containsExactlyInAnyOrder("loan_no", "tenant_name");
        assertThat(thanks.get("recipient")).isEqualTo("+256700000104");

        // FR-REP-05: the checker reverses the closing repayment. The loan reopens; the member is told nothing.
        reverse(loan, closing.get("transaction").get("id").asString());
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
        assertThat(templates()).as("a reversal sends nothing").hasSize(3);

        // Paying it off again closes it again, once more: a new transaction, a new receipt and a new thanks.
        repaid(loan, get(loan, "/payoff-quote").get("total_minor").asLong());
        assertThat(templates()).hasSize(5);
        assertThat(templates().stream().filter("loan.closed"::equals)).hasSize(2);
    }

    @Test
    void frNtf02_aRecoveryOnAWrittenOffLoanIsAReceiptWithoutABalanceEvenForATenantThatAskedForIt() {
        smsOn();
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        String loan = approved(instalmentProduct("SMSW"), 1_200_000, 3, 10);
        disburse(loan);
        repaid(loan, 300_000);
        ResponseEntity<JsonNode> request = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/write-off",
                withKey(as(UUID.randomUUID(), permissionsOf("accountant"), "*", null), key()),
                Map.of("reason", "Test borrower unreachable"));
        assertThat(request.getStatusCode()).as("%s", request.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/approvals/"
                                        + request.getBody()
                                                .get("approval_request_id")
                                                .asString() + "/approve",
                                as(UUID.randomUUID(), adminPerms, "*", null),
                                Map.of("note", "Checked"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(loan, "").get("status").asString()).isEqualTo("written_off");
        assertThat(templates())
                .as("a write-off sends nothing")
                .containsExactlyInAnyOrder("loan.disbursed", "loan.repayment_received");

        JsonNode recovery = repaid(loan, 50_000).get("transaction");

        assertThat(recovery.get("txn_type").asString()).isEqualTo("recovery");
        List<Map<String, Object>> receipts = rows(t).stream()
                .filter(r -> "loan.repayment_received".equals(r.get("template_key")))
                .toList();
        assertThat(receipts).hasSize(2);
        assertThat(receipts.stream().map(r -> params(r)))
                .filteredOn(p -> "50,000".equals(p.get("amount").asString()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.has("balance")).isFalse();
                    assertThat(p.get("receipt_no").asString())
                            .isEqualTo(recovery.get("receipt_no").asString());
                });
        assertThat(receipts.stream().map(r -> params(r)))
                .filteredOn(p -> "300,000".equals(p.get("amount").asString()))
                .singleElement()
                .satisfies(p -> assertThat(p.has("balance")).isTrue());
        assertThat(templates()).doesNotContain("loan.closed");
    }

    // ---- What queues nothing ------------------------------------------------------------------------------

    @Test
    void frNtf01_aRepaymentThatRollsBackQueuesNothing() {
        smsOn();
        String loan = approved(instalmentProduct("SMSB"), 1_200_000, 3, 5);
        disburse(loan);
        assertThat(rows(t)).hasSize(1);
        long paidBefore = get(loan, "").get("balances").get("total_paid_minor").asLong();

        // The same code the route runs, in a transaction that fails after the money and the message were written.
        UUID loanId = UUID.fromString(loan);
        try {
            TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactionManager).execute(tx -> {
                        Loan l = loanRepository.lock(loanId).orElseThrow();
                        servicer.repay(l, 400_000, today, "cash", null, cashier, "staff");
                        assertThat(TestDatabase.owner()
                                        .sql(
                                                "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = ?")
                                        .params(t.tenantId(), "loan.repayment_received")
                                        .query(Long.class)
                                        .single())
                                .as("not visible to anyone else until it commits")
                                .isZero();
                        throw new IllegalStateException("the day's closing failed");
                    }));
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("the day's closing failed");
        }

        assertThat(templates()).as("the receipt rolled back with the money").containsExactly("loan.disbursed");
        assertThat(get(loan, "").get("balances").get("total_paid_minor").asLong())
                .isEqualTo(paidBefore);
    }

    @Test
    void theSeedAndTheImportPortNeverMessageAMember() {
        smsOn();
        String loan = approved(instalmentProduct("SMSS"), 1_200_000, 3, 6);
        UUID loanId = UUID.fromString(loan);
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(tx -> {
                    port.disburseApproved(loanId, today, "cash", cashier, manager);
                    port.recordRepayment(loanId, 100_000, today, "cash", cashier);
                    return null;
                }));
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
        assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactlyInAnyOrder("repayment", "disbursement");
        assertThat(rows(t)).isEmpty();
    }

    @Test
    void frNtf20_withSmsOffForTheOperatorOrTheTenantOrAZeroCapNothingIsQueuedAndTheMoneyStillMoves() {
        // Neither switch.
        String off = approved(instalmentProduct("SMSO1"), 1_200_000, 3, 7);
        disburse(off);
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // The operator on, the tenant's own switch off.
        SmsTenants.operator(t.tenantId(), true, 1000);
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // Both on, but the tenant's daily cap is 0: stop.
        SmsTenants.enable(t.tenantId());
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 0));
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // Lifted: the next one is queued.
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 10));
        repaid(off, 100_000);
        assertThat(templates()).containsExactly("loan.repayment_received");
        assertThat(get(off, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactly("repayment", "repayment", "repayment", "repayment", "disbursement");
    }

    @Test
    void frNtf19_aBorrowerWithoutANumberWeWouldTextIsNotMessagedAndTheRepaymentStillWorks() {
        smsOn();
        String loan = approved(instalmentProduct("SMSN"), 1_200_000, 3, 8);
        // A number of another country, stored as a member's phone, is one we do not text.
        TestDatabase.owner()
                .sql("UPDATE lending_members SET phone_e164 = '+14155550123' WHERE id = ?::uuid")
                .param(memberId)
                .update();
        disburse(loan);
        repaid(loan, 100_000);
        assertThat(rows(t)).isEmpty();
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
    }

    @Test
    void anotherTenantsRowsAndSwitchesAreNeverMixedIn() {
        smsOn();
        TestDatabase.Fixture other = TestDatabase.tenant("loans-other", true);
        smsOn(other);
        String loan = approved(instalmentProduct("SMSX"), 1_200_000, 3, 9);
        disburse(loan);
        repaid(loan, 100_000);

        assertThat(rows(t)).hasSize(2);
        assertThat(rows(other)).isEmpty();
        assertThat(rows(t).stream().map(r -> r.get("idempotency_key").toString()))
                .allSatisfy(k -> assertThat(k).contains(t.tenantId().toString()));
        // Every message of the platform outbox carries the tenant that caused it, and only that one.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ? AND tenant_id <> ?")
                        .params("sms:loan.%:" + t.tenantId() + ":%", t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
