package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.Duration;
import java.util.ArrayList;
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
 * The sign-in limits of ADR-059 through HTTP (launch readiness M9, issue #426; FR-IAM-05, FR-IAM-15): no answer of the
 * sign-in family tells a caller whether an account exists, and one client IP or one address cannot keep going. All users and
 * contacts are fabricated.
 */
class SignInLimitsIT extends IntegrationTest {

    static final String STAFF_LOGIN = "/api/v1/auth/staff/login";
    static final String PLATFORM_LOGIN = "/api/v1/platform/auth/login";
    static final String FORGOT = "/api/v1/auth/staff/password/forgot";
    static final AtomicInteger ADDRESS = new AtomicInteger();

    @Autowired
    TestRestTemplate http;

    @Autowired
    AuthThrottle throttle;

    TestDatabase.Fixture t;
    Api api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("limits", true);
        api = Api.tenant(http, t.slug());
    }

    static String freshIp() {
        return "192.0.2." + (ADDRESS.incrementAndGet() % 250 + 1);
    }

    ResponseEntity<JsonNode> login(Api on, String path, String login, String password, String ip) {
        return on.call(
                HttpMethod.POST,
                path,
                Map.of("login", login, "password", password),
                null,
                Map.of("X-Forwarded-For", ip));
    }

    /** What a prober compares: the status, the code and the words. */
    static String answer(ResponseEntity<JsonNode> response) {
        return response.getStatusCode().value() + " "
                + response.getBody().get("code").asString() + " "
                + response.getBody().get("detail").asString();
    }

    List<String> wrongPasswords(String login, int times) {
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            answers.add(answer(login(api, STAFF_LOGIN, login, "Wrong-Password-" + i, freshIp())));
        }
        return answers;
    }

    /** FR-IAM-05 without enumeration: a real account and an address that matches nobody answer alike, lock included. */
    @Test
    void anUnknownAddressIsAnsweredAndLockedExactlyLikeARealAccount() {
        String real = Api.email("cashier");
        Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");

        List<String> forReal = wrongPasswords(real, 6);
        List<String> forNobody = wrongPasswords(nobody, 6);

        assertThat(forNobody).isEqualTo(forReal);
        assertThat(forReal.subList(0, 5)).allMatch(a -> a.startsWith("401 invalid_credentials "));
        assertThat(forReal.get(5)).startsWith("423 account_locked ");
        // The right password changes nothing while the lock holds, and an unknown address stays locked the same way.
        assertThat(answer(login(api, STAFF_LOGIN, real, Api.PASSWORD, freshIp())))
                .isEqualTo(answer(login(api, STAFF_LOGIN, nobody, Api.PASSWORD, freshIp())));
    }

    /** One address has a budget of attempts from anywhere; the refusal is the same whether or not it is an account. */
    @Test
    void anAddressThatIsTriedTooOftenIsLimitedFromEveryIp() {
        String real = Api.email("teller");
        Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");
        int capacity = AuthThrottle.SIGN_IN_PER_ADDRESS.capacity();

        wrongPasswords(real, capacity);
        wrongPasswords(nobody, capacity);

        ResponseEntity<JsonNode> limitedReal = login(api, STAFF_LOGIN, real, Api.PASSWORD, freshIp());
        ResponseEntity<JsonNode> limitedNobody = login(api, STAFF_LOGIN, nobody, Api.PASSWORD, freshIp());
        assertThat(limitedReal.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedReal.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(answer(limitedNobody)).isEqualTo(answer(limitedReal)).startsWith("429 rate_limited ");
        // Another address is not affected.
        String other = Api.email("other");
        Api.staff(t, other, new Role("cashier", t.headOffice()));
        assertThat(login(api, STAFF_LOGIN, other, "Wrong-Password-x", freshIp()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The per-IP bucket covers the staff and the operator sign-in, and the two-step and invitation routes with them. */
    @Test
    void oneIpThatSignsInTooOftenIsLimitedOnStaffAndOperatorRoutes() {
        String ip = "198.18.0." + (ADDRESS.incrementAndGet() % 250 + 1);
        AuthThrottle.Limit suite = throttle.signInPerIp;
        // Five requests a day, under a name of its own so this IP starts with a full bucket that does not refill
        // mid-test.
        throttle.signInPerIp = new AuthThrottle.Limit("sign-in-ip-" + UUID.randomUUID(), 5, Duration.ofDays(1));
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(throttle.tryAcquire(throttle.signInPerIp, ip)).isTrue();
            }
            refusedFrom(ip);
        } finally {
            throttle.signInPerIp = suite;
        }
        // Another client IP still signs in.
        assertThat(login(api, STAFF_LOGIN, Api.email("someone"), "Wrong-Password-1", freshIp())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    void refusedFrom(String ip) {

        ResponseEntity<JsonNode> staff = login(api, STAFF_LOGIN, Api.email("someone"), "Wrong-Password-1", ip);
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(staff.getBody().get("code").asString()).isEqualTo("rate_limited");
        ResponseEntity<JsonNode> operator =
                login(Api.platform(http), PLATFORM_LOGIN, Api.email("operator"), "Wrong-Password-1", ip);
        assertThat(operator.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map<String, Map<String, String>> bodies = Map.of(
                "/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", "x", "code", "000000"),
                "/api/v1/auth/staff/mfa/confirm", Map.of("mfa_token", "x", "code", "000000"),
                "/api/v1/auth/staff/invitations/accept", Map.of("token", "x", "password", "Fabricated-Pass-2026"),
                "/api/v1/platform/auth/mfa/verify", Map.of("mfa_token", "x", "code", "000000"));
        bodies.forEach((path, body) -> {
            Api on = path.startsWith("/api/v1/platform") ? Api.platform(http) : api;
            ResponseEntity<JsonNode> refused =
                    on.call(HttpMethod.POST, path, body, null, Map.of("X-Forwarded-For", ip));
            assertThat(refused.getStatusCode()).as(path).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        });
    }

    /** FR-IAM-15: reset links for one address are bounded whoever asks, with the same 429 for an address of nobody. */
    @Test
    void resetLinksForOneAddressAreLimitedWhetherOrNotItIsAnAccount() {
        String real = Api.email("clerk");
        Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");
        int capacity = AuthThrottle.REQUEST_PER_ADDRESS.capacity();
        List<String> answers = new ArrayList<>();
        for (String login : List.of(real, nobody)) {
            for (int i = 0; i < capacity; i++) {
                assertThat(forgot(login).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            }
            answers.add(answer(forgot(login)));
        }
        assertThat(answers.get(0)).isEqualTo(answers.get(1)).startsWith("429 rate_limited ");
    }

    ResponseEntity<JsonNode> forgot(String login) {
        return api.call(HttpMethod.POST, FORGOT, Map.of("login", login), null, Map.of("X-Forwarded-For", freshIp()));
    }
}
