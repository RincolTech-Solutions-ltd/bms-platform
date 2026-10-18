package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The real second-factor step-up of the payment account (issue #233, review of #308): signed in as real tenant admins with real
 * authenticators. Saving needs a current code of the signed-in admin's own authenticator, each code once; a manager without the
 * tenant admin's permission cannot save at all; the other admin is told by email; and only the audit row, never a key or secret,
 * records it. Every name and value is invented.
 */
@TestPropertySource(
        properties = {
            "bms.pesapal.provider=fake",
            "bms.pesapal.callback-base-url=https://callbacks.example.test",
            "db-scheduler.enabled=false",
            "bms.payments.poller-enabled=false"
        })
class MerchantAccountStepUpIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    static final String KEY = "step-up-it-key-0000001";
    static final String SECRET = "step-up-it-secret-0000001";

    Map<String, Object> body(String key, String code) {
        return Map.of("consumer_key", key, "consumer_secret", SECRET, "totp_code", code);
    }

    @Test
    void aRealAdminNeedsAFreshCodeEachTimeAndTheOtherAdminIsToldButAManagerCannotSave() {
        TestDatabase.Fixture t = TestDatabase.tenant("pay-realmfa", true);
        String emailA = "admin-a-" + UUID.randomUUID() + "@example.test";
        String emailB = "admin-b-" + UUID.randomUUID() + "@example.test";
        String emailM = "manager-" + UUID.randomUUID() + "@example.test";
        UUID adminA = Api.staff(t, emailA, new Role("tenant_admin", null));
        Api.staff(t, emailB, new Role("tenant_admin", null));
        UUID manager = Api.staff(t, emailM, new Role("branch_manager", null));
        Api api = Api.tenant(http, t.slug());
        Session a = api.signIn(adminA, emailA, Api.PASSWORD, null);
        String path = "/api/v1/payments/merchant-account";

        // No code, a wrong code: nothing is saved.
        assertThat(api.call(
                                HttpMethod.PUT,
                                path,
                                Map.of("consumer_key", KEY, "consumer_secret", SECRET),
                                a.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        ResponseEntity<JsonNode> wrong = api.call(HttpMethod.PUT, path, body(KEY, "000000"), a.accessToken());
        assertThat(wrong.getStatusCode().value()).isEqualTo(422);
        assertThat(count(t)).isZero();

        // The right code saves; the same code a second time is refused (each code is used once).
        Api.allowTotpReuse(adminA);
        String code = Api.totp(a.totpSecret(), Instant.now());
        ResponseEntity<JsonNode> saved = api.call(HttpMethod.PUT, path, body(KEY, code), a.accessToken());
        assertThat(saved.getStatusCode().value()).as("%s", saved.getBody()).isEqualTo(200);
        assertThat(saved.getBody().toString()).doesNotContain(SECRET).doesNotContain(KEY);
        assertThat(count(t)).isEqualTo(1);
        ResponseEntity<JsonNode> replay = api.call(HttpMethod.PUT, path, body(KEY, code), a.accessToken());
        assertThat(replay.getStatusCode().value()).isEqualTo(422);

        // The other admin is told by email (not the one who made the change); the notice carries no key or secret.
        List<String> notices = TestDatabase.owner()
                .sql(
                        "SELECT recipient || ' ' || params::text FROM notification_outbox WHERE tenant_id = ? AND template_key = 'payments.account_changed'")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0))
                .contains(emailB)
                .doesNotContain(emailA)
                .doesNotContain(KEY)
                .doesNotContain(SECRET);

        // A branch manager holds core.settings.read but not the account permission: refused before any code is looked
        // at.
        Session m = api.signIn(manager, emailM, Api.PASSWORD, null);
        ResponseEntity<JsonNode> refused =
                api.call(HttpMethod.PUT, path, body("manager-key-00000001", "123456"), m.accessToken());
        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        // ...and may read the status but is not given the key's last four characters.
        JsonNode view = api.call(HttpMethod.GET, path, null, m.accessToken()).getBody();
        assertThat(view.get("key_hint").isNull()).isTrue();
        JsonNode adminView =
                api.call(HttpMethod.GET, path, null, a.accessToken()).getBody();
        assertThat(adminView.get("key_hint").asString()).isEqualTo("0001");
    }

    long count(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }
}
