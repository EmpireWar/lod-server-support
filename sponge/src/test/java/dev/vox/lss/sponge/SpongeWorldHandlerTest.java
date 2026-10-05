package dev.vox.lss.sponge;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.transaction.BlockTransactionReceipt;
import org.spongepowered.api.block.transaction.Operation;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.math.vector.Vector3i;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every applied block change marks its column dirty in its own world. */
class SpongeWorldHandlerTest {

    private static BlockTransactionReceipt receipt(String world, int x, int y, int z) {
        // ResourceKey.resolve needs a running game; the handler only reads asString()
        var key = mock(ResourceKey.class);
        when(key.asString()).thenReturn(world);
        var snapshot = mock(BlockSnapshot.class);
        when(snapshot.world()).thenReturn(key);
        when(snapshot.position()).thenReturn(new Vector3i(x, y, z));
        return new BlockTransactionReceipt(snapshot, snapshot, mock(Operation.class));
    }

    private static long[] drained(DirtyColumnTracker tracker, String world) {
        long[] positions = tracker.drainDirty(world);
        Arrays.sort(positions);
        return positions;
    }

    @Test
    void receiptsMarkTheirColumnsPerWorld() {
        var tracker = new DirtyColumnTracker();
        // Built before stubbing the event: mocks cannot be created inside another when()
        var receipts = List.of(
                receipt("minecraft:overworld", 5, 64, 5),
                receipt("minecraft:overworld", 15, 0, 15),     // same column as above
                receipt("minecraft:overworld", -1, 64, -17),   // negative coords floor, not truncate
                receipt("myplugin:arena", 32, 10, 0));
        var event = mock(ChangeBlockEvent.Post.class);
        when(event.receipts()).thenReturn(receipts);

        new SpongeWorldHandler(tracker).onBlocksChanged(event);

        long[] overworld = {PositionUtil.packPosition(-1, -2), PositionUtil.packPosition(0, 0)};
        Arrays.sort(overworld);
        assertArrayEquals(overworld, drained(tracker, "minecraft:overworld"));
        assertArrayEquals(new long[]{PositionUtil.packPosition(2, 0)}, drained(tracker, "myplugin:arena"));
    }

    @Test
    void anEventWithoutReceiptsMarksNothing() {
        var tracker = new DirtyColumnTracker();
        var event = mock(ChangeBlockEvent.Post.class);
        when(event.receipts()).thenReturn(List.of());
        new SpongeWorldHandler(tracker).onBlocksChanged(event);
        assertEquals(0, tracker.pendingCount());
    }
}
