package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.ShortLinkTarget;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipt links (issue #760, ADR-116): a short link {@code rincoltech.com/<token>} on the short link host of ADR-108 that
 * opens the receipt PDF of one sale, read only and with no sign-in, for 90 days. It goes in the message a seller sends to
 * a typed WhatsApp number, since a wa.me link cannot carry a file. The token is 26 characters of lower case base32 (130
 * bits) from a keyed hash of the link's random id, longer than any new short name, and only its SHA-256 is stored; a
 * sale has one live link at a time, given again on every share. On the platform host the short link
 * finds the shop with V128's {@code retail_receipt_link_shop} and goes on to the shop's own host, where the receipt is
 * rendered under the shop's row level security. A wrong or expired link shows {@link #GONE_PAGE}, which says nothing
 * about the business or the sale.
 */
@Component
class ReceiptLinks implements ShortLinkTarget {

    static final Duration LIFETIME = Duration.ofDays(90);

    /** Where the shop's host serves the receipt of a link. */
    static final String PATH = "/api/v1/retail/receipt-links/";

    static final String GONE_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Receipt link expired</title>
            <style>
            body { margin: 0; padding: 32px 16px; font: 16px/1.5 system-ui, sans-serif; color: #1f2328; background: #ffffff; }
            main { max-width: 32rem; margin: 0 auto; }
            h1 { font-size: 1.4rem; }
            </style>
            </head>
            <body>
            <main>
            <h1>This receipt link has expired</h1>
            <p>Ask the shop to send you the receipt again.</p>
            </main>
            </body>
            </html>
            """;

    /** A live link is given again while it has at least this long left; after that a sale gets a new one. */
    static final Duration REUSE_WHILE = Duration.ofDays(30);

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern TOKEN = Pattern.compile("[a-z2-7]{26}");

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ShortNames names;
    private final byte[] tokenKey;

    ReceiptLinks(
            JdbcClient jdbc, BusinessClock clock, ShortNames names, @Value("${bms.auth.data-key:}") String dataKey) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.names = names;
        this.tokenKey = purposeKey(dataKey);
    }

    /**
     * HMAC-SHA256 of this purpose's label under the data key, as {@code CashbookSupport} keys its tokens, so the data key
     * itself never keys a token. The dev and test profiles may leave the data key blank: the key is then random, and a
     * link made before a restart is not given again (a new one is made), though it still opens.
     */
    private static byte[] purposeKey(String dataKey) {
        if (dataKey == null || dataKey.isBlank()) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return key;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dataKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("bms.retail.receipt-link.v1".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A sale's link: {@code link} as a person reads it, {@code url} with https:// for the message, and whether this call
     * made it (only then is it audited).
     */
    record Minted(String url, String link, Instant expiresAt, boolean created) {}

    /** A live link of the bound business: the sale and who made the link. */
    record Live(UUID saleId, UUID createdBy) {}

    // JUSTIFICATION-A3: review of #762, item 7: the public route renders a PDF, so its opens are limited.
    /** Opens of one link, and from one address, in a ten minute window; more is 429 with {@link #BUSY_PAGE}. */
    static final int OPENS_PER_LINK = 20;

    static final int OPENS_PER_ADDRESS = 30;
    static final int MAX_KEYS = 20_000;

    static final String BUSY_PAGE = GONE_PAGE
            .replace("<title>Receipt link expired</title>", "<title>Too many tries</title>")
            .replace("<h1>This receipt link has expired</h1>", "<h1>Too many tries</h1>")
            .replace("Ask the shop to send you the receipt again.", "Wait a few minutes and open the link again.");

    private final Map<String, Integer> opens = new HashMap<>();
    private long window = Long.MIN_VALUE;

    /**
     * Counts an open of the public route and says whether it may go on: at most {@link #OPENS_PER_LINK} of one link and
     * {@link #OPENS_PER_ADDRESS} from one address in ten minutes, so one leaked link cannot fill the render places the
     * sellers' own receipts share. In memory and per instance, behind the edge's own limits; a flood from more addresses
     * than the table holds is limited as a whole, as {@code CallbackLimiter} does.
     */
    synchronized boolean mayOpen(String address, String token) {
        long now = clock.now().getEpochSecond() / 600;
        if (now != window) {
            window = now;
            opens.clear();
        }
        if (opens.size() >= MAX_KEYS) {
            return false;
        }
        // The address first: one over its limit adds no key for a link, so one client cannot fill the table with
        // random tokens and turn every customer away (second review of #762, item 3).
        int byAddress = opens.merge("address:" + (address == null ? "unknown" : address), 1, Integer::sum);
        if (byAddress > OPENS_PER_ADDRESS) {
            return false;
        }
        String link = token != null && TOKEN.matcher(token).matches() ? token : "malformed";
        return opens.merge("link:" + link, 1, Integer::sum) <= OPENS_PER_LINK;
    }

    /**
     * The sale's link, in the caller's write transaction with the business bound: its live link while that has more than
     * {@link #REUSE_WHILE} left, otherwise a new one ({@code created}). One sale therefore has one link to share however
     * often, from however many phones, it is sent (second review of #762, item 1). The sale's lock makes two taps at once
     * share it too. A link made under a data key since rotated cannot be given again and a new one is made.
     */
    Minted mint(UUID saleId, UUID by) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('bms.receipt-link:' || ?))) AS l")
                .param(saleId.toString())
                .query(Integer.class)
                .single();
        Instant now = clock.now();
        Optional<Minted> kept = jdbc.sql("""
                        SELECT id, token_hash, expires_at FROM retail_receipt_links
                         WHERE sale_id = ? AND expires_at > ? ORDER BY expires_at DESC LIMIT 1
                        """)
                .params(saleId, Timestamp.from(now.plus(REUSE_WHILE)))
                .query((rs, n) -> {
                    String token = token(rs.getObject("id", UUID.class));
                    return hash(token).equals(rs.getString("token_hash"))
                            ? minted(token, rs.getTimestamp("expires_at").toInstant(), false)
                            : null;
                })
                .optional();
        if (kept.isPresent()) {
            return kept.get();
        }
        UUID id = UUID.randomUUID();
        String token = token(id);
        Instant expires = now.plus(LIFETIME);
        jdbc.sql("""
                        INSERT INTO retail_receipt_links (id, tenant_id, sale_id, token_hash, created_by, created_at, expires_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        """)
                .params(id, saleId, hash(token), by, Timestamp.from(now), Timestamp.from(expires))
                .update();
        return minted(token, expires, true);
    }

    private Minted minted(String token, Instant expires, boolean created) {
        String link = names.link(token);
        return new Minted("https://" + link, link, expires, created);
    }

    /**
     * A link's token: 26 characters of lower case base32 (130 bits) from an HMAC of its row id under a key made from the
     * data key for this purpose alone, so the server can give a live link again while storing only its hash.
     */
    String token(UUID linkId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            byte[] bytes = mac.doFinal(linkId.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder token = new StringBuilder(26);
            for (int i = 0; i < 26; i++) {
                token.append(ALPHABET.charAt(bytes[i] & 31));
            }
            return token.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The live link of the bound business with this token, in the caller's transaction; empty when wrong or expired. */
    Optional<Live> live(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT sale_id, created_by FROM retail_receipt_links WHERE token_hash = ? AND expires_at > ?")
                .params(hash(token), Timestamp.from(clock.now()))
                .query((rs, n) -> new Live(rs.getObject("sale_id", UUID.class), rs.getObject("created_by", UUID.class)))
                .optional();
    }

    /** On the platform host: a name shaped like a token is a receipt link, live (to the shop's host) or gone. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Answer> follow(String name) {
        String token = ShortNames.normalise(name);
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<String> shop = jdbc.sql("SELECT retail_receipt_link_shop(?, ?)")
                .params(hash(token), Timestamp.from(clock.now()))
                .query(String.class)
                .optional();
        return Optional.of(shop.map(slug -> Answer.to(slug, PATH + token)).orElse(Answer.gone(GONE_PAGE)));
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
