package dev.vox.lss.sponge;

import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link SpongeXrayMaskManager} decision/caching core through its level-free seam.
 * Sponge has no anti-xray engine, so only the LSS config keys can turn masking on: "auto"
 * (follow the engine) stays off, "on" masks with the config keys, "off" never masks.
 */
class SpongeXrayMaskManagerTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SpongeConfig config(String mode) {
        var c = new MutableSpongeSettings();
        MutableSpongeSettings.set(c, "privacy.xray.mode", mode);
        return c;
    }

    @Test
    void autoIsInactiveWithoutAnEngine() {
        var manager = new SpongeXrayMaskManager(config("auto"));
        assertNull(manager.entryFor("minecraft:overworld"));
        assertEquals("Xray: active=off, masked_sections=0", manager.diagLine());
    }

    @Test
    void offIsInactive() {
        assertNull(new SpongeXrayMaskManager(config("off")).entryFor("minecraft:overworld"));
    }

    @Test
    void onMasksUsingConfigKeys() {
        var manager = new SpongeXrayMaskManager(config("on"));
        var entry = manager.entryFor("minecraft:overworld");
        assertNotNull(entry);
        assertEquals("config", entry.sourceLabel());
        assertEquals(64, entry.mask().maxBlockHeight(), "the LSS height key applies");
        assertSame(entry, manager.entryFor("minecraft:overworld"), "a dimension is decided once per manager");
    }

    @Test
    void fallbackMaskResolvesOncePerManagerAcrossDimensions() {
        var manager = new SpongeXrayMaskManager(config("on"));
        assertSame(manager.entryFor("minecraft:overworld").mask(), manager.entryFor("minecraft:the_nether").mask());
    }

    @Test
    void fallbackKindFollowsTheDimensionString() {
        var manager = new SpongeXrayMaskManager(config("on"));
        assertEquals(FallbackKind.NETHER, manager.entryFor("minecraft:the_nether").kind());
    }

    @Test
    void diagLineAggregatesLabelAndCounter() {
        var manager = new SpongeXrayMaskManager(config("on"));
        manager.entryFor("minecraft:overworld");
        manager.countMaskedSection();
        assertEquals("Xray: active=config, masked_sections=1", manager.diagLine());
    }

    @Test
    void staticHolderLifecycleIsOwnerGuarded() {
        var first = SpongeXrayMaskManager.activate(config("on"));
        assertSame(first, SpongeXrayMaskManager.current());

        // A successor replaces the holder; the PREDECESSOR's late retract must be a no-op,
        // as must a null owner — the test-wired-service shape: its shutdown() runs with a
        // null xrayMasks field and must not null out a live production manager.
        var second = SpongeXrayMaskManager.activate(config("on"));
        SpongeXrayMaskManager.deactivate(first);
        assertSame(second, SpongeXrayMaskManager.current());
        SpongeXrayMaskManager.deactivate(null);
        assertSame(second, SpongeXrayMaskManager.current());

        SpongeXrayMaskManager.deactivate(second);
        assertNull(SpongeXrayMaskManager.current());
    }
}
