package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads the tenant's retail capabilities (ADR-033, docs/specs/trade-capabilities.md section 1).
 * The settings group itself is supplied by {@code retail.manifest} and stored by the core; this is
 * the one place the rest of retail asks whether a capability is on. Off is the default and means
 * behaviour exactly as before the capability existed.
 */
@Component
public class RetailCapabilities {

    public static final String BRANCH_ROLES = "branch_roles";
    public static final String UNITS = RetailCatalogue.UNITS_CAPABILITY;
    public static final String PRICE_LISTS = RetailPricing.PRICE_LISTS_CAPABILITY;
    public static final String CUSTOMER_ACCOUNTS = "customer_accounts";
    public static final String TRANSFER_RECEIPT = "transfer_receipt";
    public static final String DOCUMENTS = "documents";
    public static final String RETURNS = "returns";
    public static final String PURCHASING = "purchasing";
    private static final String MODULE = "retail";

    /** Credit sales count when they are made: the behaviour before ADR-043 and the default. */
    public static final String ON_SALE = "on_sale";

    /** Credit sales count as sales, cash and profit only when the buyer pays (ADR-043). */
    public static final String ON_PAYMENT = "on_payment";

    public static final String CREDIT_RECOGNITION = "credit_sale_recognition";

    private final ModuleSettings settings;

    RetailCapabilities(ModuleSettings settings) {
        this.settings = settings;
    }

    public boolean enabled(String capability) {
        return Boolean.TRUE.equals(settings.group(MODULE).get(capability));
    }

    /** The tenant's credit sale recognition, {@link #ON_SALE} unless the owner chose {@link #ON_PAYMENT} (ADR-043). */
    public String creditRecognition() {
        return ON_PAYMENT.equals(settings.group(MODULE).get(CREDIT_RECOGNITION)) ? ON_PAYMENT : ON_SALE;
    }

    /**
     * The stretches of sale dates counted when the buyer pays (ADR-043): from the day the owner chose {@code on_payment} to the day
     * he went back, or still open. Empty for a tenant that never chose it, so every report reads as it always did.
     */
    public CreditPeriods onPaymentPeriods() {
        Object stored = settings.group(MODULE).get("credit_recognition_changes");
        if (!(stored instanceof List<?> list)) {
            return CreditPeriods.NONE;
        }
        List<CreditPeriods.Range> ranges = new ArrayList<>();
        LocalDate open = null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)
                    || !(m.get("from") instanceof String from)
                    || !(m.get("mode") instanceof String mode)) {
                continue;
            }
            LocalDate date = LocalDate.parse(from);
            if (mode.equals(ON_PAYMENT) && open == null) {
                open = date;
            } else if (mode.equals(ON_SALE) && open != null) {
                ranges.add(new CreditPeriods.Range(open, date));
                open = null;
            }
        }
        if (open != null) {
            ranges.add(new CreditPeriods.Range(open, null));
        }
        return new CreditPeriods(ranges);
    }

    /** The day the current credit rule took effect, when it was ever changed (ADR-043). */
    public LocalDate creditRecognitionEffectiveFrom() {
        return settings.group(MODULE).get("credit_recognition_effective_from") instanceof String d
                ? LocalDate.parse(d)
                : null;
    }

    /** True when credit sales are reported as received money only (ADR-043). */
    public boolean recognisesOnPayment() {
        return ON_PAYMENT.equals(creditRecognition());
    }

    /** 404 {@code capability_not_enabled} for a route of a capability that is off. */
    public void require(String capability) {
        if (!enabled(capability)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "capability_not_enabled",
                    "Capability not enabled",
                    "This capability is not enabled for the tenant.");
        }
    }

    /** The whole group with defaults applied, for the settings route. */
    public Map<String, Object> group() {
        return settings.group(MODULE);
    }

    /** Applies a partial change to the group; validated by the group and audited by the core. */
    public Map<String, Object> change(Map<String, Object> proposed, int expectedVersion) {
        return settings.change(MODULE, proposed, expectedVersion);
    }

    /** The settings version, the ETag of the group. */
    public int version() {
        return settings.version();
    }
}
