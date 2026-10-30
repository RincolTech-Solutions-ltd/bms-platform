package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.ModuleSetUp;
import com.rincoltech.bms.retail.catalogue.StandardUnits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        Set<String> existing =
                repo.unitIdsByName().keySet().stream().map(StandardUnits::key).collect(Collectors.toSet());
        for (String name : NAMES) {
            if (existing.contains(StandardUnits.key(name))) {
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
