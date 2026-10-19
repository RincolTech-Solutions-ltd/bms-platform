package com.rincoltech.bms.retail.sales.internal;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for write-offs of credit sales (ADR-043). Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class WriteOffsRepository {

    private final JdbcClient jdbc;

    WriteOffsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A write-off as stored. */
    record Row(
            UUID id,
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate writtenOffOn,
            String currency,
            long unpaidMinor,
            long lossMinor,
            String recognition,
            String reason,
            UUID journalEntryId,
            UUID approvalId,
            UUID approvedBy,
            UUID createdBy,
            Instant createdAt) {}

    void insert(Row w) {
        jdbc.sql("""
                        INSERT INTO retail_sale_writeoffs (id, tenant_id, sale_id, branch_id, written_off_on, currency,
                            unpaid_minor, loss_minor, recognition, reason, journal_entry_id, approval_id, approved_by, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        w.id(),
                        w.saleId(),
                        w.branchId(),
                        Date.valueOf(w.writtenOffOn()),
                        w.currency(),
                        w.unpaidMinor(),
                        w.lossMinor(),
                        w.recognition(),
                        w.reason(),
                        w.journalEntryId(),
                        w.approvalId(),
                        w.approvedBy(),
                        w.createdBy())
                .update();
    }

    Optional<Row> forSale(UUID saleId) {
        return jdbc.sql("""
                        SELECT w.id, w.sale_id, s.sale_no, w.branch_id, w.written_off_on, w.currency, w.unpaid_minor,
                               w.loss_minor, w.recognition, w.reason, w.journal_entry_id, w.approval_id, w.approved_by,
                               w.created_by, w.created_at
                          FROM retail_sale_writeoffs w JOIN retail_sales s ON s.id = w.sale_id
                         WHERE w.sale_id = ?
                        """)
                .param(saleId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("written_off_on").toLocalDate(),
                        rs.getString("currency"),
                        rs.getLong("unpaid_minor"),
                        rs.getLong("loss_minor"),
                        rs.getString("recognition"),
                        rs.getString("reason"),
                        rs.getObject("journal_entry_id", UUID.class),
                        rs.getObject("approval_id", UUID.class),
                        rs.getObject("approved_by", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
