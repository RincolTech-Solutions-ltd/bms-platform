package db.migration;

import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V120: the one display style of names (issue #698, ADR-109, D86 = A), directly after V119 (#713).
 *
 * <p>A Java migration so it uses the very rule the application saves with ({@code CatalogueNames.display}). Category, item and
 * item-with-sizes names take the display style where that changes only their case, with {@code updated_at} and
 * {@code version} moved so every client reads them again, audited as {@code retail.category.updated},
 * {@code retail.product.updated} and {@code retail.product_family.updated} with {@code source} {@code V120}. A name with extra
 * spaces keeps them until it is next saved.
 *
 * <p>Units are not touched: duplicates already stored ("pc" beside "Piece") are left as they are, for an owner action to join
 * later (owner decision on #715); only new duplicates are refused, by the application.
 *
 * <p>Expand only: rows are updated, none deleted, and no table, column, constraint, grant or policy changes. The audit rows are
 * written as {@code system}, with nobody bound. Row security as in V109: {@code bms_owner} has BYPASSRLS; an owner without it
 * drops FORCE ROW LEVEL SECURITY, for this transaction only, on the tables written here that force it, and restores exactly
 * those. That takes ACCESS EXCLUSIVE on them until the commit, so {@code lock_timeout} is five seconds and the migration fails
 * and is retried rather than queueing requests.
 */
public class V120__retail_standard_units_and_names extends BaseJavaMigration {

    private static final String SOURCE = "V120";

    private static final Set<String> TABLES =
            Set.of("retail_categories", "retail_products", "retail_product_families", "audit_log");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL lock_timeout = '5s'");
        }
        boolean bypass;
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            rs.next();
            bypass = rs.getBoolean(1);
        }
        // Only the tables that force row security now drop it, and only those get it back.
        Set<String> forced = bypass ? Set.of() : forced(c);
        forceRowSecurity(c, forced, false);
        restyle(c, "retail_categories", "name", "retail.category.updated", "retail.category", "name");
        restyle(c, "retail_products", "description", "retail.product.updated", "retail.product", "description");
        restyle(
                c,
                "retail_product_families",
                "name",
                "retail.product_family.updated",
                "retail.product_family",
                "name");
        // Restored only after the work succeeded: on a failure the rollback restores it anyway, and the first error
        // stays the one reported (review of #715).
        forceRowSecurity(c, forced, true);
    }

    /** The tables written here that force row level security now. */
    private static Set<String> forced(Connection c) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement p =
                c.prepareStatement("SELECT relforcerowsecurity FROM pg_class WHERE oid = ?::regclass")) {
            for (String t : TABLES) {
                p.setString(1, t);
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    private static void forceRowSecurity(Connection c, Set<String> tables, boolean on) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL row_security = " + (on ? "on" : "off"));
            for (String t : tables) {
                s.execute("ALTER TABLE " + t + (on ? " FORCE" : " NO FORCE") + " ROW LEVEL SECURITY");
            }
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
        try (PreparedStatement p = c.prepareStatement("UPDATE " + table + " SET " + column
                + " = ?, updated_at = now(), version = version + 1 WHERE tenant_id = ? AND id = ?")) {
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
