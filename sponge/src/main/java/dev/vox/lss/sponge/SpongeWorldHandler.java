package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;

/**
 * Dirty chunk detection for the Sponge plugin. Paper needs a configurable list of Bukkit
 * events read reflectively ({@code updateEvents}); Sponge records every block change it
 * applies — placements, breaks, pistons (both ends), explosions, fluids, growth — as
 * receipts on one post-apply event, so that single listener covers them all and
 * {@code updateEvents} is not read here.
 */
public class SpongeWorldHandler {

    /** Read once (-Dlss.soak.dirtyTrace). */
    private static final boolean DIRTY_TRACE = Boolean.getBoolean("lss.soak.dirtyTrace");

    private final DirtyColumnTracker dirtyTracker;

    public SpongeWorldHandler(DirtyColumnTracker dirtyTracker) {
        this.dirtyTracker = dirtyTracker;
    }

    @Listener(order = Order.POST)
    public void onBlocksChanged(ChangeBlockEvent.Post event) {
        for (var receipt : event.receipts()) {
            var block = receipt.finalBlock();
            var pos = block.position();
            String dimension = block.world().asString();
            if (DIRTY_TRACE) {
                LSSLogger.info("[dirty-trace] " + receipt.operation() + " " + dimension + " " + pos);
            }
            this.dirtyTracker.markDirty(dimension, pos.x() >> 4, pos.z() >> 4);
        }
    }
}
