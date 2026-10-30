package db.migration;

import com.rincoltech.bms.retail.catalogue.StandardUnits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V121: the standard units every shop gets (issue #709, ADR-110, D98), after V120 (#715, ADR-109), which holds the number
 * above V119 (#713).
 *
 * <p>A Java migration so it matches a shop's own spellings by the very rule the application uses ({@code StandardUnits.key}):
 * every retail business that is not closed gets each standard single unit and package it does not have under any spelling
 * (a shop with Pcs gets no Piece, one with Doz no Dozen). Nothing a shop made is touched or renamed, and two units of its own
 * that are the same unit are left as they are. A unit is inserted only: {@code ON CONFLICT DO NOTHING} on the unique name
 * index (tenant, lower(name)) keeps a re-run harmless. {@code created_by} stays null, as for every unit the system made, and
 * each one added is audited as {@code retail.unit.created} by {@code system} with {@code source} {@code V121}.
 *
 * <p>Expand only: rows are inserted, none updated or deleted, and no table, column, constraint, grant or policy changes. Row
 * security as in V109 and V120: {@code bms_owner} has BYPASSRLS; an owner without it drops FORCE ROW LEVEL SECURITY, for this
 * transaction only, on the tables read and written here that force it, and restores exactly those. That takes ACCESS
 * EXCLUSIVE on them until the commit, so {@code lock_timeout} is five seconds and the migration fails and is retried rather
 * than queueing requests.
 *
 * <p>A Java migration has no checksum, and {@code StandardUnits.key} reads the shared spellings of
 * {@code unit-words.json} at run time (review of #722). A spelling added there later can only make two names count as one
 * unit, so a database migrated later skips at most a unit the shop already has under that spelling, never adds a second one.
 * {@code StandardUnitsTest} pins the keys of the seventeen standard names.
 */
public class V121__retail_standard_units extends BaseJavaMigration {

    private static final String SOURCE = "V121";

    private static final Set<String> TABLES = Set.of("retail_units", "tenant_modules", "tenants", "audit_log");

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
        Set<String> forced = bypass ? Set.of() : forced(c);
        forceRowSecurity(c, forced, false);
        Map<UUID, Set<String>> shops = shops(c);
        for (Map.Entry<UUID, Set<String>> shop : shops.entrySet()) {
            List<String> missing = new ArrayList<>();
            for (String name : StandardUnits.ALL) {
                if (shop.getValue().add(StandardUnits.key(name))) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                add(c, shop.getKey(), missing);
            }
        }
        // Restored only after the work succeeded: on a failure the rollback restores it anyway.
        forceRowSecurity(c, forced, true);
    }

    /** Every retail business that is not closed, with the keys of the units it has (switched off ones too). */
    private static Map<UUID, Set<String>> shops(Connection c) throws SQLException {
        Map<UUID, Set<String>> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT m.tenant_id FROM tenant_modules m JOIN tenants t ON t.id = m.tenant_id
                         WHERE m.module_key = 'retail' AND t.status <> 'closed' ORDER BY m.tenant_id
                        """)) {
            while (rs.next()) {
                out.put(rs.getObject(1, UUID.class), new HashSet<>());
            }
        }
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT tenant_id, name FROM retail_units")) {
            while (rs.next()) {
                Set<String> keys = out.get(rs.getObject(1, UUID.class));
                if (keys != null) {
                    keys.add(StandardUnits.key(rs.getString(2)));
                }
            }
        }
        return out;
    }

    /**
     * One shop's missing units in one statement, and their audit rows in one more (review of #722: two statements per shop,
     * not two per unit, while the tables may be held). Only the units this insert made are audited.
     */
    private static void add(Connection c, UUID tenant, List<String> names) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                WITH made AS (
                    INSERT INTO retail_units (id, tenant_id, name, created_by)
                    SELECT gen_random_uuid(), ?, n, NULL FROM unnest(?::text[]) AS n
                    ON CONFLICT DO NOTHING
                    RETURNING id, name)
                INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
                SELECT gen_random_uuid(), ?, 'system', 'retail.unit.created', 'retail.unit', made.id,
                       jsonb_build_object('after', jsonb_build_object('name', made.name, 'source', ?::text))
                  FROM made
                """)) {
            p.setObject(1, tenant);
            p.setArray(2, c.createArrayOf("text", names.toArray()));
            p.setObject(3, tenant);
            p.setString(4, SOURCE);
            p.executeUpdate();
        }
    }

    /** The tables read and written here that force row level security now. */
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
}
