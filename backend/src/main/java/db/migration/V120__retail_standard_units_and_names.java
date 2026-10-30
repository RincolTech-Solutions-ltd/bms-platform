package db.migration;

import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V120: one unit for many spellings, and the one display style of names (issue #698, ADR-109), directly after V119 (#713).
 *
 * <p>A Java migration so it uses the very rules the application saves with ({@code CatalogueNames.unitKey} and
 * {@code CatalogueNames.display}), read from the one list of unit words. Per business, in one transaction:
 *
 * <ol>
 *   <li>Units whose names share a key ("Piece", "pcs", "PC") are merged into one: the switched-on one first, then the one
 *       named as the unit's word, then the oldest. A unit is merged only when nothing but the catalogue uses it (items, items
 *       with sizes, packages, a unit's usual pack: every other foreign key to {@code retail_units}, and every foreign key to
 *       a package, is found from the catalog and checked) and moving those references clashes with nothing: no item would
 *       hold both units, no usual pack would count itself, and no two items of the same name would end up in one unit. Its
 *       references are moved to the kept unit, it is switched off and kept, never deleted, and {@code retail.unit.merged} is
 *       audited. A unit that cannot be merged is left exactly as it is and audited as {@code retail.unit.merge_skipped} with
 *       the reason.
 *   <li>Category, item and item-with-sizes names take the display style where that changes only their case, audited as
 *       {@code retail.category.updated}, {@code retail.product.updated} and {@code retail.product_family.updated} with
 *       {@code source} {@code V120}. A name with extra spaces keeps them until it is next saved.
 * </ol>
 *
 * <p>Expand only: rows are updated, none deleted, and no table, column, constraint, grant or policy changes. The audit rows are
 * written as {@code system}, with nobody bound. Row security as in V109: {@code bms_owner} has BYPASSRLS; an owner without it
 * drops FORCE ROW LEVEL SECURITY on the tables read here for this transaction only, which takes ACCESS EXCLUSIVE on them until
 * the commit, so {@code lock_timeout} is five seconds and the migration fails and is retried rather than queueing requests.
 */
public class V120__retail_standard_units_and_names extends BaseJavaMigration {

    private static final String SOURCE = "V120";

    /** The references the merge moves: the catalogue's own. Every other reference to a unit makes it used. */
    private static final Set<String> CATALOGUE = Set.of(
            "retail_products.unit_id",
            "retail_product_families.unit_id",
            "retail_product_units.unit_id",
            "retail_units.pack_of_unit_id");

    private record UnitRow(UUID id, UUID tenant, String name, boolean active, Timestamp created, UUID packOf) {}

    private record Ref(String table, String column) {}

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL lock_timeout = '5s'");
        }
        // The references to a unit and to a package, from the catalog, so a table added later is never missed.
        List<Ref> unitRefs = refs(c, "retail_units", "id");
        List<Ref> packRefs = refs(c, "retail_product_units", "unit_id");
        Set<String> tables = new LinkedHashSet<>(List.of(
                "retail_units",
                "retail_products",
                "retail_product_families",
                "retail_product_units",
                "retail_categories",
                "audit_log"));
        unitRefs.forEach(r -> tables.add(r.table()));
        packRefs.forEach(r -> tables.add(r.table()));
        boolean bypass;
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            rs.next();
            bypass = rs.getBoolean(1);
        }
        forceRowSecurity(c, tables, bypass, false);
        try {
            mergeUnits(c, unitRefs, packRefs);
            restyle(c, "retail_categories", "name", "retail.category.updated", "retail.category", "name");
            restyle(c, "retail_products", "description", "retail.product.updated", "retail.product", "description");
            restyle(
                    c,
                    "retail_product_families",
                    "name",
                    "retail.product_family.updated",
                    "retail.product_family",
                    "name");
        } finally {
            forceRowSecurity(c, tables, bypass, true);
        }
    }

    private static void forceRowSecurity(Connection c, Set<String> tables, boolean bypass, boolean on)
            throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL row_security = " + (on ? "on" : "off"));
            if (bypass) {
                return;
            }
            for (String t : tables) {
                s.execute("ALTER TABLE " + t + (on ? " FORCE" : " NO FORCE") + " ROW LEVEL SECURITY");
            }
        }
    }

    /** The columns of other tables that point at {@code table}'s {@code column} through a foreign key. */
    private static List<Ref> refs(Connection c, String table, String column) throws SQLException {
        List<Ref> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("""
                SELECT c.conrelid::regclass::text, a.attname
                  FROM pg_constraint c
                 CROSS JOIN LATERAL unnest(c.conkey, c.confkey) AS k(own, ref)
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.own
                  JOIN pg_attribute r ON r.attrelid = c.confrelid AND r.attnum = k.ref
                 WHERE c.contype = 'f' AND c.confrelid = ?::regclass AND r.attname = ?
                 ORDER BY 1, 2
                """)) {
            p.setString(1, table);
            p.setString(2, column);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new Ref(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    private static void mergeUnits(Connection c, List<Ref> unitRefs, List<Ref> packRefs) throws SQLException {
        Map<String, List<UnitRow>> groups = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT id, tenant_id, name, active, created_at, pack_of_unit_id
                          FROM retail_units ORDER BY tenant_id, created_at, id
                        """)) {
            while (rs.next()) {
                UnitRow u = new UnitRow(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getBoolean(4),
                        rs.getTimestamp(5),
                        rs.getObject(6, UUID.class));
                groups.computeIfAbsent(u.tenant() + ":" + CatalogueNames.unitKey(u.name()), k -> new ArrayList<>())
                        .add(u);
            }
        }
        for (List<UnitRow> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            String key = CatalogueNames.unitKey(group.getFirst().name());
            group.sort(Comparator.comparing((UnitRow u) -> !u.active())
                    .thenComparing(u -> !u.name().trim().equalsIgnoreCase(key))
                    .thenComparing(UnitRow::created)
                    .thenComparing(UnitRow::id));
            UnitRow kept = group.getFirst();
            for (UnitRow merged : group.subList(1, group.size())) {
                String why = whyNot(c, merged, kept, unitRefs, packRefs);
                if (why != null) {
                    audit(
                            c,
                            merged.tenant(),
                            "retail.unit.merge_skipped",
                            "retail.unit",
                            merged.id(),
                            "{\"name\": " + json(merged.name()) + "}",
                            "{\"same_as_id\": " + json(kept.id().toString()) + ", \"same_as_name\": "
                                    + json(kept.name()) + ", \"reason\": " + json(why) + ", \"source\": \""
                                    + SOURCE + "\"}");
                    continue;
                }
                int items = move(c, "retail_products", "unit_id", merged, kept, true);
                int families = move(c, "retail_product_families", "unit_id", merged, kept, true);
                int packs = move(c, "retail_product_units", "unit_id", merged, kept, true);
                move(c, "retail_product_units", "holds_unit_id", merged, kept, false);
                int usualPacks = move(c, "retail_units", "pack_of_unit_id", merged, kept, true);
                update(
                        c,
                        "UPDATE retail_units SET active = false, updated_at = now(), version = version + 1"
                                + " WHERE tenant_id = ? AND id = ?",
                        merged.tenant(),
                        merged.id());
                audit(
                        c,
                        merged.tenant(),
                        "retail.unit.merged",
                        "retail.unit",
                        merged.id(),
                        "{\"name\": " + json(merged.name()) + ", \"active\": " + merged.active() + "}",
                        "{\"active\": false, \"merged_into_id\": " + json(kept.id().toString())
                                + ", \"merged_into_name\": " + json(kept.name()) + ", \"items\": " + items
                                + ", \"items_with_sizes\": " + families + ", \"packages\": " + packs
                                + ", \"usual_packs\": " + usualPacks + ", \"source\": \"" + SOURCE + "\"}");
            }
        }
    }

    /** Why the unit cannot be merged into the kept one, or null when it can. */
    private static String whyNot(Connection c, UnitRow merged, UnitRow kept, List<Ref> unitRefs, List<Ref> packRefs)
            throws SQLException {
        for (Ref r : unitRefs) {
            if (!CATALOGUE.contains(r.table() + "." + r.column())
                    && exists(
                            c,
                            "SELECT 1 FROM " + r.table() + " WHERE tenant_id = ? AND " + r.column() + " = ?",
                            merged.tenant(),
                            merged.id())) {
                return "used by " + r.table();
            }
        }
        for (Ref r : packRefs) {
            if (exists(
                    c,
                    "SELECT 1 FROM " + r.table() + " WHERE tenant_id = ? AND " + r.column() + " = ?",
                    merged.tenant(),
                    merged.id())) {
                return "a package of it is used by " + r.table();
            }
        }
        if (merged.id().equals(kept.packOf()) || kept.id().equals(merged.packOf())) {
            return "one is the usual pack of the other";
        }
        if (exists(
                c,
                """
                SELECT 1 FROM retail_products p
                 WHERE p.tenant_id = ? AND (
                       EXISTS (SELECT 1 FROM retail_product_units a JOIN retail_product_units b ON b.product_id = a.product_id
                                WHERE a.product_id = p.id AND a.unit_id = ? AND b.unit_id = ?)
                    OR (p.unit_id = ? AND EXISTS (SELECT 1 FROM retail_product_units k WHERE k.product_id = p.id AND k.unit_id = ?))
                    OR (p.unit_id = ? AND EXISTS (SELECT 1 FROM retail_product_units k WHERE k.product_id = p.id AND k.unit_id = ?)))
                """,
                merged.tenant(),
                merged.id(),
                kept.id(),
                merged.id(),
                kept.id(),
                kept.id(),
                merged.id())) {
            return "an item has both";
        }
        if (exists(
                c,
                """
                SELECT 1 FROM retail_products a JOIN retail_products b ON b.tenant_id = a.tenant_id AND b.name_key = a.name_key
                 WHERE a.tenant_id = ? AND a.unit_id = ? AND b.unit_id = ?
                """,
                merged.tenant(),
                merged.id(),
                kept.id())) {
            return "an item of the same name is in both";
        }
        return null;
    }

    /** Moves one reference from the merged unit to the kept one; the number of rows moved. */
    private static int move(Connection c, String table, String column, UnitRow merged, UnitRow kept, boolean bump)
            throws SQLException {
        String touch = bump ? ", updated_at = now(), version = version + 1" : "";
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE " + table + " SET " + column + " = ?" + touch + " WHERE tenant_id = ? AND " + column + " = ?")) {
            p.setObject(1, kept.id());
            p.setObject(2, merged.tenant());
            p.setObject(3, merged.id());
            return p.executeUpdate();
        }
    }

    /** A name in the display style where only its case changes; each change audited with the name before and after. */
    private static void restyle(Connection c, String table, String column, String action, String entity, String field)
            throws SQLException {
        record Change(UUID id, UUID tenant, String before, String after) {}
        List<Change> changes = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT id, tenant_id, " + column + " FROM " + table)) {
            while (rs.next()) {
                String before = rs.getString(3);
                String after = CatalogueNames.display(before);
                if (!after.equals(before) && after.equalsIgnoreCase(before)) {
                    changes.add(new Change(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), before, after));
                }
            }
        }
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE " + table + " SET " + column + " = ? WHERE tenant_id = ? AND id = ?")) {
            for (Change ch : changes) {
                p.setString(1, ch.after());
                p.setObject(2, ch.tenant());
                p.setObject(3, ch.id());
                p.addBatch();
            }
            p.executeBatch();
        }
        for (Change ch : changes) {
            audit(
                    c,
                    ch.tenant(),
                    action,
                    entity,
                    ch.id(),
                    "{" + json(field) + ": " + json(ch.before()) + "}",
                    "{" + json(field) + ": " + json(ch.after()) + ", \"source\": \"" + SOURCE + "\"}");
        }
    }

    private static void audit(
            Connection c, UUID tenant, String action, String entity, UUID id, String before, String after)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
                VALUES (?, ?, 'system', ?, ?, ?, jsonb_build_object('before', ?::jsonb, 'after', ?::jsonb))
                """)) {
            p.setObject(1, UUID.randomUUID());
            p.setObject(2, tenant);
            p.setString(3, action);
            p.setString(4, entity);
            p.setObject(5, id);
            p.setString(6, before);
            p.setString(7, after);
            p.executeUpdate();
        }
    }

    private static boolean exists(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql + " LIMIT 1")) {
            for (int i = 0; i < params.length; i++) {
                p.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = p.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                p.setObject(i + 1, params[i]);
            }
            p.executeUpdate();
        }
    }

    /** A JSON string literal. */
    private static String json(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
