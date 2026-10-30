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
 * only an application whose email is confirmed holds a name; a repeat sign-up changes the name it asks for; the reserved
 * list is refused and listed for the Cloudflare rule; the short link reaches the shop and Business set-up shows it.
 * Fabricated businesses and people only.
 */
class ShortLinkIT extends IntegrationTest {

    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired
    TestRestTemplate http;

    Api platform;
    TestDatabase.Fixture t;
    Api api;
    Session staff;
    String ip;

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        platform = Api.platform(http);
        t = TestDatabase.tenant("short", false, true);
        api = Api.tenant(http, t.slug());
        String email = Api.email("short-admin");
        UUID admin = Api.staff(t, email, new Role("tenant_admin", null));
        staff = api.signIn(admin, email, Api.PASSWORD, null);
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
        if (shortName != null) {
            form.put("short_name", shortName);
        }
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

    /** The application's short name, or an empty string for none. */
    static String shortNameOf(String email) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(short_name, '') FROM onboarding_applications WHERE contact_email = ?")
                .param(email)
                .query(String.class)
                .single();
    }

    String follow(String name) {
        ResponseEntity<JsonNode> r = platform.get("/api/v1/platform/short-links/" + name, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return r.getHeaders().getLocation().toString();
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

        JsonNode free = check(fresh("tfree"), "Test Electricals Shop", null).getBody();
        assertThat(free.get("available").asBoolean()).isTrue();
        assertThat(free.get("suggestions")).isEmpty();
    }

    /**
     * Review of #713 items 3 and 4: an unconfirmed application holds nothing, so a throwaway address cannot squat a name;
     * the first to confirm keeps it, and the next applicant is refused whatever email they give, their own included.
     */
    @Test
    void onlyAConfirmedApplicationHoldsAName() {
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

    /** A repeat sign-up never silently keeps the old name: the new one, claimed as free, replaces it. */
    @Test
    void aRepeatSignUpChangesTheNameItAsksFor() {
        String again = Api.email("short-again");
        String first = fresh("tfirst");
        String second = fresh("tsecond");
        String third = fresh("tthird");
        assertThat(apply(first, null, again).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(apply(second, null, again).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(shortNameOf(again)).isEqualTo(second);
        confirm(again);
        // Review of #713 round 3: anyone may post the confirmed applicant's email with a new name, and gets the usual
        // 202, but the held name stays until the fresh link sent to that mailbox is opened.
        assertThat(apply(third, null, again).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(shortNameOf(again)).isEqualTo(second);
        assertThat(check(second, "Test Electricals Shop", null)
                        .getBody()
                        .get("problem")
                        .asString())
                .isEqualTo("taken");
        confirm(again);
        assertThat(shortNameOf(again)).isEqualTo(third);
        assertThat(check(second, "Test Electricals Shop", null)
                        .getBody()
                        .get("available")
                        .asBoolean())
                .isTrue();
    }

    /** A sign-up page from before the short name sends none, for one release: a free one comes from the business name. */
    @Test
    void aSignUpWithoutAShortNameGetsAFreeOne() {
        String email = Api.email("short-none");
        ResponseEntity<JsonNode> applied = apply(null, null, email);
        assertThat(applied.getStatusCode()).as("%s", applied.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String picked = shortNameOf(email);
        assertThat(picked).startsWith("test").matches(ShortNames.SHAPE);
        assertThat(ShortNames.refusal(picked)).isEmpty();
    }

    /** The reserved list: the website's own paths are refused at sign-up, and listed for the rule. */
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
        assertThat(apply("pricing", null).getStatusCode().value()).isEqualTo(422);

        JsonNode rule = platform.get("/api/v1/platform/short-link-rule", null).getBody();
        assertThat(rule.get("reserved").toString()).contains("\"about\"").contains("\"privacy\"");
        assertThat(rule.get("expression").asString())
                .contains("http.host eq \"rincoltech.com\"")
                .contains("\"/about\"")
                .contains("\"/privacy\"");
        assertThat(rule.get("target").asString()).contains("/api/v1/platform/short-links\", http.request.uri.path)");
        assertThat(rule.get("status_code").asInt()).isEqualTo(302);
    }

    /** The short link reaches the shop; an unknown one reaches the page that says no shop uses it; Settings shows it. */
    @Test
    void theShortLinkForwardsToTheShop() {
        assertThat(follow(t.slug())).isEqualTo("https://" + t.slug() + "-bms-staging.rincoltech.test/");
        assertThat(follow(t.slug().toUpperCase())).isEqualTo("https://" + t.slug() + "-bms-staging.rincoltech.test/");
        assertThat(follow("tnoshop1")).endsWith("/link-not-found?name=tnoshop1");
        JsonNode mine =
                api.get("/api/v1/settings/short-link", staff.accessToken()).getBody();
        assertThat(mine.get("short_name").asString()).isEqualTo(t.slug());
        assertThat(mine.get("link").asString()).isEqualTo("rincoltech.com/" + t.slug());
        assertThat(mine.get("url").asString()).isEqualTo("https://rincoltech.com/" + t.slug());
        // No self-service rename (owner decision on #713): there is nothing to change the short name with.
        ResponseEntity<JsonNode> put = api.call(
                HttpMethod.PUT,
                "/api/v1/settings/short-link",
                Map.of("short_name", fresh("tnew")),
                staff.accessToken());
        assertThat(put.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
