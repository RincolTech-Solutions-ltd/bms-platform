package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.LinkedHashMap;
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
 * Short links (issue #708, ADR-108, FR-TEN-12): a taken name is refused at sign-up with suggestions, the town or area first;
 * the reserved list is refused; the short link forwards to the shop; after a change in Settings the old link forwards for 90
 * days, and the freed name is then held 90 more. Fabricated businesses and people only.
 */
class ShortLinkIT extends IntegrationTest {

    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired
    TestRestTemplate http;

    Api platform;
    TestDatabase.Fixture t;
    Api api;
    Session ownerSession;
    Session adminSession;
    String ip;

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        platform = Api.platform(http);
        t = TestDatabase.tenant("short", false, true);
        api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("short-owner");
        String adminEmail = Api.email("short-admin");
        UUID owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(t.tenantId(), owner)
                .update();
        ownerSession = api.signIn(owner, ownerEmail, Api.PASSWORD, null);
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
        int n = ADDRESS.getAndIncrement();
        ip = "203.0." + (113 + n / 250 % 2) + "." + (n % 250 + 1);
    }

    static String fresh(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    ResponseEntity<JsonNode> check(String name, String business, String town) {
        String query = "/api/v1/platform/sign-up/short-name?name=" + name + "&business_name="
                + business.replace(' ', '+') + (town == null ? "" : "&town=" + town);
        return platform.call(HttpMethod.GET, query, null, null, Map.of("X-Forwarded-For", ip));
    }

    ResponseEntity<JsonNode> apply(String shortName, String town) {
        return apply(shortName, town, Api.email("short-apply"));
    }

    ResponseEntity<JsonNode> apply(String shortName, String town, String email) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("business_name", "Test Electricals Shop");
        form.put("contact_name", "Test Applicant 31");
        form.put("short_name", shortName);
        form.put("town", town);
        form.put("contact_email", email);
        form.put("contact_phone", "+256700000031");
        form.put("country", "UG");
        form.put("modules", List.of("retail"));
        form.put("term", "monthly");
        form.put("way_in", "trial");
        return platform.call(
                HttpMethod.POST, "/api/v1/platform/sign-up/applications", form, null, Map.of("X-Forwarded-For", ip));
    }

    String follow(String name) {
        ResponseEntity<JsonNode> r = platform.get("/api/v1/platform/short-links/" + name, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return r.getHeaders().getLocation().toString();
    }

    // JUSTIFICATION-A3: review item 3 needs confirmed applications in this test; OnboardingIT's helpers are instance
    // methods.
    /** Opens the newest confirmation link sent to the address: the application's email is confirmed. */
    void confirm(String email) {
        String link = TestDatabase.owner()
                .sql("SELECT params->>'link' FROM notification_outbox WHERE template_key = 'onboarding.verify_email'"
                        + " AND recipient = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(email)
                .query(String.class)
                .single();
        Map<String, String> body = Map.of("token", link.substring(link.indexOf("#token=") + 7));
        ResponseEntity<JsonNode> verified = platform.call(
                HttpMethod.POST, "/api/v1/platform/sign-up/verify", body, null, Map.of("X-Forwarded-For", ip));
        assertThat(verified.getStatusCode()).as("%s", verified.getBody()).isEqualTo(HttpStatus.OK);
    }

    static String shortNameOf(String email) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(short_name, '') FROM onboarding_applications WHERE contact_email = ?")
                .param(email)
                .query(String.class)
                .single();
    }

    ResponseEntity<JsonNode> change(Api on, Session who, String name) {
        return on.call(HttpMethod.PUT, "/api/v1/settings/short-link", Map.of("short_name", name), who.accessToken());
    }

    /** A taken name is refused with suggestions, the one with the town first; as typed and at the sign-up. */
    @Test
    void aTakenNameIsRefusedWithSuggestionsTheTownFirst() {
        JsonNode taken = check(t.slug(), "Test Electricals Shop", "Kampala").getBody();
        assertThat(taken.get("available").asBoolean()).isFalse();
        assertThat(taken.get("problem").asString()).isEqualTo("taken");
        assertThat(taken.get("message").asString()).isEqualTo(t.slug() + " is taken.");
        List<String> suggestions =
                taken.get("suggestions").valueStream().map(JsonNode::asString).toList();
        assertThat(suggestions).isNotEmpty().doesNotContain(t.slug());
        assertThat(suggestions.getFirst()).endsWith("-kampala");

        ResponseEntity<JsonNode> refused = apply(t.slug(), "Kampala");
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("short_name_taken");
        assertThat(refused.getBody().get("suggestions").get(0).asString()).endsWith("-kampala");

        // An unconfirmed application holds nothing, so a throwaway address cannot squat a name; the first to confirm
        // keeps it, and the next applicant is refused whatever email they give.
        String mine = fresh("tmine");
        String email = Api.email("short-mine");
        String rival = Api.email("short-rival");
        assertThat(apply(mine, null, email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(apply(mine, null, rival).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(check(mine, "Test Electricals Shop", null)
                        .getBody()
                        .get("available")
                        .asBoolean())
                .isTrue();
        confirm(email);
        assertThat(check(mine, "Test Electricals Shop", null)
                        .getBody()
                        .get("problem")
                        .asString())
                .isEqualTo("taken");
        assertThat(apply(mine, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(apply(mine, null, email).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The rival confirms second: its application loses the name, and the operator chooses its slug at Activate.
        confirm(rival);
        assertThat(shortNameOf(email)).isEqualTo(mine);
        assertThat(shortNameOf(rival)).isEmpty();
    }

    /** A repeat from a mailbox whose email is not confirmed changes the name its application asks for. */
    @Test
    void aRepeatBeforeTheConfirmationChangesTheName() {
        String again = Api.email("short-again");
        String first = fresh("tfirst");
        String second = fresh("tsecond");
        assertThat(apply(first, null, again).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(apply(second, null, again).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(shortNameOf(again)).isEqualTo(second);

        JsonNode free = check(fresh("tfree"), "Test Electricals Shop", null).getBody();
        assertThat(free.get("available").asBoolean()).isTrue();
        assertThat(free.get("suggestions")).isEmpty();
    }

    /** The reserved list: the website's own paths are refused at sign-up and in Settings, and listed for the rule. */
    @Test
    void theReservedNamesAreRefusedAndListedForTheRule() {
        for (String name : List.of(
                "about", "contact", "pricing", "blog", "login", "signup", "app", "www", "api", "admin", "help", "terms",
                "privacy")) {
            assertThat(ShortNames.RESERVED).contains(name);
            assertThat(check(name, "Test Shop", null).getBody().get("problem").asString())
                    .as(name)
                    .isEqualTo("reserved");
        }
        ResponseEntity<JsonNode> refused = apply("pricing", null);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(change(api, ownerSession, "about").getStatusCode().value()).isEqualTo(422);

        JsonNode rule = platform.get("/api/v1/platform/short-link-rule", null).getBody();
        assertThat(rule.get("reserved").toString()).contains("\"about\"").contains("\"privacy\"");
        assertThat(rule.get("expression").asString())
                .contains("http.host eq \"rincoltech.com\"")
                .contains("\"/about\"")
                .contains("\"/privacy\"");
        assertThat(rule.get("target").asString()).contains("/api/v1/platform/short-links\", http.request.uri.path)");
        assertThat(rule.get("status_code").asInt()).isEqualTo(302);
    }

    /** The short link reaches the shop; an unknown one reaches the page that says no shop uses it. */
    @Test
    void theShortLinkForwardsToTheShop() {
        assertThat(follow(t.slug())).isEqualTo("https://" + t.slug() + "-bms-staging.rincoltech.test/");
        assertThat(follow(t.slug().toUpperCase())).isEqualTo("https://" + t.slug() + "-bms-staging.rincoltech.test/");
        assertThat(follow("tnoshop1")).endsWith("/link-not-found?name=tnoshop1");
        JsonNode mine = api.get("/api/v1/settings/short-link", adminSession.accessToken())
                .getBody();
        assertThat(mine.get("link").asString()).isEqualTo("rincoltech.com/" + t.slug());
        assertThat(mine.get("url").asString()).isEqualTo("https://rincoltech.com/" + t.slug());
    }

    /** Only the Owner changes it; the old link forwards to the new address for 90 days, then the name is held 90 more. */
    @Test
    void afterAChangeTheOldLinkForwardsAndTheFreedNameIsHeld() {
        String old = t.slug();
        String renamed = fresh("tnew");
        assertThat(change(api, adminSession, renamed).getBody().get("code").asString())
                .isEqualTo("owner_only");

        ResponseEntity<JsonNode> done = change(api, ownerSession, renamed);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("short_name").asString()).isEqualTo(renamed);
        assertThat(done.getBody().get("forwarding").get(0).get("short_name").asString())
                .isEqualTo(old);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.tenant.short_name_changed'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // The old link forwards to the new address; the new link works at once.
        String newHost = "https://" + renamed + "-bms-staging.rincoltech.test/";
        assertThat(follow(old)).isEqualTo(newHost);
        assertThat(follow(renamed)).isEqualTo(newHost);
        // The old address answers where the business went, so a bookmark or an installed app follows it.
        ResponseEntity<JsonNode> oldHost = api.get("/api/v1/branding", null);
        assertThat(oldHost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(oldHost.getHeaders().getFirst("Bms-Moved-To"))
                .isEqualTo("https://" + renamed + "-bms-staging.rincoltech.test");
        // The hold: forwards 90 days, held 180 days after the change.
        Map<String, Object> hold =
                TestDatabase.owner().sql("""
                        SELECT extract(day FROM forwards_until - released_at)::int AS forwards,
                               extract(day FROM held_until - released_at)::int AS held
                          FROM platform_short_name_holds WHERE name = ?
                        """).param(old).query().singleRow();
        assertThat(hold).containsEntry("forwards", 90).containsEntry("held", 180);

        // Nobody else may take the old name while it is held, at sign-up or in another shop's Settings.
        assertThat(check(old, "Test Shop", null).getBody().get("problem").asString())
                .isEqualTo("taken");
        TestDatabase.Fixture other = TestDatabase.tenant("short-other", false, true);
        Api otherApi = Api.tenant(http, other.slug());
        String otherEmail = Api.email("short-other");
        UUID otherOwner = Api.staff(other, otherEmail, new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(other.tenantId(), otherOwner)
                .update();
        Session otherSession = otherApi.signIn(otherOwner, otherEmail, Api.PASSWORD, null);
        assertThat(change(otherApi, otherSession, old).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // After 90 days the old link no longer forwards, and the name is still held.
        TestDatabase.owner()
                .sql("UPDATE platform_short_name_holds SET released_at = released_at - interval '91 days',"
                        + " forwards_until = forwards_until - interval '91 days',"
                        + " held_until = held_until - interval '91 days' WHERE name = ?")
                .param(old)
                .update();
        assertThat(follow(old)).endsWith("/link-not-found?name=" + old);
        assertThat(api.get("/api/v1/branding", null).getHeaders().getFirst("Bms-Moved-To"))
                .isNull();
        assertThat(change(otherApi, otherSession, old).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // After 180 days the name is free again.
        TestDatabase.owner()
                .sql("UPDATE platform_short_name_holds SET released_at = released_at - interval '90 days',"
                        + " forwards_until = forwards_until - interval '90 days',"
                        + " held_until = held_until - interval '90 days' WHERE name = ?")
                .param(old)
                .update();
        ResponseEntity<JsonNode> taken = change(otherApi, otherSession, old);
        assertThat(taken.getStatusCode()).as("%s", taken.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(follow(old)).isEqualTo("https://" + old + "-bms-staging.rincoltech.test/");
    }
}
