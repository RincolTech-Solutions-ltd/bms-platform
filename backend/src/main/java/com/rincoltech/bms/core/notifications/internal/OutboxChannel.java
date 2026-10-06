package com.rincoltech.bms.core.notifications.internal;

/**
 * One delivery channel of the outbox. A channel that is not {@link #enabled()} is never asked to
 * send: its rows stay {@code pending} until it is configured. Tests replace the real channels with
 * fakes; nothing here may log the recipient, the text or a secret.
 */
interface OutboxChannel {

    String channel();

    boolean enabled();

    /** Sends one message or throws; the exception's message must not carry the text or recipient. */
    void send(Delivery delivery) throws Exception;

    /**
     * @param recipient an email address, or {@code operator} for the Telegram operator chat
     * @param idempotencyKey the row's key, carried to the provider where it supports one
     */
    record Delivery(String recipient, String subject, String text, String idempotencyKey) {

        @Override
        public String toString() {
            return "Delivery[key=" + idempotencyKey + "]";
        }
    }
}
