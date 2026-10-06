package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers outbox rows (FR-NTF-09). One transaction per row: claim the next due row of an enabled
 * channel ({@code FOR UPDATE SKIP LOCKED}, so concurrent senders never take the same row), send
 * it, record the result, commit. A crash between the send and the commit leaves the row
 * {@code pending} and it is sent again: delivery is at least once, and the idempotency key travels
 * with the message where the provider supports one. The third failure marks the row
 * {@code failed}; the operator portal lists it. Logs carry the row id, channel, template and
 * attempt only: never the recipient, the text or a link.
 */
@Component
class OutboxDispatcher {

    static final int MAX_ATTEMPTS = 3;
    /** Wait before the second and third attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OutboxTemplates templates;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final List<OutboxChannel> channels;

    OutboxDispatcher(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            OutboxTemplates templates,
            ObjectMapper mapper,
            BusinessClock clock,
            List<OutboxChannel> channels) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.templates = templates;
        this.mapper = mapper;
        this.clock = clock;
        this.channels = List.copyOf(channels);
    }

    /** The channels that send; the others keep their rows pending. */
    List<String> enabledChannels() {
        return enabled(channels).keySet().stream().sorted().toList();
    }

    /** Sends up to {@code max} due rows with the configured channels; returns how many were tried. */
    int dispatch(int max) {
        return dispatch(channels, max);
    }

    int dispatch(List<OutboxChannel> using, int max) {
        Map<String, OutboxChannel> byName = enabled(using);
        if (byName.isEmpty()) {
            return 0;
        }
        String[] names = byName.keySet().toArray(String[]::new);
        int tried = 0;
        while (tried < max) {
            Boolean claimed = transactions.execute(status -> sendOne(byName, names));
            if (!Boolean.TRUE.equals(claimed)) {
                break;
            }
            tried++;
        }
        return tried;
    }

    private Boolean sendOne(Map<String, OutboxChannel> byName, String[] names) {
        Instant now = clock.now();
        Optional<Row> claimed = jdbc.sql("SELECT * FROM notification_outbox_claim(?, ?)")
                .params(names, Timestamp.from(now))
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("recipient"),
                        rs.getString("template_key"),
                        rs.getString("params"),
                        rs.getString("idempotency_key"),
                        rs.getInt("attempts")))
                .optional();
        if (claimed.isEmpty()) {
            return false;
        }
        Row row = claimed.get();
        String error = null;
        try {
            OutboxTemplates.Rendered rendered =
                    templates.render(row.channel(), row.templateKey(), mapper.readValue(row.params(), PARAMS));
            byName.get(row.channel())
                    .send(new OutboxChannel.Delivery(
                            row.recipient(), rendered.subject(), rendered.text(), row.idempotencyKey()));
        } catch (Exception e) {
            error = safeError(e);
        }
        int attempt = row.attempts() + 1;
        Instant retryAt = now.plus(BACKOFF.get(Math.min(attempt, BACKOFF.size()) - 1));
        String status = jdbc.sql("SELECT notification_outbox_record(?, ?, ?, ?, ?, ?)")
                .params(row.id(), error == null, error, MAX_ATTEMPTS, Timestamp.from(retryAt), Timestamp.from(now))
                .query(String.class)
                .single();
        if (error == null) {
            log.info("outbox row sent: id={} channel={} template={}", row.id(), row.channel(), row.templateKey());
        } else {
            log.warn(
                    "outbox send failed: id={} channel={} template={} attempt={} status={} error={}",
                    row.id(),
                    row.channel(),
                    row.templateKey(),
                    attempt,
                    status,
                    error);
        }
        return true;
    }

    /**
     * The exception class, plus the message of the senders' own exceptions, which are written to
     * carry no recipient, text or secret. A provider exception's message can name an address, so
     * only its class is kept.
     */
    static String safeError(Exception e) {
        boolean ours = e instanceof IllegalStateException || e instanceof IllegalArgumentException;
        return ours && e.getMessage() != null
                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                : e.getClass().getSimpleName();
    }

    private static Map<String, OutboxChannel> enabled(List<OutboxChannel> using) {
        return using.stream()
                .filter(OutboxChannel::enabled)
                .collect(Collectors.toMap(OutboxChannel::channel, c -> c, (a, b) -> a));
    }

    private record Row(
            UUID id,
            String channel,
            String recipient,
            String templateKey,
            String params,
            String idempotencyKey,
            int attempts) {}
}
