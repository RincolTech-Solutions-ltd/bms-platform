package com.rincoltech.bms.retail.catalogue;

import com.rincoltech.bms.kernel.Principal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The catalogue's public API for the other retail modules: a product's current prices, read in the
 * caller's transaction. A sale snapshots {@code costMinor} and {@code sellMinor} from here
 * (ADR-020 decisions 5 and 6).
 */
public interface RetailCatalogue {

    /**
     * The largest amount in minor units any retail request may carry: 10^13 (review F7). Bean
     * Validation refuses more with 422, and the database CHECKs of migration V14 match it.
     */
    long MAX_AMOUNT_MINOR = 10_000_000_000_000L;

    /** Issue #624 (ADR-099): what a pack is for, as stored in retail_product_units.purpose and sent by the API. */
    String PACK_BUY = "buy";

    String PACK_SELL = "sell";
    String PACK_BOTH = "both";
    /** The three purposes, for request validation. */
    String PACK_PURPOSES = PACK_BUY + "|" + PACK_SELL + "|" + PACK_BOTH;

    /** The key of the units-of-measure capability in the tenant's retail.capabilities settings group (ADR-033). */
    String UNITS_CAPABILITY = "units";

    /**
     * Issue #689 (ADR-106): the names that are a package, never an item's smallest unit, in any case, one or many, with or
     * without a size ("Box", "cartons", "Pack of 6", "Set of 4"), and their short codes ("Ctn", "pkts", "Bx", "doz"). The
     * one list: every unit carries {@code is_package} from it, which the PWA reads, so the screens and the server cannot
     * disagree.
     */
    Pattern PACKAGE_WORDS = Pattern.compile(
            "((box|carton|dozen|pack|packet|bag|sack|crate|case|tray|bundle|bale)(e?s)?|(ctn|pkt)s?|bx|doz|dz)( of \\d+.*)?"
                    + "|sets? of \\d+.*",
            Pattern.CASE_INSENSITIVE);

    /** Every short code of {@link #PACKAGE_WORDS} and its word, as {@code UNIT_WORDS} in units-state.ts has them. */
    java.util.Map<String, String> SHORT_CODE_WORDS = java.util.Map.of(
            "ctn", "carton", "ctns", "carton", "pkt", "packet", "pkts", "packet", "bx", "box", "doz", "dozen", "dz",
            "dozen");

    /** True when the unit's name is a package word of {@link #PACKAGE_WORDS}. */
    static boolean isPackageWord(String unitName) {
        return unitName != null && PACKAGE_WORDS.matcher(unitName.trim()).matches();
    }

    /**
     * Issue #689: the refusal of a package as a new item's unit, as the PWA words it ({@code packageAsUnit} in
     * new-item-packs.tsx): "A box is a package", or "Cartons is a package" for a unit named in the plural. A short code reads
     * as its word, as {@code unitWord} in units-state.ts maps it (review of #692): "Ctn" and "Ctns" are "a carton", "Bx" is "a
     * box", "Doz" and "Dz" are "a dozen". fixtures/retail/package-refusals-689.json holds the sentences both sides must give.
     */
    static String packageAsUnit(String unitName) {
        String name = unitName.trim().toLowerCase(java.util.Locale.ROOT);
        String word = SHORT_CODE_WORDS.getOrDefault(name, name);
        String head = word.split(" ")[0];
        String named = head.endsWith("s") && !head.endsWith("ss")
                ? Character.toUpperCase(word.charAt(0)) + word.substring(1)
                : "A " + word;
        return named + " is a package. Choose the smallest unit, like piece, then add the " + word + " below.";
    }

    /**
     * Issue #650 (ADR-103): the standard pack of the product aliased {@code product}, the one its stock is shown and counted
     * in, as a subquery of {@code pack_id}, {@code pack_unit} and {@code pack_factor} (one row or none). Of its active packs
     * holding more than one base unit: first a pack used for selling or both (ADR-099) whose unit has a usual pack (ADR-100) of
     * the item's own base unit and for selling or both (issue #660: a box of 50 metres is never promoted on an item counted in
     * pieces; a null size or purpose never promotes); else a pack used for selling or both; else any. Within each, the
     * biggest, and on a tie the unit id. The stock lists and an item's packs (its {@code standard} flag, which the PWA reads)
     * both use it, so there is one rule.
     */
    static String standardPackSql(String product) {
        return """
                SELECT sk.id AS pack_id, su.name AS pack_unit, sk.factor AS pack_factor
                  FROM retail_product_units sk JOIN retail_units su ON su.id = sk.unit_id
                 WHERE sk.tenant_id = %1$s.tenant_id AND sk.product_id = %1$s.id AND sk.active AND sk.factor > 1
                 ORDER BY CASE WHEN sk.purpose <> 'buy' AND su.pack_size IS NOT NULL
                                    AND su.pack_of_unit_id = %1$s.unit_id AND su.pack_purpose IN ('sell', 'both') THEN 0
                               WHEN sk.purpose <> 'buy' THEN 1 ELSE 2 END,
                          sk.factor DESC, sk.unit_id
                 LIMIT 1
                """.formatted(product);
    }

    /**
     * Issue #699 (ADR-107): every level of the ladder of the product aliased {@code product}, biggest first, as a subquery of
     * {@code level_units} and {@code level_factors} (two arrays in the same order, null when there is none): its active packs
     * holding a whole number of more than one base unit. Stock reads "2 Cartons, 3 Boxes and 5 Pieces" from them and Count
     * stock takes a box for each.
     */
    static String packLevelsSql(String product) {
        return """
                SELECT array_agg(lu.name ORDER BY lk.factor DESC, lk.unit_id) AS level_units,
                       array_agg(lk.factor ORDER BY lk.factor DESC, lk.unit_id) AS level_factors
                  FROM retail_product_units lk JOIN retail_units lu ON lu.id = lk.unit_id
                 WHERE lk.tenant_id = %1$s.tenant_id AND lk.product_id = %1$s.id AND lk.active AND lk.factor > 1
                   AND lk.factor = trunc(lk.factor)
                """.formatted(product);
    }

    Optional<ProductSnapshot> find(UUID productId);

    /** What every selling price in a response says when it is left out (ADR-055). */
    String PRICE_ONLY =
            "Present only to whoever sells, oversees selling, sets prices or reads cost (retail.sale.create, retail.sale.all_sellers, retail.price.edit or retail.profit.read): the storekeeper sees no prices (ADR-055)";

    /**
     * ADR-055 (the owner's decision of 21 October 2026): selling prices are shown to whoever sells, sets prices or reads cost.
     * A storekeeper, who holds none of those, sees no price in any response. A supervisor (who may see every seller's sales,
     * {@code retail.sale.all_sellers}) oversees selling, so sees prices too, never cost. Prices are tenant-wide, so a
     * permission held at any branch counts.
     */
    static boolean maySeePrices(Principal principal) {
        return principal.hasPermission("retail.sale.create")
                || principal.hasPermission("retail.price.edit")
                || principal.hasPermission("retail.sale.all_sellers")
                || principal.hasPermission("retail.profit.read");
    }

    /**
     * ADR-033, issue #194: the pack of a product in the given unit (a carton of twelve pieces), read
     * in the caller's transaction. Empty when the product has no pack of that unit. Whether packs
     * may be used at all is the {@code units} capability, which the caller checks.
     */
    Optional<PackUnit> packUnit(UUID productId, UUID unitId);

    /**
     * ADR-067 decision 11: the product's sell price in force at {@code at}, from its price history (the last change at or
     * before it; before the first change, the price that change replaced), or the current price when the history says
     * nothing. Read in the caller's transaction.
     */
    long sellMinorAt(UUID productId, java.time.Instant at);

    /**
     * Takes the product's row lock for the rest of the caller's transaction. A caller that changes
     * several products locks them in id order first, so concurrent events cannot deadlock.
     */
    Optional<ProductSnapshot> lock(UUID productId);

    /**
     * FR-RET-06, ADR-020 decision 5: a restock line's prices become the product's current prices in
     * the caller's transaction, with a {@code purchase} history row naming the purchase and an audit
     * row. A null sell price keeps the current one. Nothing is written when neither price changes.
     *
     * @return the product as it is now
     */
    ProductSnapshot applyPurchasePrices(UUID productId, long costMinor, Long sellMinor, UUID purchaseId);

    /**
     * ADR-033, issue #199: a landed cost or a supplier price credit moves the product's cost to {@code costMinor} in the
     * caller's transaction, with a {@code purchase} history row that names the document ({@code sourceId}) and says why, and
     * an audit row. The selling price is kept. The caller has decided that the cost may change (the cost update guard).
     *
     * @return the product as it is now
     */
    ProductSnapshot applyCostChange(UUID productId, long costMinor, UUID sourceId, String reason);

    /**
     * ADR-033, issue #199: records, without changing any price, that a landed cost or supplier credit did not move the
     * product's cost because a newer cost event had set it since (a {@code purchase} history row with the same cost on both
     * sides and the reason). Never overwrites a newer cost.
     */
    void noteCostNotApplied(UUID productId, UUID sourceId, String reason);

    /**
     * Issue #351, ADR-044: a voided restock gives back the prices it set, in the caller's transaction, but only while it is
     * still the product's latest price event (no later restock, manual change, landed cost or credit has moved them). The
     * cost and the selling price go back to what they were before the restock's first change to this product, with a
     * {@code purchase} history row that names the restock and says why. When a later event moved them, nothing changes and
     * a history row with the same prices on both sides says the restock was voided without moving them. The same when the
     * caller reports {@code laterPurchase}: a restock or receipt that still stands put stock in afterwards, and may have paid
     * those prices without writing a history row (the prices did not change then). Nothing is written when the restock
     * changed no price of this product.
     *
     * @return true when the prices went back
     */
    boolean revertPurchasePrices(UUID productId, UUID purchaseId, String reason, boolean laterPurchase);

    /**
     * D23, issue #554: a delivery whose line ticked "Use 30 from now on" makes {@code factor} the usual size of the
     * product's pack in {@code unitId}, in the caller's transaction, with an audit row naming the delivery. The caller
     * holds the product's row lock (ADR-079: product rows before balance rows), which a pack edit also takes, so the two
     * never interleave. Lines already made keep the size they used. Nothing is written when the size is already the usual
     * one, nor (issue #624) when the pack has its own selling price and the caller may not change prices: the delivery
     * keeps its own size and the pack's usual size stays, with a {@code retail.product_unit.size_kept} audit row.
     *
     * @return false when the usual size was left as it was because the pack has its own price (issue #624)
     */
    boolean useBuyingSize(UUID productId, UUID unitId, BigDecimal factor, String sourceType, UUID sourceId);

    /**
     * The one normal form of a product code, for the API and the importer alike (review F9):
     * {@code strip()} and the no-break spaces it keeps are removed at the ends (a no-break space pasted from a
     * spreadsheet), then NFKC folds compatibility forms (full-width letters and digits). A code that
     * is then empty, or holds a control or format character or any space other than U+0020, is
     * refused. The database CHECK of migration V14 enforces the same rule.
     *
     * @return the normalised code, or empty when it is not acceptable
     */
    static Optional<String> normaliseCode(String raw) {
        if (raw == null || !acceptable(stripSpaces(raw))) {
            return Optional.empty();
        }
        String code = stripSpaces(Normalizer.normalize(stripSpaces(raw), Normalizer.Form.NFKC));
        return acceptable(code) ? Optional.of(code) : Optional.empty();
    }

    /** {@code strip()} plus the no-break spaces it leaves (U+00A0, U+2007, U+202F). */
    private static String stripSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean acceptable(String code) {
        return !code.isEmpty()
                && code.codePoints()
                        .allMatch(c -> c == ' '
                                || !(Character.isISOControl(c)
                                        || Character.getType(c) == Character.FORMAT
                                        || Character.isSpaceChar(c)
                                        || Character.isWhitespace(c)));
    }

    /**
     * Issue #632, Quick add: makes an item in the caller's transaction through the same rules as Items (the code from the
     * category, ADR-081; the name check, 422 {@code duplicate_product_name}). A null category files it under General and a
     * null unit counts it in the shop's piece unit (Piece, or pc when the business uses that); either is made when the
     * business has none of that name, and a switched-off one is refused as on Items. Issue #695: its packages are made with
     * it, as a new item's packs on Items (ADR-106, ADR-107), so a refused package saves nothing.
     */
    NewItem createItem(
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            String duplicateReason,
            List<NewPack> packs);

    /** The item Quick add made, and whether it also made the General category or the Piece unit. */
    record NewItem(ProductSnapshot product, boolean categoryMade, boolean unitMade) {}

    /** Issue #695: a package of a new Quick add item, with the fields and rules of a new item's pack on Items. */
    // JUSTIFICATION-A3: the stock module may not see the catalogue's internal PackRequest, so its public API carries
    // the pack.
    @Schema(name = "RetailQuickAddPack", description = "As RetailPackRequest: a size, or what it holds and how many")
    // Review of #716: the constraints of RetailPackRequest, checked on the request as POST /retail/products checks
    // them, holds_qty within PackLadder.MIN_HOLDS and MAX_HOLDS.
    record NewPack(
            @NotNull UUID unitId,

            @DecimalMin("1") @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "24")
            BigDecimal factor,

            @jakarta.validation.constraints.Pattern(regexp = PACK_PURPOSES)
            String purpose,

            @PositiveOrZero @Max(MAX_AMOUNT_MINOR) Long sellMinor,
            UUID holdsUnitId,
            @Min(2) @Max(1_000_000) Integer holdsQty,
            Boolean usualBuy) {}

    /**
     * A pack size: {@code factor} base units in one pack of {@code unit}. Issue #624: {@code purpose} says whether it is
     * bought, sold or both, and {@code sellMinor} is its own selling price per pack, null for the piece price times the size.
     */
    record PackUnit(UUID unitId, String unit, BigDecimal factor, boolean active, String purpose, Long sellMinor) {

        /** True when the pack may be used on a sale line (a walk-in sale, a scan, a quotation or an order). */
        public boolean sells() {
            return !PACK_BUY.equals(purpose);
        }

        /** True when the pack may be used on Buy stock, a purchase order or goods received. */
        public boolean buys() {
            return !PACK_SELL.equals(purpose);
        }
    }

    record ProductSnapshot(
            UUID id,
            String code,
            String description,
            UUID unitId,
            String unit,
            long costMinor,
            long sellMinor,
            String currency,
            boolean active) {}
}
