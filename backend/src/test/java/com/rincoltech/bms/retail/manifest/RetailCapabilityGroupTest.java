package com.rincoltech.bms.retail.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The retail.capabilities settings group (ADR-033): defaults, presets and refusals. */
class RetailCapabilityGroupTest {

    final RetailCapabilityGroup group = new RetailCapabilityGroup();

    @Test
    void absentKeysAreOffAndTheProfileIsShop() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of());

        assertThat(g.get("profile")).isEqualTo("shop");
        assertThat(RetailCapabilityGroup.CAPABILITIES)
                .allSatisfy(k -> assertThat(g.get(k)).isEqualTo(false));
        assertThat(group.enabledCapabilities(g)).isEmpty();
    }

    @Test
    void theTradeProfileSwitchesOnItsBundleAndLeavesDocumentsAndPurchasingOff() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of("profile", "trade"));

        assertThat(group.enabledCapabilities(g))
                .containsExactly(
                        "branch_roles", "customer_accounts", "price_lists", "returns", "transfer_receipt", "units");
        assertThat(g.get("documents")).isEqualTo(false);
        assertThat(g.get("purchasing")).isEqualTo(false);
    }

    @Test
    void aFlagInTheSameChangeWinsOverThePreset() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of("profile", "trade", "returns", false));

        assertThat(g.get("returns")).isEqualTo(false);
        assertThat(g.get("branch_roles")).isEqualTo(true);
    }

    @Test
    void aFlagChangeKeepsTheOthersAndTheShopProfileSwitchesEverythingOff() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("profile", "trade"));
        Map<String, Object> documents = group.normalise(on, Map.of("documents", true));

        assertThat(documents.get("documents")).isEqualTo(true);
        assertThat(documents.get("branch_roles")).isEqualTo(true);
        assertThat(group.enabledCapabilities(group.normalise(documents, Map.of("profile", "shop"))))
                .isEmpty();
    }

    @Test
    void anUnknownKeyOrAWrongTypeIsRefused() {
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("wholesale", true)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("units", "yes")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("profile", "wholesale")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void creditSaleRecognitionDefaultsToOnSaleSoNothingChangesForAnyTenant() {
        Map<String, Object> g = group.normalise(Map.of(), Map.of());

        assertThat(g.get("credit_sale_recognition")).isEqualTo("on_sale");
        assertThat(group.enabledCapabilities(g)).doesNotContain("credit_sale_recognition");
        // A group saved before the setting existed reads on_sale too.
        assertThat(group.normalise(Map.of("profile", "trade", "returns", true), Map.of())
                        .get("credit_sale_recognition"))
                .isEqualTo("on_sale");
    }

    @Test
    void creditSaleRecognitionTakesOnlyTheTwoValuesAndSurvivesProfileAndFlagChanges() {
        Map<String, Object> on = group.normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));

        assertThat(on.get("credit_sale_recognition")).isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("profile", "trade")).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("profile", "shop")).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("returns", true)).get("credit_sale_recognition"))
                .isEqualTo("on_payment");
        assertThat(group.normalise(on, Map.of("credit_sale_recognition", "on_sale"))
                        .get("credit_sale_recognition"))
                .isEqualTo("on_sale");
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("credit_sale_recognition", "later")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> group.normalise(Map.of(), Map.of("credit_sale_recognition", true)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void switchingOnRecordsTheDayItCountsFromAndReadingAgainChangesNothing() {
        Map<String, Object> on = group.normalise(
                Map.of(),
                Map.of("credit_sale_recognition", "on_payment", "credit_recognition_effective_from", "2026-10-20"));
        assertThat(on.get("credit_recognition_effective_from")).isEqualTo("2026-10-20");
        assertThat(on).doesNotContainKey("credit_recognition_changes");
        assertThat(group.enabledCapabilities(on)).contains("credit_on_payment");
        // Reading it again changes nothing, and a profile or flag change leaves the day alone.
        assertThat(group.normalise(on, Map.of())).isEqualTo(on);
        assertThat(group.normalise(on, Map.of("profile", "trade")).get("credit_recognition_effective_from"))
                .isEqualTo("2026-10-20");
        // Asking for on_payment again while it is on does not move the day.
        assertThat(group.normalise(on, Map.of("credit_sale_recognition", "on_payment"))
                        .get("credit_recognition_effective_from"))
                .isEqualTo("2026-10-20");
    }

    @Test
    void cancellingBeforeTheDayHasComeClearsItAndNothingElseGoesBack() {
        Map<String, Object> on = group.normalise(
                Map.of(),
                Map.of("credit_sale_recognition", "on_payment", "credit_recognition_effective_from", "2026-10-20"));
        Map<String, Object> back = group.normalise(on, Map.of("credit_sale_recognition", "on_sale"));
        assertThat(back.get("credit_sale_recognition")).isEqualTo("on_sale");
        assertThat(back).doesNotContainKey("credit_recognition_effective_from");
        assertThat(group.enabledCapabilities(back)).doesNotContain("credit_on_payment");
    }

    @Test
    void theEffectiveDateMustBeADateAndASwitchWithoutOneCountsFromTheBeginning() {
        assertThatThrownBy(() -> group.normalise(
                        Map.of(),
                        Map.of("credit_sale_recognition", "on_payment", "credit_recognition_effective_from", "soon")))
                .isInstanceOf(ApiException.class);
        Map<String, Object> g = group.normalise(Map.of(), Map.of("credit_sale_recognition", "on_payment"));
        assertThat(g.get("credit_recognition_effective_from")).isEqualTo("1970-01-01");
    }
}
