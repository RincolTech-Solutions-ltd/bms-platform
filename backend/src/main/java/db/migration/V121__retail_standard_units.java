package db.migration;

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
 * <p>A Java migration so it matches a shop's own spellings by the application's rule, frozen below:
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
 * <p>Frozen (round 2 of the review of #722): a Java migration has no checksum, so the names it adds and the rule that matches
 * a shop's own spellings are copied here as they stood when it was written ({@code standard-units.json} and
 * {@code unit-words.json} of issue #709, read through {@code CatalogueNames.unitKey}); a later change to those files never
 * changes what V121 inserts. {@code V121FrozenKeyTest} proves the copy gives the application's keys today.
 */
// JUSTIFICATION-A3: the owner's round 2 instruction asks V121 frozen with a copy of its key rule inside the migration.
public class V121__retail_standard_units extends BaseJavaMigration {

    private static final String SOURCE = "V121";

    /** The standard units as of V121, in order. */
    public static final List<String> NAMES = List.of(
            "Piece", "Pair", "Set", "Metre", "Kg", "Gram", "Litre", "Ml", "Dozen", "Box", "Carton", "Packet", "Bag",
            "Roll", "Bale", "Crate", "Bundle");

    /** Every spelling of the shared list as of V121 to its unit's word: words, plurals, codes and other spellings. */
    public static final Map<String, String> SPELLINGS = Map.ofEntries(
            Map.entry("piece", "piece"),
            Map.entry("pieces", "piece"),
            Map.entry("pc", "piece"),
            Map.entry("pcs", "piece"),
            Map.entry("pce", "piece"),
            Map.entry("kilogram", "kilogram"),
            Map.entry("kilograms", "kilogram"),
            Map.entry("kg", "kilogram"),
            Map.entry("kgs", "kilogram"),
            Map.entry("kilo", "kilogram"),
            Map.entry("kilos", "kilogram"),
            Map.entry("gram", "gram"),
            Map.entry("grams", "gram"),
            Map.entry("gm", "gram"),
            Map.entry("gms", "gram"),
            Map.entry("g", "gram"),
            Map.entry("gramme", "gram"),
            Map.entry("grammes", "gram"),
            Map.entry("millilitre", "millilitre"),
            Map.entry("millilitres", "millilitre"),
            Map.entry("ml", "millilitre"),
            Map.entry("mls", "millilitre"),
            Map.entry("milliliter", "millilitre"),
            Map.entry("milliliters", "millilitre"),
            Map.entry("litre", "litre"),
            Map.entry("litres", "litre"),
            Map.entry("ltr", "litre"),
            Map.entry("ltrs", "litre"),
            Map.entry("l", "litre"),
            Map.entry("liter", "litre"),
            Map.entry("liters", "litre"),
            Map.entry("metre", "metre"),
            Map.entry("metres", "metre"),
            Map.entry("mtr", "metre"),
            Map.entry("mtrs", "metre"),
            Map.entry("m", "metre"),
            Map.entry("meter", "metre"),
            Map.entry("meters", "metre"),
            Map.entry("carton", "carton"),
            Map.entry("cartons", "carton"),
            Map.entry("ctn", "carton"),
            Map.entry("ctns", "carton"),
            Map.entry("packet", "packet"),
            Map.entry("packets", "packet"),
            Map.entry("pkt", "packet"),
            Map.entry("pkts", "packet"),
            Map.entry("dozen", "dozen"),
            Map.entry("dozens", "dozen"),
            Map.entry("doz", "dozen"),
            Map.entry("dz", "dozen"),
            Map.entry("box", "box"),
            Map.entry("boxes", "box"),
            Map.entry("bx", "box"));

    /** {@code CatalogueNames.unitKey} as of V121, over {@link #SPELLINGS}. */
    public static String key(String name) {
        String s = name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        String listed = SPELLINGS.get(s);
        if (listed != null) {
            return listed;
        }
        String one = s;
        if (s.length() > 4 && s.endsWith("ies")) {
            one = s.substring(0, s.length() - 3) + "y";
        } else if (s.matches(".*(ch|sh|x|z|ss)es")) {
            one = s.substring(0, s.length() - 2);
        } else if (s.length() > 3
                && s.endsWith("s")
                && !s.endsWith("ss")
                && Character.isLetter(s.charAt(s.length() - 2))) {
            one = s.substring(0, s.length() - 1);
        }
        return SPELLINGS.getOrDefault(one, one);
    }

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
            for (String name : NAMES) {
                if (shop.getValue().add(key(name))) {
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
                    keys.add(key(rs.getString(2)));
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
