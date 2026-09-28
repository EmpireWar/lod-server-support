package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.config.SettingsReload;
import dev.vox.lss.common.config.SettingsSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientReloadTextTest {
    @Test void failedAndPendingAdoptionNeverSelectASuccessMessage() {
        assertEquals("lss.settings.adoption_failed",ClientReloadText.outcomeKey(SettingsReload.Status.RECONCILIATION_FAILED));
        assertEquals("lss.settings.adoption_pending",ClientReloadText.outcomeKey(SettingsReload.Status.ADOPTION_PENDING));
        assertEquals("lss.settings.reloaded",ClientReloadText.outcomeKey(SettingsReload.Status.APPLIED));
        assertEquals("lss.settings.reload_unchanged",ClientReloadText.outcomeKey(SettingsReload.Status.UNCHANGED));
    }
    @Test void normalizationHasDistinctTranslatedArgumentsAndCannotInjectAnotherLine() {
        var notice=ClientReloadText.normalization(new SettingsSchema.Normalization(
                "lod.distance_chunks", "requested\n\u202efake", 16384));
        assertEquals("lss.settings.normalized",notice.key());
        assertEquals(3,notice.arguments().size());
        assertEquals("requested\\u000a\\u202efake",notice.arguments().get(1));
        assertEquals("16384",notice.arguments().get(2));
        var longValue=ClientReloadText.normalization(new SettingsSchema.Normalization("path","x".repeat(500),0));
        assertTrue(longValue.arguments().get(1).toString().length()<300);
    }
}
