package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsNoticeLayoutTest {
    @Test void wrappedNoticesStayOutsideControlsAtNormalCompactAndTinySizes() {
        for (int height : new int[]{1, 80, 128, 240, 360, 720}) {
            for (int notice : new int[]{0, 41, 63, 96, 300}) {
                var bounds=SettingsNoticeLayout.fit(height,notice);
                assertTrue(bounds.usableHeight()>=Math.min(128,height));
                assertTrue(bounds.usableHeight()<=bounds.physicalHeight());
                if(height-notice>=128) assertEquals(notice,height-bounds.usableHeight());
            }
        }
    }
}
