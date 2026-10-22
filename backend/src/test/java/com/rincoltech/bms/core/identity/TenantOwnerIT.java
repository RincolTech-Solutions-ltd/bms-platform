package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Owner of a business (FR-IAM-18, ADR-062) through HTTP: one Owner per business, who alone adds, removes, deactivates,
 * demotes or promotes an administrator; nobody inside the business removes, deactivates or demotes the Owner; the Owner hands
 * ownership to another active administrator with a password or authenticator step-up; a platform operator makes an active
 * administrator the Owner with a reason. Everyday powers are unchanged: an administrator still manages every staff member who
 * is not an administrator. All users, addresses and passwords are fabricated.
 */
class TenantOwnerIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID owner;
    UUID adminA;
    UUID adminB;
    UUID cashier;
    Session ownerSession;
    Session adminSession;

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        t = TestDatabase.tenant("owner", true);
        api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("owner");
        String adminEmail = Api.email("admin-a");
        owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        adminA = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminB = Api.staff(t, Api.email("admin-b"), new Role("tenant_admin", null));
        cashier = Api.staff(t, Api.email("cashier"), new Role("cashier", t.headOffice()));
        makeOwner(t.tenantId(), owner);
        ownerSession = api.signIn(owner, ownerEmail, Api.PASSWORD, null);
        adminSession = api.signIn(adminA, adminEmail, Api.PASSWORD, null);
    }

    static void makeOwner(UUID tenantId, UUID userId) {
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(tenantId, userId)
                .update();
    }

    static List<UUID> owners(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT id FROM users WHERE tenant_id = ? AND is_owner")
                .param(tenantId)
                .query(UUID.class)
                .list();
    }

    long audits(String action, UUID entityId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(t.tenantId(), action, entityId)
                .query(Long.class)
                .single();
    }

    static Map<String, Object> role(String key, UUID branch) {
        Map<String, Object> r = new HashMap<>();
        r.put("role_key", key);
        r.put("branch_id", branch);
        return r;
    }

    ResponseEntity<JsonNode> invite(String roleKey, UUID branch, Session by) {
        return api.post(
                "/api/v1/users",
                Map.of(
                        "full_name",
                        "Test Invitee",
                        "email",
                        Api.email("invitee"),
                        "roles",
                        List.of(role(roleKey, branch))),
                by.accessToken());
    }

    ResponseEntity<JsonNode> setRoles(UUID userId, List<Map<String, Object>> roles, Session by) {
        return api.call(HttpMethod.PUT, "/api/v1/users/" + userId + "/roles", roles, by.accessToken());
    }

    ResponseEntity<JsonNode> patchEmail(UUID userId, Session by) {
        String etag = api.get("/api/v1/users/" + userId, by.accessToken())
                .getHeaders()
                .getETag();
        return api.call(
                HttpMethod.PATCH,
                "/api/v1/users/" + userId,
                Map.of("email", Api.email("changed")),
                by.accessToken(),
                Map.of("If-Match", etag));
    }

    ResponseEntity<JsonNode> transfer(UUID to, Session by, String password) {
        Map<String, Object> body = new HashMap<>();
        if (password != null) {
            body.put("password", password);
        }
        return api.post("/api/v1/users/" + to + "/transfer-ownership", body, by.accessToken());
    }

    static void assertRefused(ResponseEntity<JsonNode> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
        assertThat(r.getBody().get("code").asString()).isEqualTo(code);
    }

    /** FR-IAM-18: an administrator cannot touch the Owner, nor add, remove, promote or demote another administrator. */
    @Test
    void anAdministratorCannotChangeTheOwnerOrAnotherAdministrator() {
        String ownerBase = "/api/v1/users/" + owner;
        assertRefused(
                api.post(ownerBase + "/deactivate", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(
                setRoles(owner, List.of(role("cashier", t.headOffice())), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(
                api.post(ownerBase + "/mfa/reset", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(patchEmail(owner, adminSession), HttpStatus.FORBIDDEN, "owner_protected");

        assertRefused(invite("tenant_admin", null, adminSession), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(
                api.post("/api/v1/users/" + adminB + "/deactivate", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                setRoles(adminB, List.of(role("cashier", t.headOffice())), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                setRoles(cashier, List.of(role("tenant_admin", null)), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(patchEmail(adminB, adminSession), HttpStatus.FORBIDDEN, "owner_only");

        // Nothing changed: the Owner is still the one Owner and an active administrator, B is still an administrator.
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(api.get(ownerBase, ownerSession.accessToken())
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("active");
        assertThat(api.get("/api/v1/users/" + adminB, adminSession.accessToken())
                        .getBody()
                        .get("roles")
                        .get(0)
                        .get("role_key")
                        .asString())
                .isEqualTo("tenant_admin");

        // Everyday powers are unchanged: the administrator manages every staff member who is not an administrator.
        assertThat(invite("cashier", t.headOffice(), adminSession).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(setRoles(cashier, List.of(role("loan_officer", t.headOffice())), adminSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.post("/api/v1/users/" + cashier + "/deactivate", null, adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-18: the Owner invites, promotes, demotes and deactivates administrators, and cannot demote themselves. */
    @Test
    void theOwnerManagesTheAdministratorsAndMustTransferBeforeStepping() {
        assertThat(invite("tenant_admin", null, ownerSession).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(setRoles(cashier, List.of(role("tenant_admin", null)), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(setRoles(adminB, List.of(role("cashier", t.headOffice())), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchEmail(adminA, ownerSession).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.post("/api/v1/users/" + adminA + "/deactivate", null, ownerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertRefused(
                setRoles(owner, List.of(role("cashier", t.headOffice())), ownerSession),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_transfer_first");
        assertRefused(
                api.post("/api/v1/users/" + owner + "/deactivate", null, ownerSession.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "cannot_deactivate_self");
        // The Owner may keep their administrator role and change their own contact.
        assertThat(patchEmail(owner, ownerSession).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode me = api.get("/api/v1/me", ownerSession.accessToken()).getBody();
        assertThat(me.get("is_owner").asBoolean()).isTrue();
        JsonNode listed = api.get("/api/v1/users?limit=200", ownerSession.accessToken())
                .getBody()
                .get("items");
        long ownersListed = 0;
        for (JsonNode u : listed) {
            if (u.get("is_owner").asBoolean()) {
                ownersListed++;
                assertThat(u.get("id").asString()).isEqualTo(owner.toString());
            }
        }
        assertThat(ownersListed).isEqualTo(1);
    }

    /** FR-IAM-18: the Owner hands ownership to an active administrator with their password; exactly one Owner remains. */
    @Test
    void theOwnerTransfersOwnershipToAnActiveAdministrator() {
        assertRefused(transfer(adminA, ownerSession, null), HttpStatus.UNPROCESSABLE_CONTENT, "password_required");
        assertRefused(
                transfer(adminA, ownerSession, "Not-The-Password-1"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        assertRefused(
                transfer(cashier, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_be_active_admin");
        UUID gone = Api.staff(t, Api.email("gone"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(gone)
                .update();
        assertRefused(
                transfer(gone, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_be_active_admin");
        TestDatabase.Fixture other = TestDatabase.tenant("owner-other", true);
        UUID stranger = Api.staff(other, Api.email("stranger"), new Role("tenant_admin", null));
        assertThat(transfer(stranger, ownerSession, Api.PASSWORD).getStatusCode())
                .as("a user of another business is not found here")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertRefused(transfer(adminB, adminSession, Api.PASSWORD), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(transfer(owner, ownerSession, Api.PASSWORD), HttpStatus.UNPROCESSABLE_CONTENT, "already_owner");
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(owners(other.tenantId())).isEmpty();

        ResponseEntity<JsonNode> done = transfer(adminA, ownerSession, Api.PASSWORD);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("is_owner").asBoolean()).isTrue();
        assertThat(owners(t.tenantId())).containsExactly(adminA);
        assertThat(audits("core.user.ownership_transferred", adminA)).isEqualTo(1);
        // The old Owner stays an administrator, now under the new Owner's rules.
        assertThat(api.get("/api/v1/users/" + owner, adminSession.accessToken())
                        .getBody()
                        .get("roles")
                        .get(0)
                        .get("role_key")
                        .asString())
                .isEqualTo("tenant_admin");
        assertRefused(invite("tenant_admin", null, ownerSession), HttpStatus.FORBIDDEN, "owner_only");
        assertThat(setRoles(owner, List.of(role("cashier", t.headOffice())), adminSession)
                        .getStatusCode())
                .as("the new Owner may demote the old one")
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-18: an Owner with two-step sign-in proves it with a fresh authenticator code, not the password. */
    @Test
    void anOwnerWithTwoStepSignInTransfersWithACode() {
        TestDatabase.Fixture f = TestDatabase.tenant("owner-totp", true);
        Api.requireTwoStepForAdmins(f);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("owner-totp");
        UUID first = Api.staff(f, email, new Role("tenant_admin", null));
        UUID next = Api.staff(f, Api.email("next"), new Role("tenant_admin", null));
        makeOwner(f.tenantId(), first);
        Session s = on.signIn(first, email, Api.PASSWORD, null);
        ResponseEntity<JsonNode> noCode = on.post(
                "/api/v1/users/" + next + "/transfer-ownership", Map.of("password", Api.PASSWORD), s.accessToken());
        assertRefused(noCode, HttpStatus.UNPROCESSABLE_CONTENT, "totp_required");
        Api.allowTotpReuse(first);
        ResponseEntity<JsonNode> done = on.post(
                "/api/v1/users/" + next + "/transfer-ownership",
                Map.of("totp_code", Api.totp(s.totpSecret(), Instant.now())),
                s.accessToken());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(owners(f.tenantId())).containsExactly(next);
    }

    /** FR-IAM-18: the operator makes an active administrator the Owner with a reason and a code, audited on both sides. */
    @Test
    void theOperatorMakesAnActiveAdministratorTheOwnerWithAReason() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        String base = "/api/v1/platform/tenants/" + t.tenantId() + "/users/";

        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> noReason = platform.post(
                base + adminB + "/make-owner",
                Map.of("totp_code", Api.totp(operator.totpSecret(), Instant.now()), "reason", "abc"),
                operator.accessToken());
        assertRefused(noReason, HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed");
        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> notAdmin = platform.post(
                base + cashier + "/make-owner",
                Map.of(
                        "totp_code",
                        Api.totp(operator.totpSecret(), Instant.now()),
                        "reason",
                        "Test: the owner lost access"),
                operator.accessToken());
        assertRefused(notAdmin, HttpStatus.UNPROCESSABLE_CONTENT, "owner_must_be_active_admin");
        assertThat(owners(t.tenantId())).containsExactly(owner);

        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> done = platform.post(
                base + adminB + "/make-owner",
                Map.of(
                        "totp_code",
                        Api.totp(operator.totpSecret(), Instant.now()),
                        "reason",
                        "Test: the owner lost access"),
                operator.accessToken());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owners(t.tenantId())).containsExactly(adminB);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT data ->> 'reason' FROM platform_audit_log
                                 WHERE action = 'platform.tenant.owner_transferred' AND tenant_id = ?
                                """)
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Test: the owner lost access");
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT actor_kind || ':' || (data -> 'after' ->> 'reason') FROM audit_log
                                 WHERE tenant_id = ? AND action = 'core.user.ownership_transferred' AND entity_id = ?
                                """)
                        .params(t.tenantId(), adminB)
                        .query(String.class)
                        .single())
                .isEqualTo("platform:Test: the owner lost access");
        JsonNode overview = platform.get(
                        "/api/v1/platform/tenants/" + t.tenantId() + "/overview", operator.accessToken())
                .getBody();
        assertThat(overview.get("admins").get(0).get("id").asString()).isEqualTo(adminB.toString());
        assertThat(overview.get("admins").get(0).get("is_owner").asBoolean()).isTrue();
        assertThat(overview.get("tenant").get("owner_name").asString()).startsWith("Test Staff admin-b");
    }

    /** ADR-062: a business with no Owner yet keeps the rules of before until the operator assigns one. */
    @Test
    void aBusinessWithNoOwnerKeepsTheRulesOfBefore() {
        TestDatabase.Fixture f = TestDatabase.tenant("ownerless", true);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("admin");
        UUID admin = Api.staff(f, email, new Role("tenant_admin", null));
        UUID second = Api.staff(f, Api.email("second"), new Role("tenant_admin", null));
        Session s = on.signIn(admin, email, Api.PASSWORD, null);
        assertThat(on.post(
                                "/api/v1/users",
                                Map.of(
                                        "full_name",
                                        "Test Invitee",
                                        "email",
                                        Api.email("invitee"),
                                        "roles",
                                        List.of(role("tenant_admin", null))),
                                s.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(on.post("/api/v1/users/" + second + "/deactivate", null, s.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertRefused(
                on.post(
                        "/api/v1/users/" + admin + "/transfer-ownership",
                        Map.of("password", Api.PASSWORD),
                        s.accessToken()),
                HttpStatus.CONFLICT,
                "no_owner");
    }

    /** ADR-062: the database holds one Owner per business and keeps the Owner an administrator, whatever the code does. */
    @Test
    void theDatabaseKeepsOneOwnerWhoStaysAnAdministrator() {
        assertThatThrownBy(() -> makeOwner(t.tenantId(), adminA)).hasStackTraceContaining("users_one_owner");
        assertThatThrownBy(() -> TestDatabase.owner().sql("""
                                UPDATE user_role_assignments SET revoked_at = now()
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(owner).update())
                .hasStackTraceContaining("user_role_assignments_owner_keeps_admin");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                        .param(owner)
                        .update())
                .hasStackTraceContaining("users_owner_is_staff");
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = false WHERE id = ?")
                .param(owner)
                .update();
        assertThatThrownBy(() -> makeOwner(t.tenantId(), cashier)).hasStackTraceContaining("users_owner_is_admin");
        // Each business has its own Owner: another business's Owner does not count against this one.
        TestDatabase.Fixture other = TestDatabase.tenant("owner-second", true);
        UUID otherAdmin = Api.staff(other, Api.email("other"), new Role("tenant_admin", null));
        makeOwner(other.tenantId(), otherAdmin);
        makeOwner(t.tenantId(), owner);
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(owners(other.tenantId())).containsExactly(otherAdmin);
    }

    /** NFR-ISO-01: the Owner of one business cannot see, change or hand ownership to the staff of another. */
    @Test
    void theOwnerRulesNeverReachAnotherBusiness() {
        TestDatabase.Fixture other = TestDatabase.tenant("owner-iso", true);
        UUID otherAdmin = Api.staff(other, Api.email("iso"), new Role("tenant_admin", null));
        makeOwner(other.tenantId(), otherAdmin);
        String path = "/api/v1/users/" + otherAdmin;
        assertThat(api.get(path, ownerSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post(path + "/deactivate", null, ownerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(setRoles(otherAdmin, List.of(role("cashier", t.headOffice())), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transfer(otherAdmin, ownerSession, Api.PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owners(other.tenantId())).containsExactly(otherAdmin);
        assertThat(owners(t.tenantId())).containsExactly(owner);
    }
}
