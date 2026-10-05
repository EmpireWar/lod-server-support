package dev.vox.lss.sponge;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wiring pins for the P1 header freshness rung (v0.12.0-release-plan.md Phase B.0): a
 * port that drops the {@code attachRegionStamps} call or mis-roots the region-dir resolver
 * ships a server where warm rejoins are silently dead (every doubt shape degrades
 * fail-safe to NEVER_CLEAN — no crash, no log storm).
 *
 * <p>Sponge reads each level's region folder from its own chunk storage
 * ({@link SpongeRequestProcessingService#REGION_FOLDER}); these tests inject that read.
 */
class SpongeRegionFreshnessWiringTest {

    @org.junit.jupiter.api.BeforeAll
    static void setup() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var l = mock(ServerLevel.class);
        when(l.dimension()).thenReturn(key);
        return l;
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(id));
    }

    @Test
    void eachWorldMapsToTheFolderItsChunkStorageWrites() throws Exception {
        Path root = Files.createTempDirectory("lss-resolver-pin");
        var server = mock(MinecraftServer.class);
        var overworld = level(Level.OVERWORLD);
        var nether = level(Level.NETHER);
        var arena = level(dimension("myplugin:arena")); // a world loaded by a plugin
        when(server.getAllLevels()).thenReturn(List.of(overworld, nether, arena));
        Map<ServerLevel, Path> folders = Map.of(
                overworld, root.resolve("world/region"),
                nether, root.resolve("world/DIM-1/region/."),
                arena, root.resolve("world/dimensions/myplugin/arena/region"));

        var dirs = SpongeRequestProcessingService.resolveRegionDirs(server, folders::get);

        assertEquals(3, dirs.size(), "every loaded world must resolve");
        assertEquals(root.resolve("world/region"), dirs.get("minecraft:overworld"));
        assertEquals(root.resolve("world/DIM-1/region"), dirs.get("minecraft:the_nether"), "paths are normalized");
        assertEquals(root.resolve("world/dimensions/myplugin/arena/region"), dirs.get("myplugin:arena"));
    }

    @Test
    void anUnresolvableWorldDegradesThatDimensionOnlyNeverServiceStart() {
        var server = mock(MinecraftServer.class);
        var overworld = level(Level.OVERWORLD);
        var exotic = mock(ServerLevel.class);
        when(exotic.dimension()).thenThrow(new IllegalStateException("exotic dimension"));
        // The folder read itself can throw too (a world whose chunk storage is not set up);
        // it must stay INSIDE the per-level try, or one level aborts service start.
        var unattachable = level(dimension("lss_test:unattachable"));
        when(server.getAllLevels()).thenReturn(List.of(overworld, exotic, unattachable));

        var dirs = assertDoesNotThrow(() -> SpongeRequestProcessingService.resolveRegionDirs(server, l -> {
                    if (l == unattachable) throw new IllegalStateException("no chunk storage");
                    return Path.of("world/region");
                }),
                "the per-level belt: one exotic dimension must never take down start");
        assertNotNull(dirs.get("minecraft:overworld"), "the healthy dimension still resolves");
        assertNull(dirs.get("lss_test:unattachable"),
                "the unattachable level degrades to absent (UNKNOWN downstream), never a throw");
    }

    @Test
    void aWorldLoadedAfterStartupIsAdded() {
        var dirs = new HashMap<String, Path>();
        SpongeRequestProcessingService.putRegionDir(dirs, level(dimension("myplugin:arena")),
                l -> Path.of("world/dimensions/myplugin/arena/region"));
        assertEquals(Path.of("world/dimensions/myplugin/arena/region"), dirs.get("myplugin:arena"));
    }

    /** The ATTACH half (source-scan — the production wiring builder needs a full NMS
     *  server, and the test Wiring ctor deliberately bypasses it): the stamp table must be
     *  constructed from the resolver and attached to the disk reader UNCONDITIONALLY
     *  (outside any store branch — the rung is load-bearing store-LESS), and the dirty
     *  tracker's mark listener must bump the region latch. */
    @Test
    void wiringSourceCarriesTheAttachAndTheMarkListenerBump() throws Exception {
        Path src = Path.of("src/main/java/dev/vox/lss/sponge/SpongeRequestProcessingService.java");
        if (!Files.exists(src)) {
            src = Path.of("sponge").resolve(src);
        }
        String body = Files.readString(src);

        int resolve = body.indexOf("resolveRegionDirs(server, REGION_FOLDER)");
        int attach = body.indexOf("diskReader.attachRegionStamps(regionStamps)");
        int bump = body.indexOf(".bumpLiveSaveMark(");
        int stampSource = body.indexOf("setUpToDateStampSource(");
        int storeBranch = body.indexOf("if (storeMode != dev.vox.lss.common.store.LodStoreMode.OFF)");
        assertTrue(resolve > 0, "the wiring builder must construct the region dirs via resolveRegionDirs");
        assertTrue(attach > 0, "the disk reader must get attachRegionStamps — without it"
                + " the header rung is silently dead (disk.header_hits frozen at 0)");
        assertTrue(bump > 0, "the mark listener must bump the region live-save latch");
        assertTrue(stampSource > 0, "the stamped-up_to_date predicate must be installed"
                + " (setUpToDateStampSource) — without it every up_to_date ships unstamped");
        assertTrue(storeBranch > 0, "census anchor: the store-mode branch exists");
        assertTrue(attach < storeBranch, "attachRegionStamps must sit BEFORE (outside)"
                + " the store branch — the rung is load-bearing on store-LESS servers");
        assertTrue(bump < storeBranch, "the latch bump wiring must also be store-independent");
    }
}
