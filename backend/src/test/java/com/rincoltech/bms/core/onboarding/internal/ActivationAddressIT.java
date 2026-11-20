package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.platform.TenantProvisioning;
import com.rincoltech.bms.core.referrals.Referrals;
import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activate and the business's address on Cloudflare (issue #155, ADR-131). Without the Cloudflare settings, as in every test
 * and in production, Activate answers as before: no address setup, none offered, the retry route 404 and nothing audited.
 * With them, against a fake Cloudflare that fails, the business is still activated and the operator reads why; once
 * Cloudflare answers, "Set up the address again" sets it up, and each attempt is one audit row without the token. The test
 * builds its own service with the address setup pointed at the fake, so the shared context stays without the settings.
 */
class ActivationAddressIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Outbox outbox;

    @Autowired
    PlatformAuditLog platformAudit;

    @Autowired
    PlatformHost hosts;

    @Autowired
    TenantProvisioning provisioning;

    @Autowired
    List<ModuleManifest> manifests;

    @Autowired
    Referrals referrals;

    @Autowired
    ShortNames shortNames;

    @Autowired
    CloudflareAddress address;

    @Autowired
    HostnameCheck hostnameCheck;

    @Autowired
    JsonMapper jsonMapper;

    Api platform;
    Session operator;
    UUID operatorId;
    String ip;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("address-operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        ip = OnboardingIT.freshAddress();
    }

    /** Applies, confirms the email and verifies: an application ready for Activate. */
    UUID verified(String email) {
        Map<String, Object> form = Map.of(
                "business_name",
                "Test Address Shop " + email,
                "contact_name",
                "Test Applicant 02",
                "short_name",
                OnboardingIT.shortName(),
                "contact_email",
                email,
                "contact_phone",
                OnboardingIT.phone(),
                "country",
                "UG",
                "modules",
                List.of("retail"),
                "term",
                "monthly",
                "way_in",
                "trial");
        ResponseEntity<JsonNode> applied = platform.call(
                HttpMethod.POST, "/api/v1/platform/sign-up/applications", form, null, Map.of("X-Forwarded-For", ip));
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String params =
                TestDatabase.owner().sql("""
                        SELECT params::text FROM notification_outbox WHERE template_key = 'onboarding.verify_email'
                           AND recipient = ? ORDER BY created_at DESC, id DESC LIMIT 1
                        """).param(email).query(String.class).single();
        String link = mapper.readTree(params).path("link").asString();
        ResponseEntity<JsonNode> confirmed = platform.call(
                HttpMethod.POST,
                "/api/v1/platform/sign-up/verify",
                Map.of("token", OnboardingIT.tokenOf(link)),
                null,
                Map.of("X-Forwarded-For", ip));
        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        UUID id = TestDatabase.owner()
                .sql("SELECT id FROM onboarding_applications WHERE contact_email = ? ORDER BY created_at DESC LIMIT 1")
                .param(email)
                .query(UUID.class)
                .single();
        ResponseEntity<JsonNode> v =
                platform.post("/api/v1/platform/applications/" + id + "/verify", Map.of(), operator.accessToken());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        return id;
    }

    static ApplicationsController.ActivateRequest request(String slug) {
        return new ApplicationsController.ActivateRequest(
                List.of("retail"), "monthly", "trial", null, slug, null, null);
    }

    long addressAudits(UUID tenantId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.tenant.address_setup' AND tenant_id = ?")
                .param(tenantId)
                .query(Long.class)
                .single();
    }

    @Test
    void withoutTheSettingsActivateIsAsBeforeAndNothingIsOffered() {
        assertThat(address.enabled()).isFalse();
        UUID id = verified(Api.email("address-off"));

        ResponseEntity<JsonNode> done = platform.post(
                "/api/v1/platform/applications/" + id + "/activate",
                Map.of("modules", List.of("retail"), "term", "monthly", "way_in", "trial", "slug", OnboardingIT.slug()),
                operator.accessToken());

        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("activated_now").asBoolean()).isTrue();
        assertThat(done.getBody().path("address_setup").isNull()
                        || done.getBody().path("address_setup").isMissingNode())
                .isTrue();
        UUID tenantId = UUID.fromString(done.getBody().get("tenant_id").asString());
        assertThat(addressAudits(tenantId)).isZero();

        ResponseEntity<JsonNode> detail = platform.get("/api/v1/platform/applications/" + id, operator.accessToken());
        assertThat(detail.getBody().path("address_setup_available").asBoolean(true))
                .isFalse();

        ResponseEntity<JsonNode> again = platform.post(
                "/api/v1/platform/applications/" + id + "/address-setup", Map.of(), operator.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getBody().path("code").asString()).isEqualTo("address_setup_off");
    }

    @Test
    void aCloudflareFailureLeavesTheBusinessActiveAndTheRetrySetsTheAddressUp() throws Exception {
        UUID id = verified(Api.email("address-on"));
        String slug = OnboardingIT.slug();
        try (FakeCloudflare cloudflare = new FakeCloudflare()) {
            cloudflare.failures.put("GET dns", 503);
            CloudflareAddress on = new CloudflareAddress(
                    new CloudflareProperties(
                            FakeCloudflare.TOKEN,
                            FakeCloudflare.ZONE,
                            FakeCloudflare.ACCOUNT,
                            FakeCloudflare.TUNNEL,
                            null),
                    hosts,
                    mapper,
                    platformAudit,
                    transactionManager,
                    cloudflare.base(),
                    Duration.ofSeconds(3));
            BusinessClock clock = new BusinessClock(Clock.systemUTC());
            ApplicationService service = new ApplicationService(
                    jdbc,
                    transactionManager,
                    outbox,
                    platformAudit,
                    hosts,
                    provisioning,
                    clock,
                    new SignUpRateLimiter(clock),
                    new OnboardingProperties(null, null, null),
                    referrals,
                    shortNames,
                    on,
                    manifests);
            CurrentPrincipal.set(Principal.uniform(
                    operatorId,
                    "platform",
                    Set.of("platform.tenants.manage", "platform.tenants.read"),
                    true,
                    Set.of()));
            try {
                ApplicationsController.ActivationResult done = service.activate(id, request(slug));

                assertThat(done.activatedNow()).isTrue();
                assertThat(done.application().status()).isEqualTo("activated");
                assertThat(done.addressSetup().outcome()).isEqualTo("failed");
                assertThat(done.addressSetup().message())
                        .contains("Cloudflare had a problem (HTTP 503)")
                        .contains("The business is active either way");
                String status = TestDatabase.owner()
                        .sql("SELECT status FROM tenants WHERE id = ?")
                        .param(done.tenantId())
                        .query(String.class)
                        .single();
                assertThat(status).isEqualTo("active");
                assertThat(addressAudits(done.tenantId())).isEqualTo(1);

                cloudflare.failures.clear();
                CloudflareAddress.Result again = service.setUpAddressAgain(id);

                assertThat(again.outcome()).isEqualTo("set_up");
                assertThat(again.host()).isEqualTo(slug + "-bms-staging.rincoltech.test");
                assertThat(cloudflare.records).hasSize(1);
                assertThat(cloudflare.ingressHosts()).contains(slug + "-bms-staging.rincoltech.test");
                assertThat(addressAudits(done.tenantId())).isEqualTo(2);
                String audited = TestDatabase.owner()
                        .sql("SELECT string_agg(data::text, ' ') FROM platform_audit_log"
                                + " WHERE action = 'platform.tenant.address_setup' AND tenant_id = ?")
                        .param(done.tenantId())
                        .query(String.class)
                        .single();
                assertThat(audited).contains("\"again\"").doesNotContain(FakeCloudflare.TOKEN);
            } finally {
                CurrentPrincipal.clear();
            }
        }
    }

    // JUSTIFICATION-A3: the review of #966 asked for point 1 proven through activate and the retry, not only setUp.
    @Test
    void aHostThatCannotBeMadeNeverFailsActivateOrTheRetry() throws Exception {
        UUID id = verified(Api.email("address-bad-host"));
        String slug = OnboardingIT.slug();
        // Only the address setup gets a host URI rejects (a _); the rest of Activate is real.
        PlatformHost underscore = new PlatformHost() {
            @Override
            public boolean servesPlatform(String host) {
                return hosts.servesPlatform(host);
            }

            @Override
            public boolean servesTenant(String host) {
                return hosts.servesTenant(host);
            }

            @Override
            public boolean isValidSlug(String s) {
                return hosts.isValidSlug(s);
            }

            @Override
            public String tenantOrigin(String s) {
                return "https://" + s + "_bms-staging.rincoltech.test";
            }

            @Override
            public String platformOrigin() {
                return hosts.platformOrigin();
            }
        };
        try (FakeCloudflare cloudflare = new FakeCloudflare()) {
            CloudflareAddress on = new CloudflareAddress(
                    new CloudflareProperties(
                            FakeCloudflare.TOKEN,
                            FakeCloudflare.ZONE,
                            FakeCloudflare.ACCOUNT,
                            FakeCloudflare.TUNNEL,
                            null),
                    underscore,
                    mapper,
                    platformAudit,
                    transactionManager,
                    cloudflare.base(),
                    Duration.ofSeconds(3));
            BusinessClock clock = new BusinessClock(Clock.systemUTC());
            ApplicationService service = new ApplicationService(
                    jdbc,
                    transactionManager,
                    outbox,
                    platformAudit,
                    hosts,
                    provisioning,
                    clock,
                    new SignUpRateLimiter(clock),
                    new OnboardingProperties(null, null, null),
                    referrals,
                    shortNames,
                    on,
                    manifests);
            CurrentPrincipal.set(Principal.uniform(
                    operatorId,
                    "platform",
                    Set.of("platform.tenants.manage", "platform.tenants.read"),
                    true,
                    Set.of()));
            // The real controller over HTTP (MockMvc, the application's JSON mapper) on this service.
            MockMvc web = MockMvcBuilders.standaloneSetup(new ApplicationsController(service, hostnameCheck))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                    .build();
            try {
                MvcResult activated = web.perform(post("/api/v1/platform/applications/" + id + "/activate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of(
                                        "modules",
                                        List.of("retail"),
                                        "term",
                                        "monthly",
                                        "way_in",
                                        "trial",
                                        "slug",
                                        slug))))
                        .andReturn();

                assertThat(activated.getResponse().getStatus()).isEqualTo(200);
                JsonNode done = mapper.readTree(activated.getResponse().getContentAsString());
                assertThat(done.path("activated_now").asBoolean()).isTrue();
                assertThat(done.path("admin_invitation_url").asString("")).isNotBlank();
                assertThat(done.path("address_setup").path("outcome").asString())
                        .isEqualTo("failed");
                assertThat(done.path("address_setup").path("host").isNull()
                                || done.path("address_setup").path("host").isMissingNode())
                        .isTrue();
                assertThat(done.path("address_setup").path("message").asString())
                        .contains("BMS_TENANT_HOST_PATTERN")
                        .contains("The business is active either way");
                UUID tenantId = UUID.fromString(done.path("tenant_id").asString());

                MvcResult again = web.perform(post("/api/v1/platform/applications/" + id + "/address-setup"))
                        .andReturn();

                assertThat(again.getResponse().getStatus()).isEqualTo(200);
                JsonNode retried = mapper.readTree(again.getResponse().getContentAsString());
                assertThat(retried.path("outcome").asString()).isEqualTo("failed");
                assertThat(retried.path("message").asString()).contains("BMS_TENANT_HOST_PATTERN");
                assertThat(cloudflare.requests).isEmpty();
                String status = TestDatabase.owner()
                        .sql("SELECT status FROM tenants WHERE id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single();
                assertThat(status).isEqualTo("active");
                List<String> reasons = TestDatabase.owner()
                        .sql("SELECT data->>'reason' FROM platform_audit_log"
                                + " WHERE action = 'platform.tenant.address_setup' AND tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .list();
                assertThat(reasons).containsExactly("bad_host", "bad_host");
            } finally {
                CurrentPrincipal.clear();
            }
        }
    }
}
