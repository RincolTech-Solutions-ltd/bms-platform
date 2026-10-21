package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The sign-in limits of ADR-059 (launch readiness M9, issue #426; FR-IAM-05): per client IP, per sign-in address and the lock
 * of an address that matches no account, which behaves exactly like the database lock of a real one. Fabricated values only.
 */
class AuthThrottleTest {

    /** A clock the test moves by hand. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MovingClock clock = new MovingClock();
    final AuthThrottle throttle = new AuthThrottle(new BusinessClock(clock), 3);

    static Accounts accounts(String kind, UUID tenantId) {
        Accounts accounts = mock(Accounts.class);
        when(accounts.kind()).thenReturn(kind);
        when(accounts.tenantId()).thenReturn(tenantId);
        return accounts;
    }

    @Test
    void theSignInLimitPerIpRefillsOverTenMinutes() {
        for (int i = 0; i < 3; i++) {
            throttle.acquire(throttle.signInPerIp, "203.0.113.7");
        }
        assertThatThrownBy(() -> throttle.acquire(throttle.signInPerIp, "203.0.113.7"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("rate_limited");
                });
        // Another office is not affected, and one IPv6 /64 is one client.
        assertThatCode(() -> throttle.acquire(throttle.signInPerIp, "203.0.113.8"))
                .doesNotThrowAnyException();
        for (int i = 0; i < 3; i++) {
            throttle.acquire(throttle.signInPerIp, "2001:db8:1:2::" + (i + 1));
        }
        assertThat(throttle.tryAcquire(throttle.signInPerIp, "2001:db8:1:2:ffff::9"))
                .isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(10).dividedBy(3));
        assertThat(throttle.tryAcquire(throttle.signInPerIp, "203.0.113.7")).isTrue();
        assertThat(throttle.tryAcquire(throttle.signInPerIp, "203.0.113.7")).isFalse();
    }

    /** The budget follows the address typed, whatever IP sends it, and the same address in another form is the same key. */
    @Test
    void theSignInLimitPerAddressCountsEveryFormOfTheAddress() {
        Accounts staff = accounts("staff", UUID.fromString("00000000-0000-4000-8000-0000000000a1"));
        String phone = AuthThrottle.address(staff, "0700 000 001");
        assertThat(AuthThrottle.address(staff, "+256700000001")).isEqualTo(phone);
        assertThat(AuthThrottle.address(staff, " Test.Cashier@Example.TEST "))
                .isEqualTo(AuthThrottle.address(staff, "test.cashier@example.test"));
        // Another tenant, or a platform operator, is another address.
        assertThat(AuthThrottle.address(accounts("staff", UUID.randomUUID()), "0700000001"))
                .isNotEqualTo(phone);
        assertThat(AuthThrottle.address(accounts("platform", null), "0700000001"))
                .isNotEqualTo(phone);

        for (int i = 0; i < AuthThrottle.SIGN_IN_PER_ADDRESS.capacity(); i++) {
            throttle.acquireFor(AuthThrottle.SIGN_IN_PER_ADDRESS, phone);
        }
        assertThatThrownBy(() -> throttle.acquireFor(AuthThrottle.SIGN_IN_PER_ADDRESS, phone))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("rate_limited"));
    }

    /** FR-IAM-05 for an address with no account: locked at the fifth failure in a row, for 15 minutes, then counted afresh. */
    @Test
    void anUnknownAddressLocksExactlyLikeARealAccount() {
        String address = AuthThrottle.address(accounts("staff", UUID.randomUUID()), "nobody@example.test");
        for (int i = 1; i < AuthFlow.MAX_FAILURES; i++) {
            throttle.unknownFailed(address);
            assertThat(throttle.unknownLocked(address))
                    .as("after %s failures", i)
                    .isFalse();
        }
        throttle.unknownFailed(address);
        assertThat(throttle.unknownLocked(address)).isTrue();

        // Failures inside the lock keep it, they do not extend it.
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        throttle.unknownFailed(address);
        assertThat(throttle.unknownLocked(address)).isTrue();
        clock.now = clock.now.plus(AuthFlow.LOCK.minus(Duration.ofMinutes(10)));
        assertThat(throttle.unknownLocked(address)).isFalse();

        // After the lock a new run of failures starts from one.
        throttle.unknownFailed(address);
        assertThat(throttle.unknownLocked(address)).isFalse();
    }
}
