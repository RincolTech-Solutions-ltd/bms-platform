package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.internal.Accounts.Account;
import com.rincoltech.bms.core.identity.internal.UserApi.SignInResetLink;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A staff user who forgot the password gets back in with a one-time link (FR-IAM-15, ADR-042): asked for on the sign-in page
 * (the link goes to the email on file) or issued by a tenant admin (the link is shown once to the admin). The token is 256
 * random bits, stored as a SHA-256 hash, single use, valid for one hour; a newer link revokes the older ones. Using it sets the
 * password under the same rules and hashing as an invitation and ends every session of the user. It never touches the second
 * factor, the failed-attempt lock or the user's status: only an active user can reset, and a user still invited keeps the
 * invitation path.
 *
 * <p>The request for a link answers the same whether or not an account matched; everything that differs (no account, not
 * active, no email, too many links this hour) happens inside and is visible only in the audit log.
 */
@Service
class PasswordResets {

    static final Duration TTL = Duration.ofHours(1);
    /** Links one account can ask for on the sign-in page within an hour; further requests are answered and ignored. */
    static final int MAX_SELF_REQUESTS_PER_HOUR = 3;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH);

    static final String RESET_TEMPLATE = "identity.password_reset";
    static final String CHANGED_TEMPLATE = "identity.password_changed";

    private final JdbcClient jdbc;
    private final StaffAccounts accounts;
    private final Passwords passwords;
    private final PlatformHost hosts;
    private final CurrentTenant tenant;
    private final Outbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    PasswordResets(
            JdbcClient jdbc,
            StaffAccounts accounts,
            Passwords passwords,
            PlatformHost hosts,
            CurrentTenant tenant,
            Outbox outbox,
            AuditLog audit,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.passwords = passwords;
        this.hosts = hosts;
        this.tenant = tenant;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private record Target(UUID id, String email, String phone, String status) {}

    /**
     * The user asks for a link on the sign-in page. Returns nothing, on purpose: the caller must not be able to tell what
     * happened.
     */
    @Transactional
    void request(String login) {
        Optional<Account> found = accounts.byLogin(login == null ? null : login.trim());
        if (found.isEmpty()) {
            return;
        }
        Target user = lock(found.get().id());
        if (user == null) {
            return;
        }
        if (!"active".equals(user.status())) {
            refused(user, "not_active");
            return;
        }
        if (user.email() == null) {
            refused(user, "no_email");
            return;
        }
        Instant now = clock.now();
        long recent = jdbc.sql("""
                        SELECT count(*) FROM user_password_resets WHERE user_id = ? AND origin = 'self' AND created_at > ?
                        """)
                .params(user.id(), Timestamp.from(now.minus(Duration.ofHours(1))))
                .query(Long.class)
                .single();
        if (recent >= MAX_SELF_REQUESTS_PER_HOUR) {
            refused(user, "rate_limited");
            return;
        }
        Issued issued = issue(user.id(), "self", null, now);
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.requested",
                        "core.user",
                        user.id(),
                        null,
                        Map.of(),
                        Map.of(
                                "origin",
                                "self",
                                "expires_at",
                                issued.expiresAt().toString())),
                user.id(),
                "staff");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenant_name", tenant.profile().name());
        params.put("link", issued.url());
        params.put("expires_at", stamp(issued.expiresAt()));
        // The row carries the link until it is sent (then the parameters are cleared) and lapses with the token.
        outbox.enqueue(new Outbox.Message(
                Outbox.EMAIL,
                user.email(),
                RESET_TEMPLATE,
                params,
                RESET_TEMPLATE + ":" + issued.id(),
                "password-reset:" + user.id(),
                issued.expiresAt()));
    }

    /** A tenant admin issues a link for an active user; it is returned once and never emailed (FR-IAM-15). */
    @Transactional
    SignInResetLink issueForAdmin(UUID userId) {
        Principal admin = CurrentPrincipal.require();
        Target user = lock(userId);
        if (user == null) {
            throw ApiException.notFound();
        }
        requireInScope(admin, userId);
        if (!"active".equals(user.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Invalid status transition",
                    "invited".equals(user.status())
                            ? "This user has not accepted the invitation yet; send the invitation link again."
                            : "A deactivated user cannot reset the password.");
        }
        Instant now = clock.now();
        Issued issued = issue(userId, "admin", admin.userId(), now);
        audit.record(new AuditLog.Entry(
                "core.password_reset.requested",
                "core.user",
                userId,
                null,
                Map.of(),
                Map.of("origin", "admin", "expires_at", issued.expiresAt().toString())));
        // The token never reaches the audit log: the row records that the link was shown.
        audit.record(new AuditLog.Entry(
                "core.password_reset.link_revealed",
                "core.user",
                userId,
                null,
                Map.of(),
                Map.of("expires_at", issued.expiresAt().toString())));
        return new SignInResetLink(issued.url(), issued.expiresAt());
    }

    /**
     * The link sets a new password. Returns the user, whom the caller signs in through {@link AuthFlow#afterReset}. A failure
     * before the password is written (bad link, weak password) changes nothing, so the link still works for a second try.
     */
    @Transactional(noRollbackFor = ApiException.class)
    UUID complete(String token, String password) {
        Instant now = clock.now();
        String hash = Secrets.sha256(token == null ? "" : token);
        // The user's row is locked before the link's, the order request() and issueForAdmin() use, so two of them
        // cannot deadlock.
        Pending seen = pending(hash, false).orElseThrow(PasswordResets::linkInvalid);
        Account account = accounts.byIdForUpdate(seen.userId()).orElseThrow(PasswordResets::linkInvalid);
        Pending link = pending(hash, true).orElseThrow(PasswordResets::linkInvalid);
        if (link.usedAt() != null || link.revokedAt() != null) {
            throw linkInvalid();
        }
        if (!now.isBefore(link.expiresAt())) {
            throw ApiException.rule("reset_link_expired", "The reset link has expired; ask for a new one.");
        }
        Target user = lock(link.userId());
        if (user == null || !"active".equals(user.status())) {
            throw linkInvalid();
        }
        passwords.checkStrength(password, user.email(), user.phone());
        jdbc.sql(
                        "UPDATE user_credentials SET password_hash = ?, password_changed_at = ?, updated_at = ? WHERE user_id = ?")
                .params(passwords.hash(password), Timestamp.from(now), Timestamp.from(now), account.id())
                .update();
        jdbc.sql("UPDATE user_password_resets SET used_at = ? WHERE id = ?")
                .params(Timestamp.from(now), link.id())
                .update();
        revokeOpen(user.id(), now);
        int revoked = accounts.sessions().revokeAllOf(user.id(), "password_reset", now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("origin", link.origin());
        data.put("sessions_revoked", revoked);
        audit.record(
                new AuditLog.Entry("core.password_reset.used", "core.user", user.id(), null, Map.of(), data),
                user.id(),
                "staff");
        if (user.email() != null) {
            // Tells the owner of the mailbox the password changed; carries no link, so a stale copy of it is harmless.
            outbox.enqueue(new Outbox.Message(
                    Outbox.EMAIL,
                    user.email(),
                    CHANGED_TEMPLATE,
                    Map.of("tenant_name", tenant.profile().name(), "when", stamp(now)),
                    CHANGED_TEMPLATE + ":" + link.id(),
                    "password-reset:" + user.id(),
                    now.plus(Duration.ofDays(1))));
        }
        return user.id();
    }

    private record Pending(UUID id, UUID userId, String origin, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private Optional<Pending> pending(String tokenHash, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT id, user_id, origin, expires_at, used_at, revoked_at FROM user_password_resets
                         WHERE token_hash = ?""" + (forUpdate ? " FOR UPDATE" : ""))
                .param(tokenHash)
                .query((rs, n) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("origin"),
                        SessionStore.instant(rs.getTimestamp("expires_at")),
                        SessionStore.instant(rs.getTimestamp("used_at")),
                        SessionStore.instant(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    /** Revokes the open links of a user: before a new one is written, and when the user is deactivated. */
    void revokeOpen(UUID userId, Instant at) {
        jdbc.sql("""
                        UPDATE user_password_resets SET revoked_at = ?
                         WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                        """).params(Timestamp.from(at), userId).update();
    }

    private record Issued(UUID id, String url, Instant expiresAt) {}

    /** Revokes every open link of the user and writes a new one (one hour). The user's row is locked by the caller. */
    private Issued issue(UUID userId, String origin, UUID requestedBy, Instant now) {
        Instant expires = now.plus(TTL);
        revokeOpen(userId, now);
        String token = Secrets.token();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO user_password_resets (id, tenant_id, user_id, token_hash, expires_at, origin, requested_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        """)
                .params(id, userId, Secrets.sha256(token), Timestamp.from(expires), origin, requestedBy)
                .update();
        String url = hosts.tenantOrigin(tenant.profile().slug()) + "/reset-password#token=" + token;
        return new Issued(id, url, expires);
    }

    /**
     * A tenant admin whose permission covers some branches only may reset a user whose every role is in those branches. A user
     * with a role over every branch (a tenant admin) needs an admin who covers every branch.
     */
    private void requireInScope(Principal admin, UUID userId) {
        boolean all = admin.scopeOf("core.users.manage")
                .map(Principal.BranchScope::all)
                .orElse(false);
        if (all) {
            return;
        }
        var branches = jdbc.sql("""
                        SELECT branch_id FROM user_role_assignments WHERE user_id = ? AND revoked_at IS NULL
                        """)
                .param(userId)
                .query((rs, n) -> rs.getObject("branch_id", UUID.class))
                .list();
        boolean covered =
                !branches.isEmpty() && branches.stream().allMatch(b -> b != null && admin.may("core.users.manage", b));
        if (!covered) {
            throw ApiException.notFound();
        }
    }

    private Target lock(UUID userId) {
        return jdbc.sql("SELECT id, email, phone_e164, status FROM users WHERE id = ? AND kind = 'staff' FOR UPDATE")
                .param(userId)
                .query((rs, n) -> new Target(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("phone_e164"),
                        rs.getString("status")))
                .optional()
                .orElse(null);
    }

    private void refused(Target user, String reason) {
        audit.record(
                new AuditLog.Entry(
                        "core.password_reset.refused",
                        "core.user",
                        user.id(),
                        null,
                        Map.of(),
                        Map.of("reason", reason)),
                user.id(),
                "staff");
    }

    private static String stamp(Instant at) {
        return STAMP.format(at.atOffset(ZoneOffset.UTC));
    }

    private static ApiException linkInvalid() {
        return ApiException.rule("reset_link_invalid", "The reset link is not valid; ask for a new one.");
    }
}
