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
}
