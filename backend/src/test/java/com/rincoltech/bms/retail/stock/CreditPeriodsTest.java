package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.retail.stock.CreditPeriods.Range;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The stretches of sale dates counted when the buyer pays (ADR-043). */
class CreditPeriodsTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    static final LocalDate D2 = LocalDate.of(2026, 10, 10);

    @Test
    void aTenantThatNeverChoseTheRuleHasNoStretchAndNoSqlCondition() {
        assertThat(CreditPeriods.NONE.isEmpty()).isTrue();
        assertThat(CreditPeriods.NONE.contains(D1)).isFalse();
        assertThat(CreditPeriods.NONE.sql("s.sale_date", new LinkedHashMap<>(), "p"))
                .isEqualTo("false");
    }

    @Test
    void aStretchIncludesItsFirstDayAndExcludesTheDayItEnds() {
        CreditPeriods p = new CreditPeriods(List.of(new Range(D1, D2)));
        assertThat(p.contains(D1.minusDays(1))).isFalse();
        assertThat(p.contains(D1)).isTrue();
        assertThat(p.contains(D2.minusDays(1))).isTrue();
        assertThat(p.contains(D2)).isFalse();
    }

    @Test
    void anOpenStretchHasNoEnd() {
        CreditPeriods p = new CreditPeriods(List.of(new Range(D1, null)));
        assertThat(p.contains(D1.plusYears(5))).isTrue();
    }

    @Test
    void theSqlConditionBindsEveryDateUnderItsOwnName() {
        CreditPeriods p = new CreditPeriods(List.of(new Range(D1, D2), new Range(D2.plusDays(5), null)));
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = p.sql("s.sale_date", params, "x");
        assertThat(sql).isEqualTo("((s.sale_date >= :xFrom0 AND s.sale_date < :xTo0) OR (s.sale_date >= :xFrom1))");
        assertThat(params).containsOnlyKeys("xFrom0", "xTo0", "xFrom1");
    }
}
