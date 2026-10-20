package com.rincoltech.bms.retail.manifest;

import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code retail.capabilities} (ADR-033, docs/specs/trade-capabilities.md
 * section 1): eight booleans, absent meaning off, the profile {@code shop} or {@code trade}, and the policy
 * {@code credit_sale_recognition} ({@code on_sale} by default, or {@code on_payment}; ADR-043).
 * A profile is a preset that writes a bundle of the flags in one change; the module reads the
 * flags and never the profile. The core stores the group and calls this class to validate it.
 */
@Component
class RetailCapabilityGroup implements ModuleSettingsGroup {

    static final String PROFILE = "profile";
    /** How credit sales count in the reports and the daily savings (ADR-043); {@code on_sale} is the default. */
    static final String CREDIT_RECOGNITION = "credit_sale_recognition";

    static final List<String> CREDIT_RECOGNITIONS = List.of("on_sale", "on_payment");

    /**
     * The first business day the rule {@code on_payment} counts from, set by the route (the business day after it was asked for) and
     * never by the caller: credit sales dated before it keep counting at the sale (ADR-043). The rule is one-way: it is switched on
     * once, and the only way back is to cancel it before this day has come. Absent while the rule is {@code on_sale}.
     */
    static final String EFFECTIVE_FROM = "credit_recognition_effective_from";

    static final String BEGINNING = "1970-01-01";

    /** The name {@code /me} lists while the rule is on_payment, so the screens can say which profit they show. */
    static final String ON_PAYMENT_FLAG = "credit_on_payment";

    static final List<String> CAPABILITIES = List.of(
            "branch_roles",
            "units",
            "price_lists",
            "customer_accounts",
            "documents",
            "purchasing",
            "transfer_receipt",
            "returns");
    /** What choosing the trade profile switches on; documents and purchasing stay as they are. */
    static final Set<String> TRADE_PRESET =
            Set.of("branch_roles", "units", "price_lists", "customer_accounts", "returns", "transfer_receipt");

    @Override
    public String moduleKey() {
        return RetailManifest.MODULE_KEY;
    }

    @Override
    public String auditAction() {
        return "retail.capabilities.changed";
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(PROFILE, current.get(PROFILE) instanceof String p && isProfile(p) ? p : "shop");
        String mode =
                current.get(CREDIT_RECOGNITION) instanceof String c && CREDIT_RECOGNITIONS.contains(c) ? c : "on_sale";
        out.put(CREDIT_RECOGNITION, mode);
        String effective = current.get(EFFECTIVE_FROM) instanceof String d && validDate(d) ? d : null;
        if (mode.equals("on_payment") && effective == null) {
            effective = BEGINNING;
        }
        for (String key : CAPABILITIES) {
            out.put(key, Boolean.TRUE.equals(current.get(key)));
        }
        List<FieldProblem> problems = new ArrayList<>();
        proposed.forEach((key, value) -> {
            if (key.equals(CREDIT_RECOGNITION)) {
                if (!(value instanceof String c && CREDIT_RECOGNITIONS.contains(c))) {
                    problems.add(new FieldProblem(CREDIT_RECOGNITION, "invalid", "Use on_sale or on_payment."));
                }
            } else if (key.equals(EFFECTIVE_FROM)) {
                if (!(value instanceof String d && validDate(d))) {
                    problems.add(new FieldProblem(EFFECTIVE_FROM, "invalid", "Use a date like 2026-10-20."));
                }
            } else if (!key.equals(PROFILE) && !CAPABILITIES.contains(key)) {
                problems.add(new FieldProblem(key, "unknown", "Not a retail capability."));
            } else if (key.equals(PROFILE) && !(value instanceof String p && isProfile(p))) {
                problems.add(new FieldProblem(PROFILE, "invalid", "Use shop or trade."));
            } else if (!key.equals(PROFILE) && !(value instanceof Boolean)) {
                problems.add(new FieldProblem(key, "invalid", "Use true or false."));
            }
        });
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (proposed.get(CREDIT_RECOGNITION) instanceof String next && !next.equals(mode)) {
            // One way: on_payment is switched on once, from the day the route names. Going back to on_sale is only the
            // cancelling of
            // a change that has not taken effect yet, which the route allows and nothing else does.
            if (next.equals("on_payment")) {
                effective = proposed.get(EFFECTIVE_FROM) instanceof String f ? f : BEGINNING;
            } else {
                effective = null;
            }
            mode = next;
            out.put(CREDIT_RECOGNITION, mode);
        }
        if (effective != null) {
            out.put(EFFECTIVE_FROM, effective);
        }
        if (proposed.get(PROFILE) instanceof String profile) {
            out.put(PROFILE, profile);
            for (String key : CAPABILITIES) {
                if (profile.equals("shop")) {
                    out.put(key, false);
                } else if (TRADE_PRESET.contains(key)) {
                    out.put(key, true);
                }
            }
        }
        for (String key : CAPABILITIES) {
            if (proposed.get(key) instanceof Boolean on) {
                out.put(key, on);
            }
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        List<String> out = new ArrayList<>(CAPABILITIES.stream()
                .filter(k -> Boolean.TRUE.equals(normalised.get(k)))
                .toList());
        if ("on_payment".equals(normalised.get(CREDIT_RECOGNITION))) {
            out.add(ON_PAYMENT_FLAG);
        }
        out.sort(null);
        return out;
    }

    private static boolean validDate(String d) {
        try {
            java.time.LocalDate.parse(d);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isProfile(String p) {
        return p.equals("shop") || p.equals("trade");
    }
}
