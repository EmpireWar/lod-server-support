package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsNoticeLayoutTest {
    @Test void noticeAndRecoveryActionNeverOverlapTheSodiumViewport() {
        for (int height : new int[]{1, 80, 128, 240, 360, 720}) {
            for (int notice : new int[]{0, 41, 63, 96, 300}) {
                var bounds=SettingsNoticeLayout.fit(height,notice);
                assertTrue(bounds.usableHeight()>=0);
                assertTrue(bounds.usableHeight()<=bounds.noticeBottom());
                assertTrue(bounds.noticeBottom()<=bounds.buttonY());
                assertTrue(bounds.buttonHeight()>0,"recovery must remain actionable even when no notice space remains");
                assertTrue(bounds.buttonY()+bounds.buttonHeight()<=bounds.physicalHeight());
                if(height-notice-24>=128) assertEquals(notice,bounds.noticeBottom()-bounds.usableHeight());
            }
        }
    }
    @Test void compactVanillaGuiKeepsTheEntireWrappedNoticeAndDedicatedActionRow() {
        var bounds=SettingsNoticeLayout.fit(240,63);
        assertEquals(153,bounds.usableHeight());
        assertEquals(216,bounds.noticeBottom());
        assertEquals(218,bounds.buttonY());
        assertEquals(20,bounds.buttonHeight());
    }
}
