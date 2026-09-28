package dev.vox.lss.paper;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaperConfigValidationTest {
    @Test void paperUsesItsOwnFreshnessDefaultsAndTheSharedDimensionDefaults() {
        var c = new PaperConfig();
        assertEquals(300, c.lodStoreResweepSeconds());
        assertEquals(64, c.lodDistanceForWorld("world_nether", "minecraft:the_nether"));
        assertEquals("on", c.lodStore());
        assertEquals(14, c.updateEvents().size());
        assertFalse(c.requireServicePermission());
        assertEquals(1200, c.generationTimeoutTicks());
        assertEquals(200, c.dirtyBroadcastIntervalTicks());
    }
    @Test void exactWorldNameWinsBeforeDimensionAndFallback() {
        var values = Map.<String,Object>of("lod.distance.default_chunks", 12,
                "lod.distance.by_dimension", Map.of("minecraft:the_nether", 32),
                "lod.distance.by_world", Map.of("我的 world", 96, "minecraft:the_nether", 128));
        var c = new PaperConfig(SettingsSchema.server(true).fromValues(values).normalized());
        assertEquals(96, c.lodDistanceForWorld("我的 world", "minecraft:the_nether"));
        assertEquals(32, c.lodDistanceForWorld("other", "minecraft:the_nether"));
        assertEquals(128, c.lodDistanceForWorld("minecraft:the_nether", "custom:void"));
        assertEquals(12, c.lodDistanceForWorld("other_nether", "custom:void"));
        assertEquals(128, c.maxConfiguredLodDistanceChunks());
    }
    @Test void zeroSentinelsAndCorrelatedBoundsResolveInThePlatformFacade() {
        var c = new PaperConfig(SettingsSchema.server(true).fromValues(Map.of(
                "generation.concurrency.global", 2, "generation.concurrency.per_player", 10,
                "storage.lod_store.max_size_mib", 0, "updates.dirty_broadcast_interval_ticks", 0,
                "storage.disk.max_concurrent_reads", 64)).normalized());
        assertEquals(2, c.generationLimits().perPlayer());
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        assertEquals(0, c.dirtyBroadcastIntervalTicks());
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, true));
    }
}
