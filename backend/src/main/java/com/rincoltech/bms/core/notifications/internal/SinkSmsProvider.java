package com.rincoltech.bms.core.notifications.internal;

/**
 * The only provider allowed outside production (ADR-036 decision 4): it records that a message
 * would have gone and sends nothing. It holds no HTTP client, no credentials and no URL, so it
 * cannot reach a network by construction; the startup guard keeps it out of production.
 */
final class SinkSmsProvider implements SmsProvider {

    static final String NAME = "sink";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome send(String to, String body, String senderId, String clientRef) {
        return new Outcome(NAME, Status.SUNK, "sink-" + Integer.toHexString(clientRef.hashCode()), null, null, null);
    }
}
