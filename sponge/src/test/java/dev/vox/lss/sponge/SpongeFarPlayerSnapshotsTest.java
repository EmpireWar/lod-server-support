package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSPermissions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The far-player privacy ladder: explicit hidden grants (either brand spelling) or vanish
 * hide a player, and a throwing read fails HIDDEN, contained per player (a broken read must
 * never leak a hidden or vanished player's position, nor abort the snapshot pass).
 */
class SpongeFarPlayerSnapshotsTest {

    @BeforeAll
    static void setup() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetLatch() {
        SpongeFarPlayerSnapshots.resetHiddenReadWarnedForTest();
    }

    private static boolean hidden(Set<String> grants, boolean vanished) {
        return SpongeFarPlayerSnapshots.hiddenFor(grants::contains, () -> vanished);
    }

    @Test
    void aCleanUnprivilegedPlayerIsVisible() {
        assertFalse(hidden(Set.of(), false),
                "no grant and not vanished must stay visible — fail-hidden must not hide everyone");
    }

    @Test
    void eitherBrandSpellingHides() {
        for (String node : List.of(LSSPermissions.FARPLAYERS_HIDDEN_LSS, LSSPermissions.FARPLAYERS_HIDDEN_VSS)) {
            assertTrue(hidden(Set.of(node), false), node + " alone must hide the player");
        }
    }

    @Test
    void vanishHides() {
        assertTrue(hidden(Set.of(), true));
    }

    @Test
    void theLadderShortCircuitsBeforeTheVanishRead() {
        var reads = new ArrayList<String>();
        assertTrue(SpongeFarPlayerSnapshots.hiddenFor(
                node -> reads.add(node) && node.equals(LSSPermissions.FARPLAYERS_HIDDEN_LSS),
                () -> { reads.add("vanish"); return false; }));
        assertEquals(List.of(LSSPermissions.FARPLAYERS_HIDDEN_LSS), reads);
    }

    @Test
    void aThrowingPermissionReadFailsHIDDENNotOpen() {
        assertTrue(SpongeFarPlayerSnapshots.hiddenFor(
                node -> { throw new IllegalStateException("permission service broke"); }, () -> false));
    }

    @Test
    void aThrowingVanishReadFailsHIDDENNotOpen() {
        assertTrue(SpongeFarPlayerSnapshots.hiddenFor(
                node -> false, () -> { throw new IllegalStateException("data provider broke"); }));
    }

    @Test
    void theThrowIsContainedPerPlayerNotPerPass() {
        assertTrue(SpongeFarPlayerSnapshots.hiddenFor(node -> { throw new IllegalStateException("x"); }, () -> false));
        assertFalse(hidden(Set.of(), false), "the next player's read must be unaffected by the previous throw");
    }

    // ---- the pass-level containment + the snapshot builder ----

    private static net.minecraft.server.level.ServerPlayer healthyNms(UUID uuid, String name) {
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        var p = mock(net.minecraft.server.level.ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.getName()).thenReturn(net.minecraft.network.chat.Component.literal(name));
        when(p.level()).thenReturn(level);
        when(p.getItemBySlot(org.mockito.ArgumentMatchers.any()))
                .thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
        when(p.getKnownMovement()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(p.isAlive()).thenReturn(true);
        return p;
    }

    @Test
    void oneBrokenPlayerSkipsOnlyItselfNeverThePass() {
        // One broken read must not abort the snapshot loop for ALL players (far players
        // dark for the interval): buildFarPlayerSnapshots contains per player.
        var config = new MutableSpongeSettings();
        MutableSpongeSettings.normalize(config);
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var players = new java.util.concurrent.ConcurrentHashMap<UUID, SpongePlayerRequestState>();
        var diskReader = new SpongeChunkDiskReader(1);
        var processor = new SpongeRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        var tracker = new dev.vox.lss.common.tracking.DirtyColumnTracker();
        var broadcaster = new SpongeRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        var service = new SpongeRequestProcessingService(server, config,
                new SpongeRequestProcessingService.Wiring(
                        players, diskReader, null, processor, tracker, broadcaster));

        var healthy = healthyNms(UUID.randomUUID(), "healthy");
        var broken = healthyNms(UUID.randomUUID(), "broken");
        when(broken.getItemBySlot(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("equipment read broke"));

        var snapshots = service.buildFarPlayerSnapshots(List.of(broken, healthy));

        assertEquals(1, snapshots.size(), "the broken player is skipped; the pass survives");
        assertEquals("healthy", snapshots.get(0).name(), "the healthy player's snapshot is intact");
    }

    @Test
    void theSnapshotCarriesTheLadderVerdictAndIdentityFields() {
        var uuid = UUID.randomUUID();
        var p = healthyNms(uuid, "steve");

        var snap = SpongeFarPlayerSnapshots.snapshot(p, true);
        assertEquals(uuid, snap.uuid());
        assertEquals("steve", snap.name());
        assertEquals("minecraft:overworld", snap.dimension());
        assertTrue(snap.hidden(), "the privacy ladder's verdict rides the snapshot");
        assertTrue(snap.alive());

        // The false direction: hard-coding hidden=true would hide every far player with the suite green
        assertFalse(SpongeFarPlayerSnapshots.snapshot(p, false).hidden());
    }
}
