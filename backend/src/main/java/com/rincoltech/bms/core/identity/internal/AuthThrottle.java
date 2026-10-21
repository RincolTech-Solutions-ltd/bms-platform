package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Token buckets for the public sign-in family (FR-IAM-05, FR-IAM-15, ADR-042, ADR-059): per client IP on every public sign-in,
 * two-step, invitation, setup and password reset route, and per sign-in address (the email or phone typed, within one
 * tenant) on sign-in and the forgotten password, and per account on the second factor. In memory and bounded: at most
 * {@link #MAX_KEYS} keys a map, the least recently used dropped first, so a flood cannot grow it. An IPv6 client is keyed
 * by its /64, an IPv4 client by its address. Per instance and lost on restart.
 *
 * <p>Nothing here depends on whether an account exists, so no answer tells a prober anything: a bucket is keyed by what
 * the caller sent, and an address that matches no account is locked after the same {@link AuthFlow#MAX_FAILURES}
 * failures, for the same {@link AuthFlow#LOCK}, with the same 423 {@code account_locked} as a real account (whose lock
 * stays in the database). The limits per account that must survive a restart are counted in the database by
 * {@link AuthFlow} and {@link PasswordResets}.
 */
@Component
class AuthThrottle {

    /** {@code capacity} requests, refilled evenly over {@code per}. */
    record Limit(String name, int capacity, Duration per) {}

    /** Requests for a reset link: 20 an hour from one address (a branch office shares one). */
    static final Limit REQUEST_PER_IP = new Limit("reset-request-ip", 20, Duration.ofHours(1));
    /** Attempts to use a link: 30 in 10 minutes from one address. The token is 256 random bits; this only bounds noise. */
    static final Limit COMPLETE_PER_IP = new Limit("reset-complete-ip", 30, Duration.ofMinutes(10));
    /** Requests for a reset link for one sign-in address: 10 an hour, whoever asks (the database allows 3 links of them). */
    static final Limit REQUEST_PER_ADDRESS = new Limit("reset-request-address", 10, Duration.ofHours(1));
    /** Sign-in attempts for one sign-in address: 30 in 15 minutes, right or wrong, from anywhere. */
    static final Limit SIGN_IN_PER_ADDRESS = new Limit("sign-in-address", 30, Duration.ofMinutes(15));
    /** Second-factor attempts for one account: 30 in 15 minutes; the fifth wrong code in a row still locks it. */
    static final Limit MFA_PER_ACCOUNT = new Limit("mfa-account", 30, Duration.ofMinutes(15));

    static final int MAX_KEYS = 10_000;

    /**
     * Sign-in, two-step, invitation and setup requests from one client IP: {@code bms.auth.sign-in-per-ip} in 10 minutes,
     * 60 by default (about 30 people of one office signing in with two steps). The test profile raises it, because the
     * whole suite signs in from one address. Package-private and not final so a test can lower it for a moment.
     */
    Limit signInPerIp;

    private final BusinessClock clock;
    private final Map<String, Bucket> buckets = bounded();
    private final Map<String, Failures> unknown = bounded();

    AuthThrottle(BusinessClock clock, @Value("${bms.auth.sign-in-per-ip:60}") int signInPerIp) {
        this.clock = clock;
        this.signInPerIp = new Limit("sign-in-ip", Math.max(1, signInPerIp), Duration.ofMinutes(10));
    }

    /** Takes one token for the client IP or answers 429 {@code rate_limited}. */
    void acquire(Limit limit, String ip) {
        take(limit, ip(ip));
    }

    /** Takes one token for a subject (a sign-in address, an account) or answers 429 {@code rate_limited}. */
    void acquireFor(Limit limit, String subject) {
        take(limit, "subject:" + subject);
    }

    boolean tryAcquire(Limit limit, String ip) {
        return tryTake(limit, ip(ip));
    }

    /**
     * The key of a sign-in address within one tenant and one kind of account: an email in lower case, a phone in E.164
     * when it reads as one, anything else as typed in lower case.
     */
    static String address(Accounts accounts, String login) {
        String typed = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
        String normal = typed.contains("@")
                ? typed
                : PhoneNumbers.normaliseUganda(typed).orElse(typed);
        return accounts.kind() + ":" + accounts.tenantId() + ":" + normal;
    }

    /** Whether an address that matches no account has failed {@link AuthFlow#MAX_FAILURES} times in a row and is locked. */
    synchronized boolean unknownLocked(String address) {
        Failures failures = unknown.get(address);
        return failures != null && failures.lockedUntil != null && clock.now().isBefore(failures.lockedUntil);
    }

    /** Counts a failure for an address that matches no account, exactly as the database counts one for an account. */
    synchronized void unknownFailed(String address) {
        Instant now = clock.now();
        Failures failures = unknown.computeIfAbsent(address, k -> new Failures());
        if (failures.lockedUntil != null && !now.isBefore(failures.lockedUntil)) {
            failures.consecutive = 0;
            failures.lockedUntil = null;
        }
        failures.consecutive++;
        if (failures.lockedUntil == null && failures.consecutive >= AuthFlow.MAX_FAILURES) {
            failures.lockedUntil = now.plus(AuthFlow.LOCK);
        }
    }

    private void take(Limit limit, String key) {
        if (!tryTake(limit, key)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many requests",
                    "Too many attempts. Wait a few minutes and try again.");
        }
    }

    private synchronized boolean tryTake(Limit limit, String key) {
        Instant now = clock.now();
        String id = limit.name() + ":" + key;
        Bucket bucket = buckets.computeIfAbsent(id, k -> new Bucket(limit.capacity(), now));
        double perToken = (double) limit.per().toMillis() / limit.capacity();
        double refill = Math.max(0, Duration.between(bucket.at, now).toMillis()) / perToken;
        bucket.tokens = Math.min(limit.capacity(), bucket.tokens + refill);
        bucket.at = now;
        if (bucket.tokens < 1) {
            return false;
        }
        bucket.tokens -= 1;
        return true;
    }

    private static String ip(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        try {
            InetAddress parsed = InetAddress.ofLiteral(address.trim());
            if (parsed instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "::/64";
            }
            return "v4:" + parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            return "raw:" + address;
        }
    }

    private static <V> Map<String, V> bounded() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_KEYS;
            }
        };
    }

    private static final class Bucket {
        double tokens;
        Instant at;

        Bucket(double tokens, Instant at) {
            this.tokens = tokens;
            this.at = at;
        }
    }

    private static final class Failures {
        int consecutive;
        Instant lockedUntil;
    }
}
