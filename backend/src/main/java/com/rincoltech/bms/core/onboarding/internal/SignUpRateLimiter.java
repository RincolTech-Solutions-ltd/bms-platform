package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Token buckets for the public sign-up endpoints (spec sections 10 and 12, chapter 7 section
 * 7.10), in memory and bounded: at most {@link #MAX_KEYS} keys, the least recently used dropped
 * first, so a flood of addresses cannot grow it. Per instance and lost on restart, which is
 * acceptable for one API container; an edge rule in front (Cloudflare) is the dev lead's job and
 * is the real volume control. Keys are an address or a hash of an email, never the email itself.
 */
@Component
class SignUpRateLimiter {

    /** {@code capacity} requests, refilled evenly over {@code per}. */
    record Limit(String name, int capacity, Duration per) {}

    /** New applications: 5 an hour from one address. */
    static final Limit CREATE_PER_IP = new Limit("create-ip", 5, Duration.ofHours(1));
    /** New applications: 3 an hour for one email. */
    static final Limit CREATE_PER_EMAIL = new Limit("create-email", 3, Duration.ofHours(1));
    /** Link reads (verify, status, reply): 30 in 10 minutes from one address. */
    static final Limit LINK_PER_IP = new Limit("link-ip", 30, Duration.ofMinutes(10));

    static final int MAX_KEYS = 10_000;

    private final BusinessClock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    SignUpRateLimiter(BusinessClock clock) {
        this.clock = clock;
    }

    /** Takes one token or answers 429 {@code rate_limited} with a Retry-After hint in the detail. */
    void acquire(Limit limit, String key) {
        if (!tryAcquire(limit, key)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many requests",
                    "Too many attempts. Wait a few minutes and try again.");
        }
    }

    synchronized boolean tryAcquire(Limit limit, String key) {
        Instant now = clock.now();
        String id = limit.name() + ":" + (key == null ? "unknown" : key);
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

    synchronized int size() {
        return buckets.size();
    }

    private static final class Bucket {
        double tokens;
        Instant at;

        Bucket(double tokens, Instant at) {
            this.tokens = tokens;
            this.at = at;
        }
    }
}
