package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The interleaving behind issue #487, held still: another administrator's switch-on commits while a second switch-on is
 * between its reads, after it has read the version it expected. The second request lost the race and must be told so
 * (409), not have its acknowledgement refused (422) against settings it never saw. SmsSettingsIT races real requests;
 * this case is the one that race reached only now and then.
 */
class SmsSettingsServiceRaceTest {

    /** Settings at version 1 with SMS off, where the winner's switch-on commits just before the group is read. */
    static final class CommitBeforeTheGroupIsRead implements ModuleSettings {
        int version = 1;
        boolean enabled;

        @Override
        public Map<String, Object> group(String moduleKey) {
            enabled = true;
            version = 2;
            return Map.of(SmsSettingsGroup.ENABLED, enabled);
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public Map<String, Object> change(String moduleKey, Map<String, Object> proposed, int expectedVersion) {
            throw new AssertionError("the loser of the race never reaches the change");
        }

        @Override
        public Map<String, List<String>> enabledCapabilities() {
            return Map.of();
        }
    }

    @Test
    void aSwitchOnThatLostTheRaceIsAConflictNotARefusedAcknowledgement() {
        SmsSettingsService service = new SmsSettingsService(new CommitBeforeTheGroupIsRead(), null, null, null, null);
        SmsSettingsService.Change switchOn = new SmsSettingsService.Change(
                true, SmsSettingsService.ACK_TEXT_VERSION, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("\"1\"", switchOn)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("version_conflict");
        });
    }
}
