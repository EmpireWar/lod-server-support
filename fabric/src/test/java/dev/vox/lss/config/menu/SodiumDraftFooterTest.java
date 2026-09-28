package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SodiumDraftFooterTest {
    private static class LegacyScreen { private Object currentPage; }
    private static class InheritedScreen extends LegacyScreen {}
    private static final class SameTitlePage {
        @Override public boolean equals(Object other) { return other instanceof SameTitlePage; }
        @Override public int hashCode() { return 1; }
    }
    @Test void onlyRegisteredPageIdentityReservesLegacyRowsRegardlessOfTitleOrBrand() {
        Object ours = new SameTitlePage();
        SodiumDraftRefresh.rememberLegacyPages(List.of(ours));
        var screen = new LegacyScreen();
        assertFalse(SodiumDraftRefresh.usesNoticeFooter(screen),"initial vanilla selection keeps full height");
        screen.currentPage = new SameTitlePage();
        assertFalse(SodiumDraftRefresh.usesNoticeFooter(screen),"same/equal title is not our page identity");
        screen.currentPage = ours;
        assertTrue(SodiumDraftRefresh.usesNoticeFooter(screen));
        screen.currentPage = new Object();
        assertFalse(SodiumDraftRefresh.usesNoticeFooter(screen),"leaving LSS must restore full viewport");
    }
    @Test void inheritedScreenAndMissingOptionalSurfaceAreContained() {
        Object ours = new Object();
        SodiumDraftRefresh.rememberLegacyPages(List.of(ours));
        var screen = new InheritedScreen();
        ((LegacyScreen) screen).currentPage = ours;
        assertTrue(SodiumDraftRefresh.usesNoticeFooter(screen));
        assertFalse(SodiumDraftRefresh.usesNoticeFooter(new Object()));
    }
}
