package dev.vox.lss.common;

import java.util.UUID;

/**
 * Server API: lets another plugin or mod decide which columns each player may receive,
 * for example to keep a minigame map's surroundings hidden or to gate terrain by
 * permission. Without a filter every column is allowed, exactly as before.
 *
 * <p>A denied column is answered as not generated, so the client stops asking for it for
 * the rest of the session (a dirty broadcast for that position, or a rejoin, lets it ask
 * again). The check runs when a request is admitted for work, on LSS's processing thread:
 * the filter must be thread-safe and cheap. Work admitted before a filter change still
 * completes, and changing the filter does not take back columns a client already holds.
 */
public final class ColumnAccess {

    /** Decides whether {@code player} may receive the column at chunk {@code (chunkX, chunkZ)}. */
    @FunctionalInterface
    public interface Filter {
        /** @param dimension the dimension id, e.g. {@code minecraft:overworld} */
        boolean allows(UUID player, String dimension, int chunkX, int chunkZ);
    }

    private static volatile Filter filter;
    private static final LogThrottle THROW_WARN = new LogThrottle(60_000);

    private ColumnAccess() {}

    /** Installs the filter, replacing any previous one; null removes it. */
    public static void setFilter(Filter newFilter) {
        filter = newFilter;
    }

    /** Whether the column may be served. A throwing filter denies (fails closed). */
    public static boolean allows(UUID player, String dimension, int chunkX, int chunkZ) {
        var f = filter;
        if (f == null) return true;
        try {
            return f.allows(player, dimension, chunkX, chunkZ);
        } catch (RuntimeException e) {
            long n = THROW_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Column access filter threw (" + n + " time(s) since the last"
                        + " report); denying those columns", e);
            }
            return false;
        }
    }
}
