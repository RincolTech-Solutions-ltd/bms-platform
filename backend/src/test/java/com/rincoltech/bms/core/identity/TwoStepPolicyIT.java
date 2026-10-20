package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The per-tenant two-step sign-in policy (FR-IAM-06, FR-IAM-16, issue #348, ADR-046): optional by default, required for everyone or
 * for chosen roles by a tenant administrator, with a grace period, and the forgotten-password rules of ADR-042 intact. Fabricated
 * tenants and people.
 */
class TwoStepPolicyIT extends IntegrationTest {

    private static final AtomicInteger ADDRESS = new AtomicInteger();

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session admin;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("twostep", true);
        api = Api.tenant(http, t.slug());
        admin = signedIn("tenant_admin");
    }

    String email(String prefix, String role) {
        String email = Api.email(prefix);
        Api.staff(t, email, new Role(role, role.equals("tenant_admin") ? null : t.headOffice()));
        return email;
    }

    Session signedIn(String role) {
        String email = Api.email(role);
        UUID id = Api.staff(t, email, new Role(role, role.equals("tenant_admin") ? null : t.headOffice()));
        return api.signIn(id, email, Api.PASSWORD, null);
    }

    String status(String email) {
        ResponseEntity<JsonNode> login = api.login(email, Api.PASSWORD);
        assertThat(login.getStatusCode()).as("%s", login.getBody()).isEqualTo(HttpStatus.OK);
        return login.getBody().get("status").asString();
    }

    ResponseEntity<JsonNode> patch(Map<String, Object> body) {
        String etag =
                api.get("/api/v1/settings", admin.accessToken()).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/api/v1/settings", body, admin.accessToken(), Map.of("If-Match", etag));
    }

    JsonNode settings() {
        return api.get("/api/v1/settings", admin.accessToken()).getBody();
    }

    JsonNode me(String token) {
        return api.get("/api/v1/me", token).getBody();
    }

    void deadlineWas(Instant at) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings, '{mfa_enforce_from}',"
                        + " to_jsonb(?::text)) WHERE tenant_id = ?")
                .params(at.toString(), t.tenantId())
                .update();
    }

    String mailboxLink(String email) {
        ResponseEntity<JsonNode> asked = api.call(
                HttpMethod.POST,
                "/api/v1/auth/staff/password/forgot",
                Map.of("login", email),
                null,
                Map.of("X-Forwarded-For", "203.0.113." + (ADDRESS.incrementAndGet() % 250 + 1)));
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
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

    /** FR-IAM-06, issue #348: nothing is required by default, tenant administrators included, on a database made by the migrations. */
    @Test
    void twoStepSignInIsOptionalByDefaultEvenForATenantAdmin() {
        assertThat(TestDatabase.owner()
                        .sql("SELECT mfa_required FROM roles WHERE key = 'tenant_admin'")
                        .query(Boolean.class)
                        .single())
                .as("V91 clears the seed's flag")
                .isFalse();
        JsonNode current = settings();
        assertThat(current.get("require_mfa_all_staff").asBoolean()).isFalse();
        assertThat(current.get("require_mfa_roles")).isEmpty();
        assertThat(current.get("mfa_grace_days").isNull()).isTrue();
        assertThat(current.get("mfa_enforce_from").isNull()).isTrue();

        // The administrator of setUp has a session without ever enrolling, and signs in again the same way.
        assertThat(admin.totpSecret()).isNull();
        JsonNode profile = me(admin.accessToken());
        assertThat(profile.get("mfa_enabled").asBoolean()).isFalse();
        assertThat(profile.get("mfa_required").asBoolean()).isFalse();
        assertThat(profile.get("mfa_due_by").isNull()).isTrue();
        assertThat(profile.get("two_step_policy").asString()).isEqualTo("off");
        assertThat(status(email("cashier", "cashier"))).isEqualTo("signed_in");
    }

    /** FR-IAM-16: for everyone with a grace period, nobody is locked out until the date, then sign-in goes to enrolment. */
    @Test
    void requiringItForEveryoneStartsAGracePeriodAndThenForcesEnrolment() {
        String cashier = Api.email("cashier");
        UUID cashierId = Api.staff(t, cashier, new Role("cashier", t.headOffice()));

        ResponseEntity<JsonNode> on = patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14));

        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("mfa_grace_days").asInt()).isEqualTo(14);
        Instant deadline = Instant.parse(on.getBody().get("mfa_enforce_from").asString());
        Instant expected = Instant.now().plus(Duration.ofDays(14));
        assertThat(deadline).isBetween(expected.minusSeconds(120), expected.plusSeconds(120));
        // Inside the grace period a covered user without a factor signs in as before and is told the date.
        Session inGrace = api.signIn(cashierId, cashier, Api.PASSWORD, null);
        JsonNode reminder = me(inGrace.accessToken());
        assertThat(reminder.get("mfa_required").asBoolean()).isFalse();
        assertThat(Instant.parse(reminder.get("mfa_due_by").asString())).isEqualTo(deadline);
        assertThat(reminder.get("two_step_policy").asString()).isEqualTo("everyone");

        deadlineWas(Instant.now().minusSeconds(60));

        assertThat(status(cashier)).isEqualTo("mfa_enrolment_required");
        JsonNode after = me(inGrace.accessToken());
        assertThat(after.get("mfa_required").asBoolean()).isTrue();
        assertThat(after.get("mfa_due_by").isNull()).isTrue();
    }

    /** FR-IAM-16: chosen roles only, at once with no grace, and only staff roles may be chosen. */
    @Test
    void requiringItForChosenRolesCoversOnlyTheirHolders() {
        String cashier = email("cashier", "cashier");
        String officer = email("officer", "loan_officer");

        ResponseEntity<JsonNode> on = patch(Map.of("require_mfa_roles", List.of("cashier", "cashier")));

        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("require_mfa_roles")).hasSize(1);
        assertThat(on.getBody().get("mfa_enforce_from").isNull())
                .as("no grace means at once")
                .isTrue();
        assertThat(status(cashier)).isEqualTo("mfa_enrolment_required");
        assertThat(status(officer)).isEqualTo("signed_in");
        assertThat(me(admin.accessToken()).get("two_step_policy").asString()).isEqualTo("roles");

        for (List<String> bad : List.of(List.of("member"), List.of("no_such_role"), List.of("Cashier"))) {
            ResponseEntity<JsonNode> refused = patch(Map.of("require_mfa_roles", bad));
            assertThat(refused.getStatusCode()).as("%s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("validation_failed");
        }
        assertThat(patch(Map.of("mfa_grace_days", 91)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // An empty list clears the choice.
        assertThat(patch(Map.of("require_mfa_roles", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(cashier)).isEqualTo("signed_in");
    }

    /** ADR-046 decision 3: one deadline; widening restarts it for everyone covered, narrowing and other changes leave it alone. */
    @Test
    void widenAndNarrowAndOffMoveTheDeadlineAsTheDecisionSays() {
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"), "mfa_grace_days", 7))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Instant first = Instant.parse(settings().get("mfa_enforce_from").asString());
        Instant settled = Instant.now().plus(Duration.ofDays(3));
        deadlineWas(settled);

        // Narrowing, an unrelated change and the same roles again keep the deadline.
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patch(Map.of("receipt_footer", "Thank you")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(settled);

        // Widening to another role starts the stored grace again, from now.
        assertThat(patch(Map.of("require_mfa_roles", List.of("accountant", "cashier")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Instant widened = Instant.parse(settings().get("mfa_enforce_from").asString());
        assertThat(widened).isAfter(settled).isAfterOrEqualTo(first);

        // Narrowing back to one role leaves the new deadline; turning everything off clears it.
        assertThat(patch(Map.of("require_mfa_roles", List.of("accountant"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(widened);
        assertThat(patch(Map.of("require_mfa_roles", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(settings().get("mfa_enforce_from").isNull()).isTrue();
        assertThat(me(admin.accessToken()).get("two_step_policy").asString()).isEqualTo("off");
    }

    /** The change is audited with before and after like every settings change, and only a settings manager may make it. */
    @Test
    void theChangeIsAuditedAndOnlyASettingsManagerMakesIt() {
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"), "mfa_grace_days", 5))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String data = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.settings.updated'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data)
                .contains("require_mfa_roles")
                .contains("mfa_grace_days")
                .contains("mfa_enforce_from");

        Session auditor = signedIn("auditor");
        ResponseEntity<JsonNode> refused = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("require_mfa_roles", List.of()),
                auditor.accessToken(),
                Map.of("If-Match", "\"1\""));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** ADR-042 decision 7 with ADR-046 decision 4: the mailbox reset works inside the grace period and is refused once enforced. */
    @Test
    void theMailboxResetWorksDuringTheGracePeriodAndIsRefusedOnceEnforced() {
        String cashier = email("cashier", "cashier");
        assertThat(patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 10))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(mailboxLink(cashier)).as("inside the grace period").isNotNull();

        deadlineWas(Instant.now().minusSeconds(60));
        String other = email("cashier2", "cashier");

        assertThat(mailboxLink(other))
                .as("once enforced, a user with no factor is helped by an administrator")
                .isNull();
    }

    /** ADR-042 decision 9(c): the admin reset of a sign-in still takes the password of an admin with no authenticator. */
    @Test
    void anAdminWithNoSecondFactorStillResetsAColleaguesSignInWithTheirPassword() {
        String cashier = Api.email("cashier");
        UUID target = Api.staff(t, cashier, new Role("cashier", t.headOffice()));

        ResponseEntity<JsonNode> without =
                api.post("/api/v1/users/" + target + "/reset-sign-in", Map.of(), admin.accessToken());
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(without.getBody().get("code").asString()).isEqualTo("password_required");

        ResponseEntity<JsonNode> issued = api.post(
                "/api/v1/users/" + target + "/reset-sign-in", Map.of("password", Api.PASSWORD), admin.accessToken());
        assertThat(issued.getStatusCode()).as("%s", issued.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody().get("url").asString()).contains("/reset-password#token=");
    }
}
