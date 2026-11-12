package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The renewals table of {@code lending.par} and {@code lending.portfolio_outstanding} (issue #918, ADR-128): a renewal
 * never hides arrears. On the golden book of chapter 14, LN0003 (1,000,000 with its first unpaid item due 30 days ago) is
 * made the renewal of a fabricated LN0008 renewed on 3 October 2026, leaving 25,000 carried. The PAR and ageing tables do
 * not change (LN0008 is no longer active); the renewals table counts the renewal and shows the renewal loan's arrears.
 * Every figure is written by hand.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class LendingRenewalsReportIT extends LendingBookSupport {

    /** LN0008, renewed into LN0003 on 2026-10-03 with 1,000,000 moved and 25,000 carried. */
    void renew(Book book) {
        TestDatabase.Fixture t = book.t();
        loan(
                t,
                book.product(),
                t.headOffice(),
                book.officerA(),
                "LN0008",
                "active",
                1_000_000,
                0,
                0,
                TODAY.minusDays(40));
        var owner = TestDatabase.owner();
        UUID old = owner.sql("SELECT id FROM lending_loans WHERE tenant_id = ? AND loan_no = 'LN0008'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        UUID fresh = owner.sql("SELECT id FROM lending_loans WHERE tenant_id = ? AND loan_no = 'LN0003'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        owner.sql("""
                        UPDATE lending_loans SET status = 'restructured', restructured_to_loan_id = ?,
                               restructured_on = DATE '2026-10-03', restructure_kind = 'renewal',
                               principal_outstanding_minor = 0
                         WHERE id = ?
                        """).params(fresh, old).update();
        owner.sql("""
                        UPDATE lending_loans SET renewal_no = 1, restructured_from_loan_id = ?, arrears_minor = 1050000,
                               days_past_due = 30
                         WHERE id = ?
                        """).params(old, fresh).update();
        owner.sql("UPDATE lending_schedule_items SET carried_arrears_minor = 25000 WHERE loan_id = ? AND item_no = 1")
                .param(fresh)
                .update();
        owner.sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor,
                            currency, value_date, reason, source, is_historic)
                        VALUES (?, ?, ?, ?, 'restructure_out', 1000000, 'UGX', DATE '2026-10-03', 'Renewed into LN0003',
                            'staff', true)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), old)
                .update();
    }

    @Test
    void theParReportCountsTheRenewalAndTheRenewalLoansArrears() throws Exception {
        Book book = goldenBook();
        renew(book);
        UUID user = UUID.randomUUID();

        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of(), "json"));

        // The renewed loan left the book; PAR is the golden figure.
        assertThat(rows(table(file, "par"))).containsExactly("[\"All loans\",6,10000000,1860000,1800,800,300,300]");
        assertThat(rows(table(file, "renewals")))
                .containsExactly(
                        "[\"Loans renewed in the period, principal moved\",1,1000000]",
                        "[\"Unpaid interest, fees and penalties carried (not added to principal)\",1,25000]",
                        "[\"Active loans that are renewals, principal outstanding\",1,1000000]",
                        "[\"Of them, arrears\",1,1050000]",
                        "[\"Of them, more than 30 days late\",0,null]");
        assertThat(file.get("notes").toString()).contains("Renewed loans are counted from 2026-10-01 to 2026-10-08");
    }

    @Test
    void thePortfolioReportEndsWithTheSameRenewals() throws Exception {
        Book book = goldenBook();
        renew(book);
        UUID user = UUID.randomUUID();

        JsonNode run = runReport(book, user, PAR_PERMS, "lending.portfolio_outstanding", Map.of(), "json");
        JsonNode file = file(book, user, PAR_PERMS, run);

        assertThat(rows(table(file, "renewals")))
                .first()
                .isEqualTo("[\"Loans renewed in the period, principal moved\",1,1000000]");
    }

    @Test
    void withoutRenewalsTheReportSaysSoAndAddsNoTable() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();

        JsonNode file = file(book, user, PAR_PERMS, par(book, user, PAR_PERMS, Map.of(), "json"));

        assertThat(file.get("notes").toString()).contains("No loan was renewed from 2026-10-01 to 2026-10-08.");
        assertThat(file.get("tables")).hasSize(2);
    }
}
