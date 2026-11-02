package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Resend webhook of issue #333 (ADR-115 decision 8) over HTTP: a signed bounce and a signed complaint each become one
 * platform audit row with the outbox row id and the recipient's domain only, a repeat writes nothing more, a wrong
 * signature or an old timestamp gets 401 and writes nothing, and the operator's outbox view lists them. The secret is
 * the test profile's fabricated one; addresses are fabricated.
 */
class ResendWebhookIT extends IntegrationTest {

    static final byte[] KEY = Base64.getDecoder().decode("dGVzdC1vbmx5LXJlc2VuZC13ZWJob29rLXNlY3JldA==");

    @Autowired
    TestRestTemplate http;

    static String sign(byte[] key, String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1,"
                + Base64.getEncoder()
                        .encodeToString(
                                mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    ResponseEntity<String> post(String id, long timestamp, String signature, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("svix-id", id);
        headers.set("svix-timestamp", String.valueOf(timestamp));
        headers.set("svix-signature", signature);
        return http.postForEntity("/api/v1/platform/webhooks/resend", new HttpEntity<>(body, headers), String.class);
    }

    static String event(String type, UUID outboxId, UUID tenantId) {
        return """
                {"type":"%s","created_at":"2026-11-02T10:00:00.000Z","data":{"email_id":"4ef9a417-02e9-4d39-ad75-9611e0fcc33c",
                "from":"Test Shop <no-reply@example.test>","to":["Staff01@Bounce.Example.Test"],"subject":"Your BMS password was changed",
                "bounce":{"type":"Permanent","subType":"General","message":"The address does not exist"},
                "tags":{"outbox_id":"%s","tenant_id":"%s","template":"identity_password_changed"}}}
                """.formatted(type, outboxId, tenantId);
    }

    long rows(String svixId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(svixId)
                .query(Long.class)
                .single();
    }

    @Test
    void aSignedBounceAndComplaintAreRecordedOnceWithTheDomainOnlyAndShownToTheOperator() throws Exception {
        UUID outboxId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        String bounceId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String bounce = event("email.bounced", outboxId, tenantId);

        assertThat(post(bounceId, now, sign(KEY, bounceId, now, bounce), bounce)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        // The same message again (Resend retries until it hears 2xx): nothing more is written.
        assertThat(post(bounceId, now, sign(KEY, bounceId, now, bounce), bounce)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(bounceId)).isEqualTo(1);
        var row = TestDatabase.owner()
                .sql(
                        "SELECT action, tenant_id, data::text AS data FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(bounceId)
                .query()
                .singleRow();
        assertThat(row.get("action")).isEqualTo("platform.email.bounced");
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat((String) row.get("data"))
                .contains(outboxId.toString())
                .contains("bounce.example.test")
                .contains("Permanent")
                .doesNotContainIgnoringCase("staff01");

        String complaintId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String complaint = event("email.complained", outboxId, tenantId);
        assertThat(post(complaintId, now, sign(KEY, complaintId, now, complaint), complaint)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(complaintId)).isEqualTo(1);

        // Another event type is acknowledged and not recorded.
        String deliveredId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String delivered = event("email.delivered", outboxId, tenantId);
        assertThat(post(deliveredId, now, sign(KEY, deliveredId, now, delivered), delivered)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(deliveredId)).isZero();

        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        JsonNode status =
                platform.get("/api/v1/platform/outbox", op.accessToken()).getBody();
        int found = 0;
        for (JsonNode b : status.get("bounces")) {
            if (b.get("outbox_id").asString().equals(outboxId.toString())) {
                found++;
                assertThat(b.get("recipient_domain").asString()).isEqualTo("bounce.example.test");
                assertThat(b.get("tenant_id").asString()).isEqualTo(tenantId.toString());
            }
        }
        assertThat(found).isEqualTo(2);
        assertThat(status.toString()).doesNotContainIgnoringCase("staff01@");
    }

    @Test
    void aWrongSignatureOrAnOldTimestampGets401AndWritesNothing() throws Exception {
        UUID outboxId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        String body = event("email.bounced", outboxId, UUID.randomUUID());

        String forged = "msg_" + UUID.randomUUID().toString().replace("-", "");
        byte[] other = "another-secret-entirely".getBytes(StandardCharsets.UTF_8);
        assertThat(post(forged, now, sign(other, forged, now, body), body)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(forged)).isZero();

        // Signed with the right key, but over another body.
        String tampered = "msg_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(post(tampered, now, sign(KEY, tampered, now, body), body.replace("Permanent", "Transient"))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(tampered)).isZero();

        // A replay: correctly signed, but ten minutes old.
        String old = "msg_" + UUID.randomUUID().toString().replace("-", "");
        long then = now - 600;
        assertThat(post(old, then, sign(KEY, old, then, body), body)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(old)).isZero();
    }
}
