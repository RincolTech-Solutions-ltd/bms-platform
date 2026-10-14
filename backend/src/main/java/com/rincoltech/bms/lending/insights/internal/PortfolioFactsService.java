package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Metric;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.DayTotal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PortfolioFacts} over the insights code (ADR-037 decision 3). The figures are the ones the insights endpoints
 * return for the same scope ({@link InsightsService#brief}, {@code portfolio}, {@code revenue} and {@code members}, and
 * {@link InsightsFigures#par} for the branch and product groups), so there is one implementation of every number and
 * the scope rules are the endpoints' own. This class adds three things and no arithmetic of its own beyond a
 * difference:
 *
 * <ol>
 *   <li>the changes: the same figures read again for the range a week earlier and a month earlier (a stock from the
 *       nightly snapshot of that date, a flow from the transactions of that range), and the difference taken;
 *   <li>the suppression of small counts: a figure whose population is one to four loans or members is replaced by
 *       {@code fewer than 5}, and so is its change, and a change is left out when the earlier figure had such a
 *       population (current minus change would give it back);
 *   <li>the groups: branches and products by id, never by name in a fact (the names travel apart in
 *       {@link FactSet#names()}).
 * </ol>
 *
 * <p>A residual risk that the acknowledgement states: when some groups are suppressed and others are shown, the
 * suppressed ones' share is the total minus the shown groups. A model asked not to compute is not an attacker, and the
 * same figures are on the insights page for the people allowed to see them.
 */
@Service
class PortfolioFactsService implements PortfolioFacts {

    /** The most groups per dimension in the facts: the largest by loans, so that a prompt stays small. */
    static final int MAX_GROUPS = 10;

    /** The synthetic population keys: repayment and recovery transactions in the window, and on its last day. */
    private static final String COLLECTIONS = "txn.collections";

    private static final String COLLECTIONS_TODAY = "txn.collections_today";

    private static final String ACTIVE = "portfolio.active_loans";

    /** How a figure is picked: its dictionary key, polarity, whether it has changes, and the populations it covers. */
    private record Spec(String key, Polarity polarity, boolean delta, boolean stock, String... populations) {}

    private static Spec spec(String key, Polarity polarity, boolean delta, boolean stock, String... populations) {
        return new Spec(key, polarity, delta, stock, populations);
    }

    private static final Polarity BETTER = Polarity.HIGHER_IS_BETTER;
    private static final Polarity WORSE = Polarity.HIGHER_IS_WORSE;
    private static final Polarity NEUTRAL = Polarity.NEUTRAL;

    private static final List<Spec> BRIEF = List.of(
            spec("brief.disbursed_today", BETTER, true, false, "brief.disbursed_loans_today"),
            spec("brief.disbursed_loans_today", BETTER, true, false, "brief.disbursed_loans_today"),
            spec("brief.collected_today", BETTER, true, false, ACTIVE, COLLECTIONS_TODAY),
            spec("brief.expected_today", NEUTRAL, true, false, ACTIVE),
            spec("brief.collection_rate_today", BETTER, true, false, ACTIVE),
            spec("brief.new_arrears", WORSE, false, false, "brief.new_arrears"),
            spec("brief.new_arrears_amount", WORSE, false, false, "brief.new_arrears"),
            spec("brief.going_bad", WORSE, false, false, "brief.going_bad"),
            spec("brief.going_bad_principal", WORSE, false, false, "brief.going_bad"));

    private static final List<Spec> PORTFOLIO = List.of(
            spec("portfolio.principal_outstanding", NEUTRAL, true, true, ACTIVE),
            spec("portfolio.active_loans", NEUTRAL, true, true, ACTIVE),
            spec("portfolio.borrowers", NEUTRAL, true, true, "portfolio.borrowers"),
            spec("portfolio.arrears", WORSE, true, true, ACTIVE),
            spec("portfolio.par1", WORSE, true, true, ACTIVE),
            spec("portfolio.par30", WORSE, true, true, ACTIVE),
            spec("portfolio.par60", WORSE, true, true, ACTIVE),
            spec("portfolio.par90", WORSE, true, true, ACTIVE),
            spec("portfolio.disbursed", BETTER, true, false, "portfolio.disbursed_count"),
            spec("portfolio.disbursed_count", BETTER, true, false, "portfolio.disbursed_count"),
            spec("portfolio.collected", BETTER, true, false, ACTIVE, COLLECTIONS),
            spec("portfolio.expected", NEUTRAL, true, false, ACTIVE),
            spec("portfolio.collection_rate", BETTER, true, false, ACTIVE),
            spec("portfolio.forecast_7", NEUTRAL, false, false, ACTIVE),
            spec("portfolio.forecast_30", NEUTRAL, false, false, ACTIVE),
            spec("portfolio.written_off", WORSE, true, false, "portfolio.written_off_count"),
            spec("portfolio.written_off_count", WORSE, true, false, "portfolio.written_off_count"),
            spec("portfolio.recovered", BETTER, true, false, ACTIVE),
            spec("portfolio.repeat_share", BETTER, true, false, "portfolio.disbursed_count"),
            spec("portfolio.yield", BETTER, true, false, ACTIVE));

    private static final List<Spec> REVENUE = List.of(
            spec("revenue.interest", BETTER, true, false, ACTIVE),
            spec("revenue.fees", BETTER, true, false, ACTIVE),
            spec("revenue.penalties", BETTER, true, false, ACTIVE),
            spec("revenue.recovered", BETTER, true, false, ACTIVE),
            spec("revenue.write_off_expense", WORSE, true, false, ACTIVE),
            spec("revenue.contribution", BETTER, true, false, ACTIVE));

    private static final List<Spec> MEMBERS = List.of(
            spec("members.new_members", BETTER, true, false, "members.new_members"),
            spec("members.applied", NEUTRAL, true, false, "members.applied"),
            spec("members.approved", NEUTRAL, true, false, "members.approved"),
            spec("members.disbursed", BETTER, true, false, "members.disbursed"),
            spec("members.median_decision_hours", WORSE, true, false, "members.approved"),
            spec("members.dormant", WORSE, false, false, "members.dormant"));

    private final InsightsService insights;
    private final InsightsQueries queries;
    private final Positions positions;
    private final InsightsFigures figures;
    private final CurrentTenant tenant;

    PortfolioFactsService(
            InsightsService insights,
            InsightsQueries queries,
            Positions positions,
            InsightsFigures figures,
            CurrentTenant tenant) {
        this.insights = insights;
        this.queries = queries;
        this.positions = positions;
        this.figures = figures;
        this.tenant = tenant;
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet forCaller() {
        // The caller's scope, resolved by the insights code itself: permission, branches, own loans.
        return build(figures.scope(null, null, null, null, null));
    }

    /** One window of figures: every value by dictionary key, and whether the stock figures could be read. */
    private static final class Window {
        final Map<String, Long> values = new HashMap<>();
        final Set<String> present = new HashSet<>();
        boolean stockKnown = true;

        void put(String key, Long value) {
            values.put(key, value);
            present.add(key);
        }

        boolean has(Spec s) {
            return present.contains(s.key()) && (!s.stock() || stockKnown);
        }

        /** True when a population of this window is one to four. */
        boolean small(Spec s) {
            for (String p : s.populations()) {
                Long n = values.get(p);
                if (n != null && n > 0 && n < MIN_GROUP) {
                    return true;
                }
            }
            return false;
        }
    }

    private Window read(Scope scope, boolean withBrief) {
        Window w = new Window();
        if (withBrief) {
            // The brief is the business date's: in an earlier window "today" is that window's last day.
            Scope day = new Scope(
                    scope.from(),
                    scope.to(),
                    scope.to(),
                    scope.branchIds(),
                    scope.officerId(),
                    scope.productId(),
                    scope.currency());
            for (Metric m : insights.brief(day).metrics()) {
                w.put(m.key(), m.value());
            }
            w.put(
                    COLLECTIONS_TODAY,
                    queries.collectedByDay(day.withRange(day.to(), day.to()), InsightsQueries.COLLECTED_TYPES).stream()
                            .mapToLong(DayTotal::count)
                            .sum());
        }
        InsightsApi.PortfolioResponse portfolio = insights.portfolio(scope, null);
        for (Metric m : portfolio.metrics()) {
            w.put(m.key(), m.value());
        }
        w.stockKnown = !"none".equals(portfolio.stockSource());
        for (Metric m : insights.revenue(scope).metrics()) {
            w.put(m.key(), m.value());
        }
        for (Metric m : insights.members(scope, null).metrics()) {
            w.put(m.key(), m.value());
        }
        w.put(
                COLLECTIONS,
                queries.collectedByDay(scope, InsightsQueries.COLLECTED_TYPES).stream()
                        .mapToLong(DayTotal::count)
                        .sum());
        return w;
    }

    private static Scope shifted(Scope s, UnaryOperator<LocalDate> by) {
        return new Scope(
                by.apply(s.from()),
                by.apply(s.to()),
                s.today(),
                s.branchIds(),
                s.officerId(),
                s.productId(),
                s.currency());
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet build(PortfolioScope p) {
        String currency = tenant.profile().currency();
        Scope now = new Scope(p.from(), p.to(), p.today(), p.branchIds(), p.officerId(), p.productId(), currency);
        boolean brief = now.to().equals(now.today());
        Window current = read(now, brief);
        Window week = read(shifted(now, d -> d.minusDays(7)), brief);
        Window month = read(shifted(now, d -> d.minusMonths(1)), brief);

        List<Fact> facts = new ArrayList<>();
        List<List<Spec>> sections =
                brief ? List.of(BRIEF, PORTFOLIO, REVENUE, MEMBERS) : List.of(PORTFOLIO, REVENUE, MEMBERS);
        for (List<Spec> section : sections) {
            for (Spec s : section) {
                if (current.has(s)) {
                    facts.add(fact(s, currency, current, week, month));
                }
            }
        }
        List<GroupName> names = new ArrayList<>();
        groups(p, ParGroupBy.BRANCH, Dimension.BRANCH, "branch", currency, facts, names);
        groups(p, ParGroupBy.PRODUCT, Dimension.PRODUCT, "product", currency, facts, names);
        return new FactSet(now.to(), now.from(), now.to(), currency, facts, names);
    }

    private static Fact fact(Spec s, String currency, Window current, Window week, Window month) {
        Metrics.Definition def = Metrics.ALL.get(s.key());
        Kind kind = kind(def.kind());
        if (current.small(s)) {
            return new Fact(
                    s.key(), def.label(), kind, s.polarity(), null, "fewer than " + MIN_GROUP, true, null, null, null);
        }
        Long value = current.values.get(s.key());
        if (value == null) {
            return new Fact(s.key(), def.label(), kind, s.polarity(), null, "not available", false, null, null, null);
        }
        return new Fact(
                s.key(),
                def.label(),
                kind,
                s.polarity(),
                value,
                FactDisplay.of(kind, value, currency),
                false,
                s.delta() ? delta(s, kind, value, week, currency) : null,
                s.delta() ? delta(s, kind, value, month, currency) : null,
                null);
    }

    /** The change against an earlier window, or null when that figure is unknown or covered a small population. */
    private static Delta delta(Spec s, Kind kind, long value, Window before, String currency) {
        if (!before.has(s) || before.small(s)) {
            return null;
        }
        Long then = before.values.get(s.key());
        if (then == null) {
            return null;
        }
        long change = value - then;
        return new Delta(change, FactDisplay.change(kind, change, currency));
    }

    private static Kind kind(String dictionaryKind) {
        return switch (dictionaryKind) {
            case Metrics.MONEY -> Kind.MONEY;
            case Metrics.COUNT -> Kind.COUNT;
            case Metrics.BP -> Kind.BASIS_POINTS;
            case Metrics.DAYS -> Kind.DAYS;
            case Metrics.HOURS -> Kind.HOURS;
            default -> throw new IllegalArgumentException("unknown kind " + dictionaryKind);
        };
    }

    /**
     * The branch or product groups, from the public figures port: the largest first, a group of fewer than five loans
     * as a single suppressed count and nothing else. A past date with no snapshot has no groups.
     */
    private void groups(
            PortfolioScope scope,
            ParGroupBy by,
            Dimension dimension,
            String prefix,
            String currency,
            List<Fact> facts,
            List<GroupName> names) {
        if (!scope.to().equals(scope.today()) && !positions.snapshotTaken(scope.to())) {
            // par() would refuse, and a refusal inside this transaction would mark it rollback-only.
            return;
        }
        ParFigures par = figures.par(scope, by, () -> {});
        List<ParGroup> largest = par.groups().stream()
                .sorted(Comparator.comparingLong(ParGroup::loans).reversed().thenComparing(ParGroup::key))
                .limit(MAX_GROUPS)
                .toList();
        for (ParGroup g : largest) {
            Group group = new Group(dimension, g.key());
            names.add(new GroupName(group, g.label()));
            if (g.loans() < MIN_GROUP) {
                facts.add(new Fact(
                        prefix + ".loans",
                        "Active loans",
                        Kind.COUNT,
                        NEUTRAL,
                        null,
                        "fewer than " + MIN_GROUP,
                        true,
                        null,
                        null,
                        group));
                continue;
            }
            facts.add(new Fact(
                    prefix + ".loans",
                    "Active loans",
                    Kind.COUNT,
                    NEUTRAL,
                    g.loans(),
                    FactDisplay.count(g.loans()),
                    false,
                    null,
                    null,
                    group));
            facts.add(new Fact(
                    prefix + ".principal_outstanding",
                    "Principal outstanding",
                    Kind.MONEY,
                    NEUTRAL,
                    g.principalOutstandingMinor(),
                    FactDisplay.money(g.principalOutstandingMinor(), currency),
                    false,
                    null,
                    null,
                    group));
            facts.add(new Fact(
                    prefix + ".par30",
                    "PAR 30",
                    Kind.BASIS_POINTS,
                    WORSE,
                    g.par30Bp(),
                    g.par30Bp() == null ? "not available" : FactDisplay.percent(g.par30Bp()),
                    false,
                    null,
                    null,
                    group));
        }
    }
}
