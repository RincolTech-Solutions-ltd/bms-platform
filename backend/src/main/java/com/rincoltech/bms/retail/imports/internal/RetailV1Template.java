package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.retail.catalogue.CatalogueHistory;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code retail_v1}, the canonical template of retail (issue #135, spec section 4.1 and Appendix A, ADR-027 decision 1): the
 * field list of the normalised export, {@code docs/specs/retail-pilot-data-dictionary.md} section 4, as ten entity sets in
 * the order the command imports them, the retail issue codes of spec section 3.4.1, the receivable decision, the forecast
 * and the books. Its loader is {@link RetailImporter#loader}: the code of {@code import-retail}, which the command runs on
 * its own export ({@link #exportMapping}) and the framework runs on a business's sheets read with a mapping, so both write
 * one way. The cash book files of section 5 are not part of it: they stay the command's own, a later version's to add.
 */
@Component
class RetailV1Template implements ImportTemplate {

    static final String KEY = "retail";

    static final int VERSION = 1;

    /** The entity sets in commit order, each fed by the export file of the same name. */
    static final List<String> SETS = List.of(
            "branches",
            "categories",
            "units",
            "products",
            "suppliers",
            "customers",
            "purchases",
            "sales",
            "usage",
            "balances");

    /** The sets whose rows the export knows by their own {@code source_ref}; the others by position. */
    static final Set<String> BY_REFERENCE = Set.of("purchases", "sales", "usage");

    /** The ledger type of the opening stock journal: the one the command's journals have always had. */
    static final String OPENING_TYPE = "retail.import_opening";

    static final String NAME_MISSING = "NAME_MISSING";
    static final String NEGATIVE_BALANCE = "NEGATIVE_BALANCE";
    static final String PRICE_DIFFERS_EXISTING = "PRICE_DIFFERS_EXISTING";
    static final String CREDIT_SALE_UNPAID = "CREDIT_SALE_UNPAID";

    /**
     * The receivable decision (spec section 3.8) of a source with credit sales and no payments. {@code open_from_list} is
     * not offered: the export holds no outstanding amount per buyer at the cutover moment, so a run that chose it could only
     * guess; a later version that adds that list offers it.
     */
    static final Policy RECEIVABLE = new Policy(
            Policy.RECEIVABLE,
            List.of(Policy.TREAT_AS_SETTLED, Policy.DEFER),
            Set.of(Policy.DEFER),
            "CREDIT_DECISION_MISSING",
            "sales",
            v -> "credit".equals(v.get("payment_method")));

    /** The catalogue's normal form of a product code (data dictionary rule 1), as the import and the forms apply it. */
    static final Normaliser RETAIL_CODE = new Normaliser(
            "retail_code",
            s -> s == null ? null : RetailCatalogue.normaliseCode(s).orElse(s.strip()));

    private static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(RetailCatalogue.MAX_AMOUNT_MINOR);

    /**
     * Found when a loader is asked for, not when the template is made: the importer runs the command on the framework,
     * whose registry holds this template, so a constructor dependency would be a cycle.
     */
    private final ObjectProvider<RetailImporter> importer;

    private final CatalogueHistory catalogue;
    private final Branches branches;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    RetailV1Template(
            ObjectProvider<RetailImporter> importer,
            CatalogueHistory catalogue,
            Branches branches,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.importer = importer;
        this.catalogue = catalogue;
        this.branches = branches;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public List<EntitySet> entitySets() {
        return ENTITY_SETS;
    }

    private static Field money(String name, boolean required) {
        Field f = required ? Field.required(name, FieldType.money()) : Field.optional(name, FieldType.money());
        return f.range(BigDecimal.ZERO, MAX_AMOUNT);
    }

    private static Field text(String name, int length, boolean required) {
        return required ? Field.required(name, FieldType.text(length)) : Field.optional(name, FieldType.text(length));
    }

    /** The source system's user name, kept with the reference: a person's, so never in a message. */
    private static Field sourceUser() {
        return text("source_user", 200, false).personalData();
    }

    /** Data dictionary section 4, field by field: required in bold there is required here. */
    static final List<EntitySet> ENTITY_SETS = List.of(
            new EntitySet(
                    "branches",
                    List.of(
                            Field.required("code", FieldType.text(10))
                                    .normalisedBy(Normaliser.UPPER)
                                    .asKey(),
                            text("name", 200, true))),
            new EntitySet("categories", List.of(text("name", 100, true).asKey())),
            new EntitySet("units", List.of(text("name", 30, true).asKey())),
            new EntitySet(
                    "products",
                    List.of(
                            Field.required("code", FieldType.text(40))
                                    .normalisedBy(RETAIL_CODE)
                                    .asKey(),
                            // The item's name: a row without one is NAME_MISSING, the retail code (spec 3.4.1).
                            text("description", 300, false),
                            text("category", 100, true),
                            text("unit", 30, true),
                            money("cost_minor", true),
                            money("sell_minor", true),
                            Field.optional("active", FieldType.bool()))),
            new EntitySet("suppliers", List.of(text("name", 200, true).asKey())),
            new EntitySet(
                    "customers",
                    List.of(
                            // Personal data, so not a key: no row refers to a buyer by it (a sale keeps its own text).
                            text("name", 200, true).personalData(),
                            // Kept as entered: a contact may hold several numbers or part of one (section 3).
                            text("contact", 100, false).personalData())),
            new EntitySet(
                    "purchases",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("kind", FieldType.enumOf("purchase", "adjustment", "return")),
                            money("unit_cost_minor", true),
                            money("unit_sell_minor", false),
                            // Branch code to quantity: a JSON object, or BRANCH=qty pairs separated by ; in a sheet.
                            text("qty_by_branch", 2000, true),
                            Field.required("purchased_on", FieldType.datetime()),
                            text("supplier", 200, false),
                            sourceUser())),
            new EntitySet(
                    "sales",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("qty", FieldType.qty()),
                            money("unit_price_minor", true),
                            money("unit_cost_minor", true),
                            Field.required("payment_method", FieldType.enumOf("cash", "credit")),
                            text("buyer", 200, false).personalData(),
                            text("buyer_contact", 100, false).personalData(),
                            Field.optional("due_date", FieldType.date()),
                            Field.required("sold_at", FieldType.datetime()),
                            sourceUser())),
            new EntitySet(
                    "usage",
                    List.of(
                            text("source_ref", 100, true),
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("kind", FieldType.enumOf("used", "damaged")),
                            text("reason", 300, true),
                            Field.required("qty", FieldType.qty()),
                            money("unit_cost_minor", true),
                            Field.required("reported_at", FieldType.datetime()),
                            sourceUser())),
            new EntitySet(
                    "balances",
                    List.of(
                            Field.required("branch", FieldType.ref("branches")),
                            Field.required("product_code", FieldType.ref("products")),
                            Field.required("qty", FieldType.qty()))));

    @Override
    public List<IssueCode> issueCodes() {
        return List.of(
                new IssueCode(NAME_MISSING, Severity.BLOCKING, "The item on line {line} has no name or description."),
                new IssueCode(
                        NEGATIVE_BALANCE,
                        Severity.WARNING,
                        "The source shows a negative quantity for this item at {branch}; it is imported as it is and"
                                + " listed for the first stock-take."),
                new IssueCode(
                        PRICE_DIFFERS_EXISTING,
                        Severity.INFO,
                        "The item already exists with other prices; its prices are left unchanged."),
                new IssueCode(
                        CREDIT_SALE_UNPAID,
                        Severity.INFO,
                        "Credit sale imported without payment history; the receivable decision applies."));
    }

    /**
     * The retail rules a row shows by itself, and the one that needs the business's items: a product without a name, an
     * existing product whose prices differ (issue #73: they are kept), a negative balance, and a credit sale.
     */
    @Override
    public List<RowCheck> rowChecks(String entitySet) {
        return switch (entitySet) {
            case "products" -> List.of(v -> productFindings(v, this::existingPrices));
            case "balances" -> List.of(RetailV1Template::balanceFindings);
            case "sales" -> List.of(RetailV1Template::saleFindings);
            default -> List.of();
        };
    }

    static List<RowFinding> productFindings(Map<String, Object> v, Function<String, Optional<long[]>> prices) {
        List<RowFinding> out = new ArrayList<>();
        if (!(v.get("description") instanceof String d) || d.isBlank()) {
            out.add(new RowFinding(NAME_MISSING, "description", Map.of()));
        }
        if (v.get("code") instanceof String code
                && v.get("cost_minor") instanceof Long cost
                && v.get("sell_minor") instanceof Long sell) {
            prices.apply(code)
                    .filter(p -> p[0] != cost || p[1] != sell)
                    .ifPresent(p -> out.add(new RowFinding(PRICE_DIFFERS_EXISTING, "cost_minor", Map.of())));
        }
        return out;
    }

    static List<RowFinding> balanceFindings(Map<String, Object> v) {
        if (v.get("qty") instanceof BigDecimal qty && qty.signum() < 0) {
            String branch = String.valueOf(v.get("branch"));
            return List.of(new RowFinding(NEGATIVE_BALANCE, "qty", Map.of("branch", branch)));
        }
        return List.of();
    }

    static List<RowFinding> saleFindings(Map<String, Object> v) {
        return "credit".equals(v.get("payment_method"))
                ? List.of(new RowFinding(CREDIT_SALE_UNPAID, "payment_method", Map.of()))
                : List.of();
    }

    /** The cost and sell price of the business's product with this code, compared ignoring case. */
    private Optional<long[]> existingPrices(String code) {
        return jdbc.sql("SELECT cost_minor, sell_minor FROM retail_products"
                        + " WHERE tenant_id = current_setting('app.tenant_id')::uuid AND lower(code) = lower(?)")
                .param(code)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .optional();
    }

    /** The compare form of a key: upper case with every space removed (ImportTemplate#existingKeys). */
    static String compare(String key) {
        return key == null ? null : key.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    @Override
    public Set<String> existingKeys(String entitySet, Set<String> keys) {
        Set<String> held = switch (entitySet) {
            case "branches" ->
                branches.all().stream().map(b -> compare(b.code())).collect(Collectors.toSet());
            case "products" ->
                catalogue.productsByCode().values().stream()
                        .map(p -> compare(p.code()))
                        .collect(Collectors.toSet());
            default -> Set.of();
        };
        return keys.stream().filter(held::contains).collect(Collectors.toSet());
    }

    @Override
    public List<Policy> policies() {
        return List.of(RECEIVABLE);
    }

    @Override
    public BatchWriter loader(String entitySet) {
        return importer.getObject().loader(entitySet);
    }

    // ---------------------------------------------------------------------------------------------
    // The forecast and the books (issues #132 to #134), by the rules the loader posts by.
    // ---------------------------------------------------------------------------------------------

    @Override
    public ImportForecast forecast() {
        return forecast(Map.of());
    }

    /**
     * The predicted stock and opening journals, by the loader's rules (data dictionary rules 4 and 5): each balances row is
     * the source's current quantity of an item at a branch, which the legacy balance makes the derived balance, whatever the
     * history before it; the first row of an item and branch counts (a second is refused). Each is valued at the item's
     * cost: an item the business already holds keeps its own prices (issue #73), a new one takes its first products row's.
     * The opening inventory journal of each batch is the change in what the run's items above zero are worth per branch,
     * so a negative balance is never in it (the negative-stock rule of #134). No receivable is opened under either option
     * of the decision. History rows put nothing in the forecast: the legacy balance absorbs them.
     */
    @Override
    public ImportForecast forecast(Map<String, String> policy) {
        Map<String, Long> held = readOnly.execute(s -> {
            Map<String, Long> costs = new HashMap<>();
            catalogue.productsByCode().values().forEach(p -> costs.put(compare(p.code()), p.costMinor()));
            return costs;
        });
        // JUSTIFICATION-A3: the re-review of 0bb283a (must-fix 3) asks the forecast to net what the loader nets.
        // What the command's imports' opening stock journals already hold per branch (its runs, V145's and the old
        // table's), as the loader nets them: the same export uploaded after the command predicts no journal, as the
        // loader posts none. A framework run's own journals are left out: the reconciliation forecasts a committed run
        // with this too, and must predict what that run posted.
        Map<String, Long> journalled = readOnly.execute(s -> {
            Map<String, Long> out = new HashMap<>();
            jdbc.sql("""
                            SELECT b.code, coalesce(sum(l.debit - l.credit), 0) AS held
                              FROM journal_entries e
                              JOIN journal_lines l ON l.entry_id = e.id
                              JOIN gl_accounts a ON a.id = l.account_id
                              JOIN branches b ON b.id = e.branch_id
                             WHERE e.tenant_id = current_setting('app.tenant_id')::uuid AND a.system_key = 'inventory'
                               AND e.id IN (SELECT r.target_id FROM import_refs r JOIN import_runs u ON u.id = r.run_id
                                             WHERE r.tenant_id = e.tenant_id AND r.target_type = 'ledger.journal_entry'
                                               AND r.source_key LIKE 'opening|inventory|%' AND u.origin <> 'framework'
                                            UNION
                                            SELECT o.target_id FROM retail_import_refs o
                                             WHERE o.tenant_id = e.tenant_id AND o.source_file = 'opening'
                                               AND NOT EXISTS (SELECT 1 FROM import_refs f
                                                                 JOIN import_runs fu ON fu.id = f.run_id
                                                                WHERE f.tenant_id = e.tenant_id
                                                                  AND f.target_id = o.target_id
                                                                  AND fu.origin = 'framework'))
                             GROUP BY b.code
                            """)
                    .query((rs, n) -> out.put(compare(rs.getString("code")), rs.getLong("held")))
                    .list();
            return out;
        });
        return new StockForecast(held == null ? Map.of() : held, journalled == null ? Map.of() : journalled);
    }

    /** The forecast of one run or one row: see {@link #forecast(Map)}. */
    static final class StockForecast implements ImportForecast {

        private final Map<String, Long> cost;
        private final Map<String, Map<String, BigDecimal>> qty = new TreeMap<>();
        private final Map<String, Long> journalled = new TreeMap<>();
        private final List<Journal> journals = new ArrayList<>();

        StockForecast(Map<String, Long> held) {
            this(held, Map.of());
        }

        /** {@code journalledBefore}: what earlier imports' opening stock journals hold per branch (compare form). */
        StockForecast(Map<String, Long> held, Map<String, Long> journalledBefore) {
            this.cost = new HashMap<>(held);
            this.journalled.putAll(journalledBefore);
        }

        @Override
        public void batch(int seq) {
            close();
        }

        @Override
        public void row(String entitySet, Map<String, Object> values) {
            switch (entitySet) {
                case "products" -> {
                    if (values.get("code") instanceof String code && values.get("cost_minor") instanceof Long c) {
                        cost.putIfAbsent(compare(code), c);
                    }
                }
                case "balances" -> {
                    if (values.get("branch") instanceof String branch
                            && values.get("product_code") instanceof String product
                            && values.get("qty") instanceof BigDecimal q) {
                        qty.computeIfAbsent(compare(branch), k -> new TreeMap<>())
                                .putIfAbsent(compare(product), q);
                    }
                }
                default -> {}
            }
        }

        private long value(String product, BigDecimal q) {
            return Quantities.value(q, cost.getOrDefault(product, 0L));
        }

        /** Closes a batch: its journal per branch is the change in what the run's items above zero are worth. */
        private void close() {
            qty.forEach((branch, items) -> {
                long now = 0;
                for (Map.Entry<String, BigDecimal> e : items.entrySet()) {
                    if (e.getValue().signum() > 0) {
                        now = Math.addExact(now, value(e.getKey(), e.getValue()));
                    }
                }
                long change = Math.subtractExact(now, journalled.getOrDefault(branch, 0L));
                if (change != 0) {
                    long amount = Math.abs(change);
                    journals.add(new Journal(branch, "inventory", amount, amount));
                }
                journalled.put(branch, now);
            });
        }

        @Override
        public Balances balances() {
            close();
            List<Stock> stock = new ArrayList<>();
            qty.forEach((branch, items) -> {
                BigDecimal onHand = BigDecimal.ZERO;
                long worth = 0;
                for (Map.Entry<String, BigDecimal> e : items.entrySet()) {
                    onHand = onHand.add(e.getValue());
                    worth = Math.addExact(worth, value(e.getKey(), e.getValue()));
                }
                stock.add(new Stock(branch, onHand, worth));
            });
            return new Balances(stock, List.of(), List.of(), List.of(), List.copyOf(journals));
        }

        @Override
        public List<ProductStock> productStock() {
            List<ProductStock> out = new ArrayList<>();
            qty.forEach((branch, items) -> items.forEach((product, q) ->
                    out.add(new ProductStock(branch, product, q, BigDecimal.valueOf(value(product, q))))));
            return out;
        }
    }

    /**
     * The books (issue #134): the stock the run's own records put on hand, per branch and item, from the stock ledger: the
     * movements of the purchases, sales and usage reports the run registered, and the stock movements it registered
     * (adjustments, returns and legacy balances), each item valued at its current cost as the opening journal values it.
     * Stock held before the import or moved after it is not the run's.
     */
    @Override
    public ImportBooks books() {
        return new ImportBooks() {
            @Override
            public List<ImportForecast.ProductStock> stock(Scope scope) {
                List<UUID> movements = scope.of("retail.stock_movement");
                List<UUID> documents = new ArrayList<>(scope.of("retail.purchase"));
                documents.addAll(scope.of("retail.sale"));
                documents.addAll(scope.of("retail.usage"));
                if (movements.isEmpty() && documents.isEmpty()) {
                    return List.of();
                }
                return jdbc.sql("""
                                SELECT b.code, p.code, sum(m.qty), p.cost_minor
                                  FROM retail_stock_movements m
                                  JOIN branches b ON b.id = m.branch_id
                                  JOIN retail_products p ON p.id = m.product_id
                                 WHERE m.id = ANY(?) OR m.source_id = ANY(?)
                                 GROUP BY b.code, p.code, p.cost_minor
                                 ORDER BY b.code, p.code
                                """)
                        .params(movements.toArray(UUID[]::new), documents.toArray(UUID[]::new))
                        .query((rs, n) -> {
                            BigDecimal q = rs.getBigDecimal(3);
                            return new ImportForecast.ProductStock(
                                    compare(rs.getString(1)),
                                    compare(rs.getString(2)),
                                    q,
                                    BigDecimal.valueOf(Quantities.value(q, rs.getLong(4))));
                        })
                        .list();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // The mapping of the normalised export (the import-retail command's).
    // ---------------------------------------------------------------------------------------------

    /**
     * The mapping of the normalised export onto {@code retail_v1}: each JSON Lines file of the export directory is the
     * source of the entity set of its name, every field to itself, dates in ISO 8601 in the business's zone. The history
     * files are known by their {@code source_ref}, the others by position. The command records it on its batches; a JSON
     * Lines upload of an export with it keys its history rows as the command does, so the two recognise each other's rows.
     */
    static Map<String, Object> exportMapping(ZoneId zone) {
        Map<String, Object> sources = new LinkedHashMap<>();
        for (EntitySet set : ENTITY_SETS) {
            Map<String, Object> columns = new LinkedHashMap<>();
            set.fields().forEach(f -> columns.put(f.name(), Map.of("field", f.name())));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("entity_set", set.key());
            source.put("file", set.key() + ".jsonl");
            source.put("header_row", "auto");
            source.put("columns", columns);
            source.put(
                    "source_ref",
                    BY_REFERENCE.contains(set.key())
                            ? Map.of("rule", "column", "column", "source_ref")
                            : Map.of("rule", "row_hash"));
            sources.put(set.key(), source);
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("template", KEY);
        mapping.put("template_version", VERSION);
        mapping.put("date_convention", "iso");
        mapping.put("time_zone", zone.getId());
        mapping.put("currency", "UGX");
        mapping.put("sources", sources);
        return mapping;
    }
}
