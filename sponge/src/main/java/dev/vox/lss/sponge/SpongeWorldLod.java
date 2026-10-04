package dev.vox.lss.sponge;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Per-world LOD-distance resolution for Sponge: every Sponge world is its own dimension
 * key ({@code minecraft:the_nether}, {@code myplugin:arena}), so the dimension id is the
 * only key; an unlisted world falls through to {@code config.lodDistanceChunks()}.
 *
 * <p>Resolution is LEVEL-keyed at the core ({@link #distance(SpongeConfig, ServerLevel)});
 * the player and dimension-key entry points funnel into it. Every read is guarded — a
 * resolution failure degrades to the default, never an exception into a serve path.
 */
final class SpongeWorldLod {

    /** A player resolves through its own level, so the dim-id extraction
     *  lives in ONE place ({@link #distance(SpongeConfig, ServerLevel)}). */
    static int distance(SpongeConfig config, ServerPlayer player) {
        return player == null ? config.lodDistanceChunks() : distance(config, levelOf(player));
    }

    /** The core resolver: dimension id, then the default. */
    static int distance(SpongeConfig config, ServerLevel level) {
        if (level == null) return config.lodDistanceChunks();
        return config.lodDistanceForWorld(dimensionId(level));
    }

    /** The distance for a dimension {@link ResourceKey} — used by the dimension-change
     *  compare for the PREVIOUS world, which may already be unloaded. The dimension id is
     *  the only key on Sponge, so no level lookup is needed. */
    static int distanceForDimKey(SpongeConfig config, ResourceKey<Level> key) {
        if (key == null) return config.lodDistanceChunks();
        return config.lodDistanceForWorld(key.identifier().toString());
    }

    private static ServerLevel levelOf(ServerPlayer player) {
        try {
            return player.level() instanceof ServerLevel sl ? sl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String dimensionId(ServerLevel level) {
        try {
            var dim = level.dimension();
            return dim == null ? null : dim.identifier().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SpongeWorldLod() {}
}
