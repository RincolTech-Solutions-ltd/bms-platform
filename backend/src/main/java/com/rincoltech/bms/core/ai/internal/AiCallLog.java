package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.AiGateway.Reason;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code ai_calls} log and the caps arithmetic over it (migration V51). A call is written
 * <em>before</em> the network call, in its own short transaction, holding a reservation (the input
 * estimate plus the output cap) against the daily caps, and finished after it; the sums the caps
 * read are over {@code tokens_counted} and {@code cost_counted_micro}, so a call in flight counts the
 * moment it starts and two calls at once cannot both fit into the last of the cap. The reserve step
 * takes one transaction-level advisory lock, because the platform ceiling is a sum across tenants that
 * no row lock covers; it holds it for two short queries and an insert. Rows hold counts, a model
 * name, a closed reason and a request hash, never text.
 */
@Component
class AiCallLog {

    /** One lock for the reserve step of every tenant: the platform ceiling is shared. */
    private static final long RESERVE_LOCK = 7_220_000_001L;

    private final JdbcClient jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final TransactionTemplate joined;

    AiCallLog(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.writes = new TransactionTemplate(transactions);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.joined = new TransactionTemplate(transactions);
    }

    /** What the caps count, in tokens and in micro-units of the price currency. */
    record Usage(long tokens, long costMicro) {}

    /** The daily limits one reservation is checked against, already the lower of tenant and platform. */
    record Limits(
            long tenantTokens, long tenantCostMicro, long platformTokens, long platformCostMicro, int maxInputTokens) {}

    /** The two day windows: the tenant's own timezone, and the platform's. */
    record Windows(Instant tenantFrom, Instant tenantTo, Instant platformFrom, Instant platformTo) {}

    record Request(
            UUID id,
            String purpose,
            UUID askedBy,
            String model,
            String requestHash,
            long estimatedInputTokens,
            int maxTokens,
            String currency,
            Instant now,
            Windows windows,
            Limits limits,
            Pricing pricing) {}

    /** Cost estimation, kept apart from the properties so the log is testable alone. */
    interface Pricing {
        long costMicro(long inputTokens, long outputTokens);
    }

    /** @param refused null when the call is reserved and may go ahead */
    record Reservation(UUID id, Reason refused) {}

    Reservation reserve(Request r) {
        return writes.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(?)")
                    .param(RESERVE_LOCK)
                    .query()
                    .listOfRows();
            long reserveTokens = r.estimatedInputTokens() + r.maxTokens();
            long reserveCost = r.pricing().costMicro(r.estimatedInputTokens(), r.maxTokens());
            Reason refusal = null;
            if (r.estimatedInputTokens() > r.limits().maxInputTokens()) {
                refusal = Reason.INPUT_TOO_LARGE;
            } else {
                Usage tenant = tenantUsage(r.windows().tenantFrom(), r.windows().tenantTo());
                Usage platform =
                        platformUsage(r.windows().platformFrom(), r.windows().platformTo());
                if (tenant.tokens() + reserveTokens > r.limits().tenantTokens()) {
                    refusal = Reason.TENANT_TOKEN_CAP;
                } else if (tenant.costMicro() + reserveCost > r.limits().tenantCostMicro()) {
                    refusal = Reason.TENANT_COST_CAP;
                } else if (platform.tokens() + reserveTokens > r.limits().platformTokens()) {
                    refusal = Reason.PLATFORM_TOKEN_CAP;
                } else if (platform.costMicro() + reserveCost > r.limits().platformCostMicro()) {
                    refusal = Reason.PLATFORM_COST_CAP;
                }
            }
            if (refusal != null) {
                insertRefused(r, refusal);
                return new Reservation(r.id(), refusal);
            }
            jdbc.sql("""
                            INSERT INTO ai_calls (id, tenant_id, created_at, purpose, user_id, model, request_hash,
                                                  outcome, reserved_tokens, tokens_counted, estimated_cost_micro,
                                                  cost_counted_micro, cost_currency)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?,
                                    'pending', ?, ?, ?, ?, ?)
                            """)
                    .params(
                            r.id(),
                            Timestamp.from(r.now()),
                            r.purpose(),
                            r.askedBy(),
                            r.model(),
                            r.requestHash(),
                            (int) reserveTokens,
                            (int) reserveTokens,
                            reserveCost,
                            reserveCost,
                            r.currency())
                    .update();
            return new Reservation(r.id(), null);
        });
    }

    /** Records a refusal that came after the reservation (the breaker): the row holds nothing against the caps. */
    void refuse(UUID id, Instant now, Reason reason) {
        finish(id, now, new Finish("refused", reason, 0, 0, null, null, 0, null, 0, null));
    }

    void succeed(
            UUID id, Instant now, int attempts, int inputTokens, int outputTokens, long costMicro, long latencyMs) {
        finish(
                id,
                now,
                new Finish(
                        "ok",
                        null,
                        200,
                        attempts,
                        inputTokens,
                        outputTokens,
                        (long) inputTokens + outputTokens,
                        costMicro,
                        costMicro,
                        latencyMs));
    }

    void fail(UUID id, Instant now, Reason reason, int httpStatus, int attempts, long latencyMs) {
        finish(id, now, new Finish("failed", reason, httpStatus, attempts, null, null, 0, null, 0, latencyMs));
    }

    private record Finish(
            String outcome,
            Reason reason,
            int httpStatus,
            int attempts,
            Integer inputTokens,
            Integer outputTokens,
            long tokensCounted,
            Long estimatedCostMicro,
            long costCountedMicro,
            Long latencyMs) {}

    private void finish(UUID id, Instant now, Finish f) {
        Integer rows = writes.execute(status -> jdbc.sql("""
                                UPDATE ai_calls
                                   SET finished_at = ?, outcome = ?, reason = ?, http_status = ?, attempts = ?,
                                       input_tokens = ?, output_tokens = ?, tokens_counted = ?,
                                       estimated_cost_micro = ?, cost_counted_micro = ?, latency_ms = ?
                                 WHERE id = ? AND outcome = 'pending'
                                """)
                .params(
                        Timestamp.from(now),
                        f.outcome(),
                        f.reason() == null ? null : f.reason().code(),
                        f.httpStatus() == 0 ? null : (short) f.httpStatus(),
                        (short) f.attempts(),
                        f.inputTokens(),
                        f.outputTokens(),
                        (int) f.tokensCounted(),
                        f.estimatedCostMicro(),
                        f.costCountedMicro(),
                        f.latencyMs() == null ? null : (int) Math.min(Integer.MAX_VALUE, f.latencyMs()),
                        id)
                .update());
        if (rows == null || rows != 1) {
            throw new IllegalStateException("ai_calls row " + id + " was not pending");
        }
    }

    private void insertRefused(Request r, Reason reason) {
        jdbc.sql("""
                        INSERT INTO ai_calls (id, tenant_id, created_at, finished_at, purpose, user_id, model,
                                              request_hash, outcome, reason, cost_currency)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, 'refused', ?, ?)
                        """)
                .params(
                        r.id(),
                        Timestamp.from(r.now()),
                        Timestamp.from(r.now()),
                        r.purpose(),
                        r.askedBy(),
                        r.model(),
                        r.requestHash(),
                        reason.code(),
                        r.currency())
                .update();
    }

    /** Today's usage of the bound tenant, a call in flight included. */
    Usage tenantUsage(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT coalesce(sum(tokens_counted), 0)::bigint AS tokens,
                               coalesce(sum(cost_counted_micro), 0)::bigint AS cost
                          FROM ai_calls WHERE created_at >= ? AND created_at < ?
                        """)
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Usage(rs.getLong("tokens"), rs.getLong("cost")))
                .single();
    }

    Usage platformUsage(Instant from, Instant to) {
        return jdbc.sql("SELECT tokens, cost_micro FROM ai_platform_usage(?, ?)")
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Usage(rs.getLong("tokens"), rs.getLong("cost_micro")))
                .single();
    }

    /** As {@link #tenantUsage}, in a read-only transaction of its own, for the settings status. */
    Usage tenantUsageToday(Instant from, Instant to) {
        return reads.execute(status -> tenantUsage(from, to));
    }

    /**
     * The nightly housekeeping for the bound tenant: a call left pending for an hour (the process died
     * between reserving and finishing) is closed as failed, keeping its reservation against that day,
     * and rows past the 90 day retention are deleted (spec 5.10). Returns the rows deleted.
     */
    int purge() {
        Integer deleted = joined.execute(status -> {
            jdbc.sql("""
                            UPDATE ai_calls SET outcome = 'failed', reason = 'abandoned', finished_at = now()
                             WHERE outcome = 'pending' AND created_at < now() - interval '1 hour'
                            """).update();
            return jdbc.sql("DELETE FROM ai_calls WHERE created_at < now() - interval '90 days'")
                    .update();
        });
        return deleted == null ? 0 : deleted;
    }
}
