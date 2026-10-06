package com.rincoltech.bms.core.notifications;

import java.util.Map;

/**
 * The notification outbox (FR-NTF-01, FR-NTF-10, spec section 11): a message is written in the
 * caller's transaction, so it exists exactly when the change that causes it commits, and a sender
 * job delivers it afterwards, at least once. The idempotency key is unique: writing the same key
 * twice keeps the first row, so a retried cause never queues a second copy.
 */
public interface Outbox {

    String EMAIL = "email";
    String TELEGRAM = "telegram";
    /** The recipient of a Telegram operator alert; the chat id stays in the environment. */
    String OPERATOR_CHAT = "operator";

    /**
     * Writes one row in the caller's transaction, which must exist. Returns false when the
     * idempotency key was already used.
     */
    boolean enqueue(Message message);

    /**
     * @param channel {@link #EMAIL} or {@link #TELEGRAM}
     * @param recipient an email address, or {@link #OPERATOR_CHAT}; never logged unmasked
     * @param templateKey for example {@code onboarding.activation}; rendered when sent
     * @param params template values; cleared from the row once it is sent
     * @param idempotencyKey unique per message, for example {@code onboarding.activation:<id>}
     */
    record Message(
            String channel, String recipient, String templateKey, Map<String, String> params, String idempotencyKey) {

        public Message {
            params = Map.copyOf(params);
        }
    }
}
