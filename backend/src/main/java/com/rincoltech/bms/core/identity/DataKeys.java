package com.rincoltech.bms.core.identity;

/**
 * Field encryption with the application data key (chapter 8 section 8.7), for any module that must keep a secret it
 * has to read back (a provider credential, a TOTP seed). AES-256-GCM with a random nonce; the ciphertext names the key
 * that sealed it, so a data key can be rotated row by row: the host keeps the retired keys in
 * {@code BMS_DATA_KEY_PREVIOUS} until every row is sealed with the current one ({@link #needsRewrap}).
 *
 * <p>Nothing here ever returns, logs or prints a key; a failure names no value.
 */
public interface DataKeys {

    /** Seals the plaintext with the current key. */
    byte[] seal(byte[] plaintext);

    /** Opens a box sealed with the current key or any retired key still configured; otherwise throws. */
    byte[] open(byte[] box);

    /** The id of the current key, as stored in each new ciphertext. */
    String currentKeyId();

    /** The id of the key a box was sealed with. */
    String keyIdOf(byte[] box);

    /**
     * A keyed, one-way digest of a value (HMAC-SHA256 under a key derived from the current data key for this purpose, hex),
     * for finding or de-duplicating a secret without storing it: equal inputs give equal digests while the current key
     * stays the same, so whoever stores one must recompute it when re-sealing after a rotation.
     */
    String blindIndex(String purpose, String value);

    /** True when the box was sealed with a key other than the current one, so a rotation job should re-seal it. */
    default boolean needsRewrap(byte[] box) {
        return !currentKeyId().equals(keyIdOf(box));
    }
}
