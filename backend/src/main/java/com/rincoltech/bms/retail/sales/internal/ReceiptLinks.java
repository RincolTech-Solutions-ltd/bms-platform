package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.ShortLinkTarget;
import com.rincoltech.bms.core.tenancy.ShortNames;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipt links (issue #760, ADR-116): a short link {@code rincoltech.com/<token>} on the short link host of ADR-108 that
 * opens the receipt PDF of one sale, read only and with no sign-in, for 90 days. It goes in the message a seller sends to
 * a typed WhatsApp number, since a wa.me link cannot carry a file. The token is 26 random characters of lower case
 * base32 (130 bits), longer than any new short name, and only its SHA-256 is stored. On the platform host the short link
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

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern TOKEN = Pattern.compile("[a-z2-7]{26}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ShortNames names;

    ReceiptLinks(JdbcClient jdbc, BusinessClock clock, ShortNames names) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.names = names;
    }

    /** A new link: {@code link} as a person reads it, {@code url} with https:// for the message. */
    record Minted(String url, String link, Instant expiresAt) {}

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
        String link = token != null && TOKEN.matcher(token).matches() ? token : "malformed";
        int byLink = opens.merge("link:" + link, 1, Integer::sum);
        int byAddress = opens.merge("address:" + (address == null ? "unknown" : address), 1, Integer::sum);
        return byLink <= OPENS_PER_LINK && byAddress <= OPENS_PER_ADDRESS;
    }

    /** Makes a link to the sale's receipt, in the caller's write transaction, with the business bound. */
    Minted mint(UUID saleId, UUID by) {
        StringBuilder token = new StringBuilder(26);
        for (int i = 0; i < 26; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Instant now = clock.now();
        Instant expires = now.plus(LIFETIME);
        jdbc.sql("""
                        INSERT INTO retail_receipt_links (id, tenant_id, sale_id, token_hash, created_by, created_at, expires_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        saleId,
                        hash(token.toString()),
                        by,
                        Timestamp.from(now),
                        Timestamp.from(expires))
                .update();
        String link = names.link(token.toString());
        return new Minted("https://" + link, link, expires);
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
