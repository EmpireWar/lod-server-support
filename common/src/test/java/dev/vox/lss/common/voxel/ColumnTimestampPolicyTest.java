package dev.vox.lss.common.voxel;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnTimestampPolicyTest {
    @Test void shrinkIsBoundedAndKeepsMostRecentlyTouchedTilesAndSaveSnapshot() {
        var cache = new ColumnTimestampCache(100L * ColumnTimestampCache.TILE_HEAP_BYTES, 100);
        long stamp = ColumnTimestampCache.TS_EPOCH_SECONDS + 100;
        for (int tile = 0; tile < 100; tile++)
            cache.put("world", PositionUtil.packPosition(tile * 32, 0), stamp, tile);
        cache.put("world", PositionUtil.packPosition(0, 0), stamp + 1, 101);
        var save = cache.snapshotForSave();
        cache.adoptPolicy(ColumnTimestampCache.TILE_HEAP_BYTES, 100);
        assertEquals(100, cache.size(), "adoption does not perform bulk eviction");
        assertEquals(8, cache.trimOversized(8));
        assertEquals(92, cache.size());
        assertEquals(stamp + 1, cache.get("world", PositionUtil.packPosition(0, 0)));
        while (cache.size() > 1) cache.trimOversized(8);
        assertEquals(stamp + 1, cache.get("world", PositionUtil.packPosition(0, 0)));
        assertEquals(100, save.size(), "queued save snapshot stays independent");
        cache.adoptPolicy(100L * ColumnTimestampCache.TILE_HEAP_BYTES, 100);
        assertEquals(1, cache.size(), "growth does not eagerly allocate tiles");
    }

    @Test void ttlEditsClearOnlyMissesAndZeroDisablesFutureMemoWrites() {
        var cache = new ColumnTimestampCache(1 << 20, 1000);
        long stamp = ColumnTimestampCache.TS_EPOCH_SECONDS + 100;
        cache.put("world", 1, stamp, 100);
        cache.putMiss("world", 2, 100);
        cache.adoptPolicy(2 << 20, 1000);
        assertTrue(cache.isFreshMiss("world", 2, 101), "budget-only edit retains memo deadlines");
        cache.adoptPolicy(2 << 20, 2000);
        assertFalse(cache.isFreshMiss("world", 2, 101));
        cache.putMiss("world", 2, 100);
        cache.adoptPolicy(2 << 20, 0);
        cache.putMiss("world", 3, 100);
        assertEquals(0, cache.missCount());
        assertEquals(stamp, cache.get("world", 1));
    }
}
