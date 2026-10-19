package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The forgotten staff password and the admin reset of sign-in through HTTP (FR-IAM-15, FR-IAM-03, FR-IAM-05, FR-IAM-07,
 * FR-IAM-08, FR-AUD-03, NFR-ISO-01; ADR-042). All users, addresses and passwords are fabricated.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIT extends IntegrationTest {

    static final String REQUEST = "/api/v1/auth/staff/password/forgot";
    static final String COMPLETE = "/api/v1/auth/staff/password/reset";
    static final String NEW_PASSWORD = "Another-Fabricated-Pass-27";
    /** Every request has its own documentation address (RFC 5737), so the per-address limit never crosses tests. */
    static final AtomicInteger ADDRESS = new AtomicInteger(10);

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID admin;
    Session adminSession;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("reset", true);
        api = Api.tenant(http, t.slug());
        String adminEmail = Api.email("admin");
        admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    static Map<String, String> from(String address) {
        return Map.of("X-Forwarded-For", address);
    }

    static String freshAddress() {
        return "198.51.100." + (ADDRESS.incrementAndGet() % 250 + 1);
    }

    ResponseEntity<JsonNode> request(Api on, String login, String address) {
        return on.call(HttpMethod.POST, REQUEST, Map.of("login", login), null, from(address));
    }

    ResponseEntity<JsonNode> request(String login) {
        return request(api, login, freshAddress());
    }

    ResponseEntity<JsonNode> complete(Api on, String token, String password) {
        return on.call(
                HttpMethod.POST, COMPLETE, Map.of("token", token, "password", password), null, from(freshAddress()));
    }

    ResponseEntity<JsonNode> complete(String token, String password) {
        return complete(api, token, password);
    }

    UUID user(String email, String... roleKeys) {
        Role[] roles = roleKeys.length == 0
                ? new Role[] {new Role("cashier", t.headOffice())}
                : java.util.Arrays.stream(roleKeys)
                        .map(k -> new Role(k, k.equals("tenant_admin") ? null : t.headOffice()))
                        .toArray(Role[]::new);
        return Api.staff(t, email, roles);
    }

    /** The newest reset email of an address, read as the owner. */
    String emailedLink(String email) {
        return TestDatabase.owner()
                .sql("""
                        SELECT params->>'link' FROM notification_outbox
                         WHERE template_key = 'identity.password_reset' AND recipient = ? ORDER BY created_at DESC LIMIT 1
                        """)
                .param(email)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    long emails(String template, String email) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE template_key = ? AND recipient = ?")
                .params(template, email)
                .query(Long.class)
                .single();
    }

    static String tokenOf(String url) {
        return url.substring(url.indexOf("#token=") + 7);
    }

    long audits(String action, UUID entityId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id IS NOT DISTINCT FROM ?")
                .params(t.tenantId(), action, entityId)
                .query(Long.class)
                .single();
    }

    long auditsMentioning(String text) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND data::text LIKE ?")
                .params(t.tenantId(), "%" + text + "%")
                .query(Long.class)
                .single();
    }

    long openLinks(UUID userId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM user_password_resets WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL")
                .param(userId)
                .query(Long.class)
                .single();
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }

    /** FR-IAM-15: the whole self-service path, with what is stored and what is sent. */
    @Test
    void aUserWhoForgotThePasswordGetsALinkSetsANewPasswordAndEverySessionEnds(CapturedOutput output) throws Exception {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session before = api.signIn(userId, email, Api.PASSWORD, null);
        Session beforeToo = api.signIn(userId, email, Api.PASSWORD, null);

        // The Host header and X-Forwarded-Host a client sends never reach the link.
        ResponseEntity<JsonNode> asked = api.call(
                HttpMethod.POST,
                REQUEST,
                Map.of("login", email),
                null,
                Map.of("X-Forwarded-For", freshAddress(), "X-Forwarded-Host", "evil.example"));
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String url = emailedLink(email);
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/reset-password#token=");
        String token = tokenOf(url);
        assertThat(emails("identity.password_reset", email)).isEqualTo(1);
        // Stored as a hash only, one hour, from the self-service path.
        var row = TestDatabase.owner()
                .sql(
                        "SELECT token_hash, origin, requested_by, expires_at, created_at FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(row.get("token_hash")).isEqualTo(sha256(token));
        assertThat(row.get("origin")).isEqualTo("self");
        assertThat(row.get("requested_by")).isNull();
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
                .isBetween(Instant.now().plusSeconds(59 * 60), Instant.now().plusSeconds(61 * 60));
        assertThat(audits("core.password_reset.requested", userId)).isEqualTo(1);
        // The sessions still work until the link is used.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        assertThat(done.getHeaders().getFirst("Set-Cookie")).startsWith("__Host-bms_rt=");
        String fresh = done.getBody().get("access_token").asString();
        assertThat(api.get("/api/v1/me", fresh).getBody().get("user_id").asString())
                .isEqualTo(userId.toString());

        // Every earlier session is gone, with its own reason, and the refresh tokens are dead too.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/me", beforeToo.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.refresh(before.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_reason = 'password_reset'")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        // The old password is dead, the new one signs in.
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Single use.
        ResponseEntity<JsonNode> again = complete(token, "Yet-Another-Fabricated-Pass-3");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Audited without the token; the owner of the mailbox is told, without a link.
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(auditsMentioning(token)).isZero();
        assertThat(emails("identity.password_changed", email)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT params::text FROM notification_outbox WHERE template_key = 'identity.password_changed' AND recipient = ?")
                        .param(email)
                        .query(String.class)
                        .single())
                .doesNotContain("token");
        // Neither the token nor a password reaches the log.
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(NEW_PASSWORD);
    }

    /** FR-IAM-04, FR-IAM-15: the same answer for an account, no account, a deactivated one, an invited one and a phone. */
    @Test
    void theAnswerIsTheSameWhetherOrNotAnAccountMatches() {
        String known = Api.email("known");
        user(known);
        String deactivated = Api.email("gone");
        UUID gone = user(deactivated);
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(gone)
                .update();
        String invited = Api.email("invited");
        UUID waiting = user(invited);
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'invited' WHERE id = ?")
                .param(waiting)
                .update();
        String phoneOnly = Api.email("phone");
        UUID phoneUser = user(phoneOnly);
        TestDatabase.owner()
                .sql("UPDATE users SET email = NULL, phone_e164 = '+256700000401' WHERE id = ?")
                .param(phoneUser)
                .update();

        List<String> logins = List.of(
                known,
                Api.email("nobody"),
                deactivated,
                invited,
                "+256700000401",
                "0700000402",
                "not an address or a number");
        JsonNode first = null;
        for (String login : logins) {
            long started = System.nanoTime();
            ResponseEntity<JsonNode> response = request(login);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(response.getStatusCode()).as(login).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getFirst("Set-Cookie")).as(login).isNull();
            // Nothing in the time of the answer says what happened inside: a floor, not a measurement of the work.
            assertThat(millis).as(login).isGreaterThanOrEqualTo(350);
            if (first == null) {
                first = response.getBody();
            }
            assertThat(response.getBody()).as(login).isEqualTo(first);
        }
        // Only the active account with an email got a link.
        assertThat(emailedLink(known)).isNotNull();
        for (String silent : List.of(deactivated, invited, phoneOnly)) {
            assertThat(emailedLink(silent)).as(silent).isNull();
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The refusals of known accounts are visible to the operator, not to the caller.
        assertThat(audits("core.password_reset.refused", gone)).isEqualTo(1);
        assertThat(audits("core.password_reset.refused", waiting)).isEqualTo(1);
        assertThat(audits("core.password_reset.refused", phoneUser)).isEqualTo(1);
    }

    /** A request with no login at all is a plain validation error, the same for everyone. */
    @Test
    void aBlankLoginIsRefusedWithoutLookingAnyoneUp() {
        ResponseEntity<JsonNode> response = request("");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-IAM-15: a newer link revokes the older ones. */
    @Test
    void aNewRequestRevokesTheOlderLink() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String first = tokenOf(emailedLink(email));
        // Created a moment apart so "newest" is well defined.
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET created_at = created_at - interval '1 minute' WHERE recipient = ?")
                .param(email)
                .update();
        request(email);
        String second = tokenOf(emailedLink(email));
        assertThat(second).isNotEqualTo(first);
        assertThat(openLinks(userId)).isEqualTo(1);

        ResponseEntity<JsonNode> stale = complete(first, NEW_PASSWORD);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(complete(second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-15: a link lives one hour. */
    @Test
    void anExpiredLinkIsRefusedAndChangesNothing() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        TestDatabase.owner()
                .sql("UPDATE user_password_resets SET expires_at = now() - interval '1 second' WHERE user_id = ?")
                .param(userId)
                .update();

        ResponseEntity<JsonNode> late = complete(token, NEW_PASSWORD);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("reset_link_expired");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits("core.password_reset.used", userId)).isZero();
    }

    /** FR-IAM-15: of several uses of one link at the same moment, one wins. */
    @Test
    void aLinkUsedManyTimesAtOnceWorksOnce() throws Exception {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));

        List<org.springframework.http.HttpStatusCode> statuses = Api.race(4, () -> complete(token, NEW_PASSWORD));

        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.UNPROCESSABLE_CONTENT)).isEqualTo(3);
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(emails("identity.password_changed", email)).isEqualTo(1);
    }

    /** FR-IAM-04: the link sets a password under the same rules as an invitation, and a refusal keeps the link. */
    @Test
    void aWeakPasswordIsRefusedAndTheLinkStillWorks() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));

        for (String weak : List.of("short", "password123", email)) {
            ResponseEntity<JsonNode> refused = complete(token, weak);
            assertThat(refused.getStatusCode()).as(weak).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).as(weak).isEqualTo("weak_password");
        }
        assertThat(openLinks(userId)).isEqualTo(1);
        assertThat(complete(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-06, FR-IAM-15: a reset never disables or skips the second factor. */
    @Test
    void aResetKeepsTheSecondFactor() {
        // The tenant admin of setUp enrolled TOTP at the first sign-in.
        String adminEmail = TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(admin)
                .query(String.class)
                .single();
        assertThat(adminSession.totpSecret()).isNotNull();
        request(adminEmail);
        String token = tokenOf(emailedLink(adminEmail));

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("mfa_required");
        assertThat(done.getBody().path("access_token").isMissingNode()
                        || done.getBody().path("access_token").isNull())
                .isTrue();
        assertThat(done.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT mfa_enabled AND EXISTS (SELECT 1 FROM user_credentials c WHERE c.user_id = users.id"
                                        + " AND c.totp_secret_enc IS NOT NULL) FROM users WHERE id = ?")
                        .param(admin)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ? AND used_at IS NULL")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(10);

        // A wrong code does not sign in; the right one does.
        String mfaToken = done.getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> wrong =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", "000000"), null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Api.allowTotpReuse(admin);
        ResponseEntity<JsonNode> verified = api.post(
                "/api/v1/auth/staff/mfa/verify",
                Map.of("mfa_token", mfaToken, "code", Api.totp(adminSession.totpSecret(), Instant.now())),
                null);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().get("status").asString()).isEqualTo("signed_in");
        // And the old session of the admin ended with the reset.
        assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** FR-IAM-05: a reset does not clear the lock, so it cannot be used to keep guessing a code. */
    @Test
    void aLockedAccountSetsThePasswordButIsToldToSignInLater() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        TestDatabase.owner()
                .sql(
                        "UPDATE users SET failed_login_count = 5, locked_until = now() + interval '10 minutes' WHERE id = ?")
                .param(userId)
                .update();

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("sign_in_required");
        assertThat(done.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(TestDatabase.owner()
                        .sql("SELECT locked_until > now() FROM users WHERE id = ?")
                        .param(userId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        // The new password is stored: once the lock lapses it signs in.
        TestDatabase.owner()
                .sql("UPDATE users SET locked_until = NULL, failed_login_count = 0 WHERE id = ?")
                .param(userId)
                .update();
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-08: a user deactivated after asking cannot use the link, and deactivation revokes it. */
    @Test
    void aDeactivatedUserCannotUseAnOpenLink() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        ResponseEntity<JsonNode> off =
                api.post("/api/v1/users/" + userId + "/deactivate", null, adminSession.accessToken());
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openLinks(userId)).isZero();

        ResponseEntity<JsonNode> refused = complete(token, NEW_PASSWORD);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // And the admin cannot issue one for the deactivated user, nor for one still invited.
        ResponseEntity<JsonNode> issue =
                api.post("/api/v1/users/" + userId + "/reset-sign-in", null, adminSession.accessToken());
        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(issue.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
    }

    /** FR-IAM-15: three links an hour for one account; the rest are answered and ignored. */
    @Test
    void anAccountCannotBeFloodedWithLinks() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        for (int i = 0; i < 5; i++) {
            assertThat(request(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(emails("identity.password_reset", email)).isEqualTo(3);
        assertThat(audits("core.password_reset.refused", userId)).isEqualTo(2);
        assertThat(openLinks(userId)).isEqualTo(1);
    }

    /** NFR-SEC-05 style bounds: an address that asks too often is answered 429, whatever it asks for. */
    @Test
    void anAddressThatAsksTooOftenIsLimited() {
        String address = freshAddress();
        String email = Api.email("cashier");
        user(email);
        for (int i = 0; i < 20; i++) {
            assertThat(request(api, Api.email("nobody"), address).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<JsonNode> limited = request(api, email, address);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asString()).isEqualTo("rate_limited");
        assertThat(emailedLink(email)).isNull();
    }

    /** Guesses at the link are limited per address too. */
    @Test
    void anAddressThatGuessesTokensIsLimited() {
        String address = freshAddress();
        for (int i = 0; i < 30; i++) {
            ResponseEntity<JsonNode> response = api.call(
                    HttpMethod.POST,
                    COMPLETE,
                    Map.of("token", "guess-" + i, "password", NEW_PASSWORD),
                    null,
                    from(address));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        ResponseEntity<JsonNode> limited = api.call(
                HttpMethod.POST, COMPLETE, Map.of("token", "guess-31", "password", NEW_PASSWORD), null, from(address));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** NFR-ISO-01: a link and a login belong to one tenant. */
    @Test
    void aLinkOfOneTenantDoesNothingInAnother() {
        TestDatabase.Fixture other = TestDatabase.tenant("reset-b", true);
        Api otherApi = Api.tenant(http, other.slug());
        String email = Api.email("cashier");
        UUID userId = user(email);
        // The same address exists in the other tenant, with its own password.
        UUID twin = Api.staff(other, email, new Role("cashier", other.headOffice()));

        request(email);
        String token = tokenOf(emailedLink(email));
        // Used on the other tenant's host the link is simply not found.
        ResponseEntity<JsonNode> wrongHost = complete(otherApi, token, NEW_PASSWORD);
        assertThat(wrongHost.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(otherApi.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(openLinks(userId)).isEqualTo(1);

        // Asking on the other tenant's host reaches the twin, never this tenant's user.
        request(otherApi, email, freshAddress());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(twin)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(openLinks(userId)).isEqualTo(1);
        assertThat(complete(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The twin's password is untouched.
        assertThat(otherApi.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // An admin of the other tenant cannot issue a link for this tenant's user.
        String otherAdminEmail = Api.email("admin");
        UUID otherAdmin = Api.staff(other, otherAdminEmail, new Role("tenant_admin", null));
        Session otherSession = otherApi.signIn(otherAdmin, otherAdminEmail, Api.PASSWORD, null);
        assertThat(otherApi.post("/api/v1/users/" + userId + "/reset-sign-in", null, otherSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FR-IAM-15: a tenant admin issues a link, sees it once, and the user uses it. */
    @Test
    void anAdminResetShowsTheLinkOnceAndItWorksOnce() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session before = api.signIn(userId, email, Api.PASSWORD, null);

        ResponseEntity<JsonNode> issued =
                api.post("/api/v1/users/" + userId + "/reset-sign-in", null, adminSession.accessToken());
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = issued.getBody().get("url").asString();
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/reset-password#token=");
        assertThat(Instant.parse(issued.getBody().get("expires_at").asString()))
                .isBetween(Instant.now().plusSeconds(59 * 60), Instant.now().plusSeconds(61 * 60));
        String token = tokenOf(url);

        // Audited as the admin's act, without the token; not emailed.
        assertThat(audits("core.password_reset.requested", userId)).isEqualTo(1);
        assertThat(audits("core.password_reset.link_revealed", userId)).isEqualTo(1);
        assertThat(auditsMentioning(token)).isZero();
        assertThat(emails("identity.password_reset", email)).isZero();
        var row = TestDatabase.owner()
                .sql("SELECT origin, requested_by FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(row.get("origin")).isEqualTo("admin");
        assertThat(row.get("requested_by")).isEqualTo(admin);
        // Issuing does not sign anyone out: that happens when the link is used.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second link replaces the first.
        ResponseEntity<JsonNode> second =
                api.post("/api/v1/users/" + userId + "/reset-sign-in", null, adminSession.accessToken());
        String secondToken = tokenOf(second.getBody().get("url").asString());
        assertThat(complete(token, NEW_PASSWORD).getBody().get("code").asString())
                .isEqualTo("reset_link_invalid");
        ResponseEntity<JsonNode> done = complete(secondToken, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(complete(secondToken, NEW_PASSWORD).getBody().get("code").asString())
                .isEqualTo("reset_link_invalid");
    }

    /** FR-IAM-03, FR-IAM-01: who may issue a link, and for whom. */
    @Test
    void onlyAHolderOfUsersManageMayIssueALinkForAnActiveUser() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session cashier = api.signIn(userId, email, Api.PASSWORD, null);
        String path = "/api/v1/users/" + userId + "/reset-sign-in";

        assertThat(api.post(path, null, cashier.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post(path, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/v1/users/" + UUID.randomUUID() + "/reset-sign-in", null, adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A user who has not accepted the invitation keeps the invitation path.
        UUID waiting = user(Api.email("waiting"));
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'invited' WHERE id = ?")
                .param(waiting)
                .update();
        ResponseEntity<JsonNode> invited =
                api.post("/api/v1/users/" + waiting + "/reset-sign-in", null, adminSession.accessToken());
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invited.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(waiting)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** ADR-017: an admin whose permission covers some branches resets only users inside them. */
    @Test
    void aBranchScopedAdminResetsOnlyUsersInTheirBranches() {
        UUID inBranch = user(Api.email("branch2"));
        TestDatabase.owner()
                .sql("UPDATE user_role_assignments SET branch_id = ? WHERE user_id = ?")
                .params(t.secondBranch(), inBranch)
                .update();
        UUID headOfficeUser = user(Api.email("hq"));
        UUID everywhere = user(Api.email("everywhere"), "tenant_admin");
        Map<String, String> scoped = Map.of(
                "X-Dev-User-Id", UUID.randomUUID().toString(),
                "X-Dev-Permissions", "core.users.manage",
                "X-Dev-Branch-Ids", t.secondBranch().toString());

        assertThat(api.call(HttpMethod.POST, "/api/v1/users/" + inBranch + "/reset-sign-in", null, null, scoped)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.call(HttpMethod.POST, "/api/v1/users/" + headOfficeUser + "/reset-sign-in", null, null, scoped)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.call(HttpMethod.POST, "/api/v1/users/" + everywhere + "/reset-sign-in", null, null, scoped)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(openLinks(headOfficeUser) + openLinks(everywhere)).isZero();
    }
}
