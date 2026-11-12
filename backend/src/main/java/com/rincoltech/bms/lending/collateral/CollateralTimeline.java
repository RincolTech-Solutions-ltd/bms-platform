package com.rincoltech.bms.lending.collateral;

import java.util.UUID;

/**
 * Lines other lending modules write on an item's custody timeline (FR-COL-03), in the caller's transaction. A loan renewal
 * (issue #918, ADR-128) moves an item's pledge to the loan that replaces the old one: the item does not change hands or
 * custody, so the timeline gets a {@code note} event with the item's status unchanged, like a note staff add, saying which
 * loan it now secures.
 */
public interface CollateralTimeline {

    /** Writes a {@code note} event now, from and to the item's current custody status, recorded by {@code by}. */
    void note(UUID collateralId, String note, UUID by);
}
