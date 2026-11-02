package com.rincoltech.bms.lending.reports.internal;

import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportOutput;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.ReportParams;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.RegisterFigures;
import com.rincoltech.bms.lending.insights.RegisterFigures.CollateralFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code lending.collateral_register} (FR-COL-06; chapter 14 section 14.5, permission {@code lending.reports.members}):
 * every collateral item in the caller's branches as it stands now, through {@link RegisterFigures#collateral}. Two
 * tables:
 *
 * <ul>
 *   <li>{@code items}: per item the branch, member, type, description, reference, estimated value, latest valuation and
 *       its date, custody status and location, the linked loans with their status, and its flags.
 *   <li>{@code flags}: how many items carry each flag, and how many items there are.
 * </ul>
 *
 * The branches are those of {@code lending.reports.members} for the caller, resolved at request time and frozen with the
 * run; the member's name is printed only for a holder of {@code lending.members.read}. A download by someone whose access
 * has narrowed since is refused. The PDF cuts a long register at the row cap with a pointer to the Excel file; the
 * summary table comes first so it is always printed whole.
 */
@Component
class CollateralRegisterReport implements ReportDefinition {

    static final String KEY = "lending.collateral_register";
    static final String PERMISSION = "lending.reports.members";
    static final String MEMBERS_READ = "lending.members.read";

    static final String RELEASE_OVERDUE = "Release overdue";
    static final String UNVERIFIED = "Imported, not verified";
    static final String NO_VALUATION = "No valuation";

    private final RegisterFigures figures;

    CollateralRegisterReport(RegisterFigures figures) {
        this.figures = figures;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "Collateral register";
    }

    @Override
    public String description() {
        return "Every collateral item in your branches: its value, where it is kept, the loans it secures, and what needs attention.";
    }

    @Override
    public String module() {
        return "lending";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public List<Param> params() {
        return List.of(
                new Param(
                        "branch_id",
                        "uuid_list",
                        false,
                        "Branches to include; every branch in your scope when absent."),
                new Param(
                        "collateral_type",
                        "enum",
                        false,
                        "Only items of this type; every type when absent.",
                        RegisterFigures.COLLATERAL_TYPES),
                new Param(
                        "custody_status",
                        "enum",
                        false,
                        "Only items in this custody status; every status when absent.",
                        RegisterFigures.CUSTODY_STATUSES));
    }

    @Override
    public Set<String> formats() {
        return Set.of("json", "csv", "pdf", "xlsx");
    }

    @Override
    public Prepared prepare(Map<String, Object> params) {
        List<UUID> branches = ReportParams.uuids(params, "branch_id");
        String type = ReportParams.choice(params, "collateral_type", RegisterFigures.COLLATERAL_TYPES, null);
        String custody = ReportParams.choice(params, "custody_status", RegisterFigures.CUSTODY_STATUSES, null);
        Principal principal = CurrentPrincipal.require();
        List<UUID> scope = principal.branchFilter(PERMISSION, branches);
        Map<String, Object> normalised = new LinkedHashMap<>();
        if (!branches.isEmpty()) {
            normalised.put("branch_id", branches.stream().map(UUID::toString).toList());
        }
        if (type != null) {
            normalised.put("collateral_type", type);
        }
        if (custody != null) {
            normalised.put("custody_status", custody);
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put(
                "branch_ids",
                scope == null ? null : scope.stream().map(UUID::toString).toList());
        frozen.put("names", principal.hasPermission(MEMBERS_READ));
        // The register is the items as they stand now; the business date of the request is printed as its date.
        return new Prepared(normalised, frozen, figures.today());
    }

    private static List<UUID> branches(Prepared run) {
        Object value = run.scope().get("branch_ids");
        if (value == null) {
            return null;
        }
        List<UUID> out = new ArrayList<>();
        for (Object id : (List<?>) value) {
            out.add(UUID.fromString(String.valueOf(id)));
        }
        return out;
    }

    private static boolean names(Prepared run) {
        return Boolean.TRUE.equals(run.scope().get("names"));
    }

    @Override
    public boolean stillAllowed(Prepared run) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> frozen = branches(run);
        List<UUID> now = principal.branchFilter(PERMISSION, frozen);
        boolean branchesOk = frozen == null ? now == null : now == null || new HashSet<>(now).containsAll(frozen);
        return branchesOk && (!names(run) || principal.hasPermission(MEMBERS_READ));
    }

    @Override
    public String describeScope(Prepared run) {
        List<UUID> b = branches(run);
        return b == null
                ? "All branches in your access"
                : b.isEmpty() ? "No branches" : b.size() == 1 ? "1 branch selected" : b.size() + " branches selected";
    }

    @Override
    public void query(Prepared prepared, ReportOutput out) {
        boolean names = names(prepared);
        out.note(
                "The items as they stand when the report is made; custody and values are not read back to a past date.");
        out.note(RELEASE_OVERDUE + ": still pledged or in our custody, and every loan it secures is closed.");
        out.note(UNVERIFIED + ": brought in by an import and not valued, moved or noted since.");
        out.note(NO_VALUATION + ": no valuation recorded; the estimated value is the one given at registration.");

        // The counts are written first, from a first pass over the items, so the PDF prints them whole however long
        // the register is.
        long[] counts = new long[4];
        CollateralFilter filter = filter(prepared, names);
        figures.collateral(filter, out::checkpoint, l -> {
            counts[0]++;
            counts[1] += l.releaseOverdue() ? 1 : 0;
            counts[2] += l.unverified() ? 1 : 0;
            counts[3] += l.noValuation() ? 1 : 0;
        });
        ReportOutput.Table flags = out.table(
                "flags",
                "What needs attention",
                List.of(new Column("flag", "Flag", Kind.TEXT), new Column("items", "Items", Kind.COUNT)));
        flags.row(RELEASE_OVERDUE, counts[1]);
        flags.row(UNVERIFIED, counts[2]);
        flags.row(NO_VALUATION, counts[3]);
        flags.total("Items in the register", counts[0]);

        List<Column> columns = new ArrayList<>();
        columns.add(new Column("branch", "Branch", Kind.TEXT));
        columns.add(new Column("member_no", "Member", Kind.TEXT));
        if (names) {
            columns.add(new Column("member_name", "Name", Kind.TEXT));
        }
        columns.add(new Column("type", "Type", Kind.TEXT));
        columns.add(new Column("description", "Description", Kind.TEXT));
        columns.add(new Column("reference", "Reference", Kind.TEXT));
        columns.add(new Column("estimated_value", "Estimated value", Kind.MONEY));
        columns.add(new Column("latest_valuation", "Latest valuation", Kind.MONEY));
        columns.add(new Column("valued_on", "Valued on", Kind.DATE));
        columns.add(new Column("custody_status", "Custody", Kind.TEXT));
        columns.add(new Column("location", "Kept at", Kind.TEXT));
        columns.add(new Column("loans", "Loans", Kind.TEXT));
        columns.add(new Column("flags", "Flags", Kind.TEXT));
        ReportOutput.Table items = out.table("items", "Collateral items", columns);
        figures.collateral(filter, out::checkpoint, l -> {
            List<Object> cells = new ArrayList<>();
            cells.add(l.branch());
            cells.add(l.memberNo());
            if (names) {
                cells.add(l.memberName());
            }
            cells.add(l.type());
            cells.add(l.description());
            cells.add(l.reference());
            cells.add(l.estimatedValueMinor());
            cells.add(l.latestValuationMinor());
            cells.add(l.valuedOn());
            cells.add(l.custodyStatus());
            cells.add(l.location());
            cells.add(l.loans());
            List<String> f = new ArrayList<>();
            if (l.releaseOverdue()) {
                f.add(RELEASE_OVERDUE);
            }
            if (l.unverified()) {
                f.add(UNVERIFIED);
            }
            if (l.noValuation()) {
                f.add(NO_VALUATION);
            }
            cells.add(f.isEmpty() ? null : String.join("; ", f));
            items.row(cells.toArray());
        });
    }

    private static CollateralFilter filter(Prepared prepared, boolean names) {
        return new CollateralFilter(
                branches(prepared),
                (String) prepared.params().get("collateral_type"),
                (String) prepared.params().get("custody_status"),
                names);
    }
}
