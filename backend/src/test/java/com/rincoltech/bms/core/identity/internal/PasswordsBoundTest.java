package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Launch readiness M9, ADR-059, issue #426: argon2id runs a bounded number of hashes at once, and a caller that finds no
 * free slot in time is told sign-in is busy (503, Retry-After from the handler) instead of the API filling its heap.
 */
class PasswordsBoundTest {

    final Passwords passwords = new Passwords(new AuthProperties("none", null, null, null, null, 4096, 1), 2);

    @Test
    void aHashWaitsForAFreeSlotAndIsRefusedAsBusyWhenNoneComes() throws Exception {
        String hash = passwords.hash("Fabricated-Pass-2026");
        passwords.hashing.acquire(2);
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> passwords.matches("Fabricated-Pass-2026", hash))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("sign_in_busy");
                    });
            // An unknown account is refused the same way: the dummy hash takes a slot too.
            assertThatThrownBy(() -> passwords.matches("Fabricated-Pass-2026", null))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("sign_in_busy"));
            assertThat((System.nanoTime() - started) / 1_000_000)
                    .isGreaterThanOrEqualTo(2 * 1000L * Passwords.WAIT_SECONDS - 100);
        } finally {
            passwords.hashing.release(2);
        }
        // Every slot comes back after use.
        assertThat(passwords.matches("Fabricated-Pass-2026", hash)).isTrue();
        assertThat(passwords.hashing.availablePermits()).isEqualTo(2);
    }
}
