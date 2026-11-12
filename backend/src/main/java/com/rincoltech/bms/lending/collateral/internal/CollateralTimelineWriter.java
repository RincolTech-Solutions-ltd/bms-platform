package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.lending.collateral.CollateralTimeline;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CollateralTimeline} over {@code lending_collateral_events} (append-only), in the caller's transaction. */
@Component
class CollateralTimelineWriter implements CollateralTimeline {

    private final JdbcClient jdbc;

    CollateralTimelineWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void note(UUID collateralId, String note, UUID by) {
        jdbc.sql("""
                        INSERT INTO lending_collateral_events (id, tenant_id, collateral_id, event_type, from_status,
                            to_status, note, occurred_at, recorded_by)
                        SELECT ?, current_setting('app.tenant_id')::uuid, i.id, 'note', i.custody_status, i.custody_status,
                               ?, now(), ?
                          FROM lending_collateral_items i WHERE i.id = ?
                        """).params(UUID.randomUUID(), note, by, collateralId).update();
    }
}
