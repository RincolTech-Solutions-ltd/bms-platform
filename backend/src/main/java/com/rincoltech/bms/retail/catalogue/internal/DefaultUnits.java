package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.ModuleSetUp;
import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import com.rincoltech.bms.retail.catalogue.StandardUnits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #618 (ADR-098): a business that gets the retail module starts with the units most shops count in, so its first item
 * can be saved without a trip to Units. Issue #709 (ADR-110, D98): those are the standard single units and packages. A unit
 * the business already has under any spelling of the same unit (Pcs for Piece) is skipped, so switching retail off and on
 * again adds only what is missing. Migration V121 gives the missing ones to every retail business made before.
 */
@Component
class DefaultUnits implements ModuleSetUp {

    static final List<String> NAMES = StandardUnits.ALL;

    private final CatalogueRepository repo;
    private final AuditLog audit;

    DefaultUnits(CatalogueRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Override
    public String moduleKey() {
        return "retail";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void switchedOn() {
        // Issue #698: every default's key locked first, in key order, so "pcs" added on a screen at this moment and the
        // Piece made here are never two units.
        NAMES.stream().map(CatalogueNames::unitKey).sorted().forEach(repo::lockUnitKey);
        Set<String> existing = repo.unitIdsByName().keySet();
        for (String name : NAMES) {
            // Issue #698: keyed as the units are, so a shop with Pcs gets no second Piece.
            if (existing.contains(CatalogueNames.unitKey(name))) {
                continue;
            }
            UUID id = UUID.randomUUID();
            repo.insertUnit(id, name, null);
            audit.record(
                    AuditLog.Entry.created("retail.unit.created", "retail.unit", id, null, Map.of("name", name)),
                    null,
                    "system");
        }
    }
}
