package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The sale dates counted when the buyer pays (ADR-043): one range, from one business day, for ever. */
class CreditPeriodsTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 1);

    @Test
    void aTenantThatNeverChoseTheRuleHasNoRangeAndNoSqlCondition() {
        assertThat(CreditPeriods.NONE.isEmpty()).isTrue();
        assertThat(CreditPeriods.NONE.contains(D1)).isFalse();
        assertThat(CreditPeriods.NONE.sql("s.sale_date", new LinkedHashMap<>(), "p"))
                .isEqualTo("false");
    }

    @Test
    void theRangeIncludesItsFirstDayAndEveryDayAfterAndNothingBefore() {
        CreditPeriods p = new CreditPeriods(D1);
        assertThat(p.isEmpty()).isFalse();
        assertThat(p.contains(D1.minusDays(1))).isFalse();
        assertThat(p.contains(D1)).isTrue();
        assertThat(p.contains(D1.plusYears(5))).isTrue();
    }

    @Test
    void theSqlConditionBindsTheDateUnderItsOwnName() {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = new CreditPeriods(D1).sql("s.sale_date", params, "x");
        assertThat(sql).isEqualTo("(s.sale_date >= :xFrom)");
        assertThat(params).containsOnlyKeys("xFrom");
    }
}
