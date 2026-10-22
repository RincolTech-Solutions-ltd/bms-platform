package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Owner of the business (ADR-062, FR-IAM-18): one staff user per business, marked {@code users.is_owner}, who holds the
 * administrator role like every administrator and alone adds, removes, deactivates, demotes or promotes an administrator.
 * Nobody inside the business removes, deactivates or demotes the Owner; the Owner hands the role on by a transfer.
 *
 * <p>Every method runs in the caller's transaction, bound to the request's tenant: the row policy of {@code users} keeps it to
 * the tenant's own staff. A business with no Owner yet (one with no active administrator when V79 ran) keeps the rules of
 * before this change until a platform operator assigns one; that is logged as a warning each time a rule is skipped.
 */
@Component
class Owners {

    private static final Logger log = LoggerFactory.getLogger(Owners.class);

    private final JdbcClient jdbc;
    private final AuditLog audit;

    Owners(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The Owner of the request's business, or empty while it has none. */
    Optional<UUID> current() {
        return jdbc.sql("SELECT id FROM users WHERE kind = 'staff' AND is_owner")
                .query(UUID.class)
                .optional();
    }

    boolean isOwner(UUID userId) {
        return current().map(userId::equals).orElse(false);
    }

    /**
     * An act only the Owner may do (inviting or promoting an administrator). Refused with 403 {@code owner_only} for anyone
     * else; allowed to every holder of the route's permission while the business has no Owner.
     */
    void requireOwner(Principal actor, String what) {
        Optional<UUID> owner = current();
        if (owner.isEmpty()) {
            warnOwnerless(what);
            return;
        }
        if (!owner.get().equals(actor.userId())) {
            throw ownerOnly();
        }
    }

    /**
     * A change to another administrator (deactivate, change roles or contact, reset the second factor, a new invitation link).
     * The Owner may; anyone else is refused, with 403 {@code owner_protected} when the target is the Owner and 403
     * {@code owner_only} otherwise.
     */
    void requireMayChangeAdmin(Principal actor, UUID target, String what) {
        Optional<UUID> owner = current();
        if (owner.isEmpty()) {
            warnOwnerless(what);
            return;
        }
        if (owner.get().equals(actor.userId())) {
            return;
        }
        throw owner.get().equals(target) ? ownerProtected() : ownerOnly();
    }

    /**
     * Makes {@code to} the Owner in the caller's transaction and returns the previous Owner, or null when the business had
     * none. {@code to} must be an active administrator of the business who is not already the Owner (422
     * {@code owner_must_be_active_admin}, {@code already_owner}); the previous Owner stays an administrator. Serialised per
     * business by an advisory lock, so two transfers at once end with exactly one Owner; the unique index
     * {@code users_one_owner} holds it in any case. With {@code actorMustBeOwner} (the Owner's own transfer) the actor must still
     * be the Owner once the lock is held (403 {@code owner_only}; 409 {@code no_owner} for a business with none). Audited as
     * {@code core.user.ownership_transferred} by the actor.
     */
    UUID transfer(UUID to, UUID actorId, String actorKind, String reason, boolean actorMustBeOwner) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('bms.owner:' || current_setting('app.tenant_id'), 0))")
                .query((rs, n) -> 1)
                .single();
        UUID previous = current().orElse(null);
        if (actorMustBeOwner) {
            requireActorIsOwner(previous, actorId);
        }
        requireCandidate(to, true);
        if (to.equals(previous)) {
            throw ApiException.rule("already_owner", "This person is already the owner of the business.");
        }
        if (previous != null) {
            jdbc.sql("UPDATE users SET is_owner = false, updated_at = now(), version = version + 1 WHERE id = ?")
                    .param(previous)
                    .update();
        }
        jdbc.sql("UPDATE users SET is_owner = true, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(to)
                .update();
        Map<String, Object> before = new HashMap<>();
        before.put("owner", previous);
        Map<String, Object> after = new HashMap<>();
        after.put("owner", to);
        after.put("by", actorKind.equals("platform") ? "platform" : "owner");
        if (reason != null) {
            after.put("reason", reason.strip());
        }
        audit.record(
                new AuditLog.Entry("core.user.ownership_transferred", "core.user", to, null, before, after),
                actorId,
                actorKind);
        return previous;
    }

    /**
     * Everything {@link #transfer} refuses about the new Owner: not a staff user of this business (404), not active or not an
     * administrator (422 {@code owner_must_be_active_admin}). {@code forUpdate} locks the row, as the transfer does.
     */
    void requireCandidate(UUID userId, boolean forUpdate) {
        String status = jdbc.sql(
                        "SELECT status FROM users WHERE id = ? AND kind = 'staff'" + (forUpdate ? " FOR UPDATE" : ""))
                .param(userId)
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        boolean admin = jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(userId).query(Long.class).single() > 0;
        if (!"active".equals(status) || !admin) {
            throw ApiException.rule(
                    "owner_must_be_active_admin",
                    "Ownership can only go to an active administrator of the business. Make the person an administrator first.");
        }
    }

    /**
     * After an invitation is accepted: an administrator who joins a business that has no Owner and no other active
     * administrator becomes its Owner (the first administrator of a business, invited before V79). Audited by the system.
     */
    void claimIfFirstAdmin(UUID userId) {
        if (current().isPresent()) {
            return;
        }
        long admins = jdbc.sql("""
                        SELECT count(DISTINCT a.user_id) FROM user_role_assignments a JOIN users u ON u.id = a.user_id
                         WHERE a.role_key = 'tenant_admin' AND a.revoked_at IS NULL AND u.status = 'active'
                        """).query(Long.class).single();
        boolean isAdmin = jdbc.sql("""
                                SELECT count(*) FROM user_role_assignments
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(userId).query(Long.class).single() > 0;
        if (!isAdmin || admins != 1) {
            return;
        }
        markFirstOwner(userId, userId, "staff", "the first administrator joined a business with no owner");
    }

    /** The first administrator of a new business is its Owner from the start (still invited until they accept). */
    void markFirstOwner(UUID userId, UUID actorId, String actorKind, String reason) {
        if (current().isPresent()) {
            return;
        }
        // No version bump: this runs in the transaction that creates the user or accepts the invitation, which owns it.
        jdbc.sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(userId)
                .update();
        Map<String, Object> before = new HashMap<>();
        before.put("owner", null);
        audit.record(
                new AuditLog.Entry(
                        "core.user.owner_assigned",
                        "core.user",
                        userId,
                        null,
                        before,
                        Map.of("owner", userId, "reason", reason)),
                actorId,
                actorKind);
    }

    /** The Owner's own transfer: the business has an Owner and it is the actor. */
    static void requireActorIsOwner(UUID owner, UUID actorId) {
        if (owner == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "no_owner",
                    "No owner yet",
                    "This business has no owner yet. The platform operator assigns one.");
        }
        if (!owner.equals(actorId)) {
            throw ownerOnly();
        }
    }

    private static void warnOwnerless(String what) {
        log.warn(
                "The business has no owner, so the owner-only rule is not applied to: {}. A platform operator assigns one.",
                what);
    }

    static ApiException ownerOnly() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "owner_only",
                "Only the owner",
                "Only the owner of the business can add, remove, deactivate or change an administrator.");
    }

    static ApiException ownerProtected() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "owner_protected",
                "The owner is protected",
                "Nobody can remove, deactivate or change the owner of the business. The owner can hand ownership to another administrator.");
    }
}
