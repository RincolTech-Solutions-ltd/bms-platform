package com.rincoltech.bms.core.notifications;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one way a vertical queues a member SMS for a business event (FR-NTF-02, ADR-036 third amendment): a loan
 * disbursed, a repayment received, a loan closed, an investment funded, maturing or paid out. It sits on the outbox
 * ({@link Outbox}) and adds what every event needs and no module should write again: the number is read without guessing
 * (a number we would not text is never queued), the key is deterministic per event and member (so a retried or replayed
 * event never texts twice), the tenant's name and balance rules are applied, and a failure to queue never fails the
 * business transaction that called it.
 *
 * <p>Every call runs in the caller's transaction, which must exist and be bound to the tenant. The row is written in
 * that transaction, so it exists exactly when the event commits and a rolled-back event sends nothing (FR-NTF-01). A
 * provider outage cannot reach the caller: the row is only written here, and the sender job delivers it later.
 *
 * <p>What this port does not decide is left to the channel and checked again on every attempt: the operator's and the
 * tenant's switches, the switch-on time (a row queued before it is never sent), the opt-out, the sending window, the
 * caps, the tenant's own wording and the segment count. Personal data: the phone number is never logged.
 */
public interface SmsEvents {

    /** The outcome of {@link #queue}, for the caller's tests and logs; no caller needs to act on it. */
    enum Result {
        /** A new row was written. */
        QUEUED,
        /** Nothing was written: the event was queued before, or SMS is switched off for the tenant. */
        NOT_QUEUED,
        /** The member has no number we would text (none, or one that cannot be read without guessing). */
        NO_PHONE,
        /** The row could not be written; the caller's transaction is unharmed. */
        FAILED
    }

    /**
     * One message about a member.
     *
     * @param templateKey for example {@code loan.repayment_received}
     * @param memberId the member the message is about (the opt-out is matched on it); the message goes to {@code phone}
     * @param phone the member's primary phone, as stored; null or unreadable queues nothing
     * @param params the template values; {@code tenant_name} is added when absent, and {@code balance} is dropped
     *     unless the tenant asked for it ({@link #includeBalance})
     * @param eventRef what makes the event one event, for example the id of the transaction; with the template and the
     *     member it is the idempotency key
     * @param category {@link Outbox#TRANSACTIONAL} (a receipt, not held by the sending window, expires after an hour)
     *     or {@link Outbox#REMINDER} (held for the tenant's window)
     */
    record MemberSms(
            String templateKey,
            UUID memberId,
            String phone,
            Map<String, String> params,
            String eventRef,
            String category) {

        public MemberSms {
            params = Map.copyOf(params);
            category = category == null ? Outbox.TRANSACTIONAL : category;
        }

        public MemberSms(String templateKey, UUID memberId, String phone, Map<String, String> params, String eventRef) {
            this(templateKey, memberId, phone, params, eventRef, null);
        }
    }

    /**
     * Whether a message could be sent for the bound tenant now: the operator's switch and the tenant's own are both on
     * and neither cap is 0. A caller asks first so that a tenant without SMS costs a money event two small reads and no
     * member lookup; {@link #queue} checks again, so the answer is an optimisation and never the authority.
     */
    boolean active();

    /** Whether the tenant asked for the balance in receipts (default no: a phone may be shared). */
    boolean includeBalance();

    /** {@code 1234567} with the currency's exponent 0 is {@code 1,234,567}; with exponent 2, {@code 12,345.67}. */
    String amount(long minor, String currency);

    /** {@code 15/10/2026}: day, month and year, the order Uganda writes them, the same length always. */
    String date(LocalDate date);

    /** Writes the row in the caller's transaction (see the class comment). Never throws for a delivery reason. */
    Result queue(MemberSms message);

    /**
     * The call a module makes for an event: nothing is looked up, formatted or written unless {@link #active} (a tenant
     * without SMS costs a money event two small reads), and nothing that goes wrong while preparing the message, such as
     * the member lookup or a value that cannot be formatted, reaches the caller. {@code phone} and {@code params} are
     * asked for only when SMS is active.
     */
    Result queueWhenActive(
            String templateKey,
            UUID memberId,
            String eventRef,
            String category,
            Supplier<String> phone,
            Supplier<Map<String, String>> params);
}
