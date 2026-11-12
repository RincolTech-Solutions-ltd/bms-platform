package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The recording fake of chapter 12 section 12.8, for the dev and test profiles only: objects live
 * in memory and its signed URLs point at {@link FakeStorageController}, carrying an expiry and an
 * HMAC over key and expiry, so expiry and tampering behave as they do with R2. Given a directory
 * ({@code bms.storage.fake-dir}), it keeps each object's bytes in a file there instead, so a test that judges the heap
 * holds no stored file in it, as R2 holds none in the API's (the big file test, issue #900).
 */
class FakeObjectStorage implements ObjectStorage {

    static final String PATH = "/api/v1/storage/fake";

    /** An object: its bytes, or the file that holds them when the fake has a directory. */
    record StoredObject(byte[] bytes, String contentType, java.time.Instant at, java.nio.file.Path file) {

        StoredObject(byte[] bytes, String contentType, java.time.Instant at) {
            this(bytes, contentType, at, null);
        }

        @Override
        public byte[] bytes() {
            if (file == null) {
                return bytes;
            }
            try {
                return java.nio.file.Files.readAllBytes(file);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final byte[] secret;
    private final BusinessClock clock;
    private final java.nio.file.Path dir;

    FakeObjectStorage(String secret, BusinessClock clock) {
        this(secret, clock, null);
    }

    /** {@code dir}: where each object's bytes are kept; null keeps them in memory. */
    FakeObjectStorage(String secret, BusinessClock clock, java.nio.file.Path dir) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.dir = dir;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        keep(key, bytes, contentType, clock.now());
    }

    /** For tests: an object as if storage had written it at {@code at}, so the sweep's age rule can be tested. */
    void putAt(String key, byte[] bytes, String contentType, java.time.Instant at) {
        keep(key, bytes, contentType, at);
    }

    private void keep(String key, byte[] bytes, String contentType, java.time.Instant at) {
        if (dir == null) {
            drop(objects.put(key, new StoredObject(bytes.clone(), contentType, at)));
            return;
        }
        try {
            java.nio.file.Path file = java.nio.file.Files.write(fileFor(), bytes);
            drop(objects.put(key, new StoredObject(null, contentType, at, file)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private java.nio.file.Path fileFor() throws java.io.IOException {
        java.nio.file.Files.createDirectories(dir);
        return dir.resolve(java.util.UUID.randomUUID().toString());
    }

    /** Deletes the file of an object the fake no longer holds. */
    private static void drop(StoredObject gone) {
        if (gone != null && gone.file() != null) {
            try {
                java.nio.file.Files.deleteIfExists(gone.file());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    /** For tests: keys that {@link #deleteAll} reports as failed and keeps, like an object store that refuses some. */
    final java.util.Set<String> failDeletes = ConcurrentHashMap.newKeySet();

    @Override
    public java.util.List<String> deleteAll(java.util.List<String> keys) {
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (String key : keys) {
            if (failDeletes.contains(key)) {
                failed.add(key);
            } else {
                drop(objects.remove(key));
            }
        }
        return failed;
    }

    /** For tests: whether the fake holds an object under this key. */
    boolean has(String key) {
        return objects.containsKey(key);
    }

    @Override
    public java.util.stream.Stream<Listed> list(String prefix) {
        return objects.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> new Listed(e.getKey(), e.getValue().at()))
                .toList()
                .stream();
    }

    @Override
    public void putFile(String key, java.nio.file.Path file, String contentType) {
        try {
            if (dir == null) {
                drop(objects.put(
                        key, new StoredObject(java.nio.file.Files.readAllBytes(file), contentType, clock.now())));
                return;
            }
            java.nio.file.Path copy = java.nio.file.Files.copy(file, fileFor());
            drop(objects.put(key, new StoredObject(null, contentType, clock.now(), copy)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** For tests: reads served by {@link #get}, and a switch that makes them fail like an outage. */
    final java.util.concurrent.atomic.AtomicInteger gets = new java.util.concurrent.atomic.AtomicInteger();

    volatile boolean failGets;

    @Override
    public Optional<byte[]> get(String key) {
        gets.incrementAndGet();
        if (failGets) {
            throw new IllegalStateException("simulated storage outage");
        }
        return Optional.ofNullable(objects.get(key)).map(o -> o.bytes().clone());
    }

    @Override
    public void delete(String key) {
        drop(objects.remove(key));
    }

    /** For tests: how many objects the fake holds. */
    int objectCount() {
        return objects.size();
    }

    @Override
    public String signedGetUrl(String key, Duration ttl, String downloadName, String contentType) {
        long expires = clock.now().plus(ttl).getEpochSecond();
        return PATH + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&expires=" + expires
                + "&name=" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8)
                + "&sig=" + sign(key + "\n" + downloadName, expires);
    }

    /** The object when the signature matches and has not expired; empty otherwise. */
    Optional<StoredObject> read(String key, long expires, String name, String sig) {
        boolean signed = MessageDigest.isEqual(
                sign(key + "\n" + name, expires).getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
        if (!signed || clock.now().getEpochSecond() > expires) {
            return Optional.empty();
        }
        return Optional.ofNullable(objects.get(key));
    }

    String sign(String key, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
