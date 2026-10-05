package dev.vox.lss.sponge;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pump's synchronous loaded-chunk probe in {@link SpongeRequestProcessingService}: the
 * main thread owns every chunk on Sponge, so loaded columns are serialized on the pump and
 * handed to the router in the same snapshot as the requests they answer.
 */
class LoadedChunkProbeTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- rig (mirrors SpongeRequestProcessingServiceTest, plus the probe seams) ----

    private Map<UUID, SpongePlayerRequestState> players;
    private SpongeChunkDiskReader diskReader;
    private SpongeRequestProcessingServiceTest.RecordingProcessor processor;
    private SpongeRequestProcessingServiceTest.RecordingGenService genService;
    private MinecraftServer server;
    private PlayerList playerList;
    private SpongeConfig config;
    private SpongeRequestProcessingService service;


    @BeforeEach
    void buildRig() {
        config = new MutableSpongeSettings();
        MutableSpongeSettings.normalize(config);
        players = new ConcurrentHashMap<>();
        diskReader = new SpongeChunkDiskReader(1);
        processor = new SpongeRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        genService = new SpongeRequestProcessingServiceTest.RecordingGenService(config);
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var tracker = new DirtyColumnTracker();
        var broadcaster = new SpongeRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        service = new SpongeRequestProcessingService(server, config,
                new SpongeRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster));

        service.setLoadedColumnProbe((level, cx, cz) -> null);
    }

    @AfterEach
    void teardownReader() {
        diskReader.shutdown();
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var l = mock(ServerLevel.class);
        when(l.dimension()).thenReturn(key);
        return l;
    }

    private static ServerPlayer playerIn(UUID uuid, ServerLevel level) {
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        when(p.chunkPosition()).thenReturn(new ChunkPos(0, 0)); // lifecycle stamps the gate's ring origin
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        return p;
    }

    private static LoadedColumnData column(int cx, int cz) {
        return new LoadedColumnData(cx, cz, new byte[]{1, 2, 3}, 3);
    }

    /** The probe map the latest posted snapshot carries for the player, or null. */
    private Long2ObjectMap<LoadedColumnData> probesInLastSnapshot(UUID uuid) {
        var snapshot = processor.snapshots.get(processor.snapshots.size() - 1);
        return snapshot.loadedChunkProbes().get(uuid);
    }

    /** Declare a complete want-set into the MAILBOX — the source the Folia hold-release takes
     *  from. Each call REPLACES the previous declaration (latest-wins): a client that still
     *  wants an earlier position re-declares it, so multi-position seeds are ONE batch. */
    private static void offer(SpongePlayerRequestState state, IncomingRequest... reqs) {
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }

    /** Stand in for the processing thread having APPLIED a want-set: what the SYNC (non-Folia)
     *  probe walks. The regionized path reads the mailbox first, falling back to the published want-set (the R1 arm) — see {@link #offer}. */
    private static void publish(SpongePlayerRequestState state, IncomingRequest... reqs) {
        state.publishWantSet(new IncomingBatch(reqs));
    }

    /** True when a declaration is sitting in the mailbox (released or never held). */
    private static boolean hasPendingBatch(SpongePlayerRequestState state) {
        return state.peekIncomingBatch() != null;
    }

    // ---- RP-001: regionized mode schedules instead of probing synchronously ----



    @Test
    void syncProbingIsUnchangedWhenRegionizedProbingIsOff() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        // Sync mode walks the APPLIED want-set (no hold-release), so seed the published set.
        publish(state, new IncomingRequest(3, 4, -1));

        service.tick();

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertTrue(probes.containsKey(PositionUtil.packPosition(3, 4)),
                "sync mode serves the probe in the SAME tick's snapshot");
    }

    /** 2026-08-05 review P1, pump rung: a probe-suppressed want-set entry (just sent, or
     *  just answered up_to_date) must not be re-serialized by the sync probe pass while
     *  an unsuppressed sibling still probes. Reverting the {@code isProbeSuppressed}
     *  rung reds here. */
    @Test
    void syncProbeSkipsSuppressedPositionsAndStillProbesSiblings() {
        service.setLoadedColumnProbe((level, cx, cz) -> column(cx, cz));
        var uuid = UUID.randomUUID();
        var player = playerIn(uuid, level(Level.OVERWORLD));
        var state = service.registerPlayer(player, 1);
        long suppressed = PositionUtil.packPosition(3, 4);
        long sibling = PositionUtil.packPosition(3, 5);
        publish(state, new IncomingRequest(3, 4, -1), new IncomingRequest(3, 5, -1));
        state.stampProbeSuppress(suppressed);

        service.tick();

        var probes = probesInLastSnapshot(uuid);
        assertNotNull(probes);
        assertFalse(probes.containsKey(suppressed),
                "a suppressed head must not re-serialize in the probe pass");
        assertTrue(probes.containsKey(sibling), "the unsuppressed sibling still probes");

        // The suppress mark dies with a dirty clear (the edited-column path): the next
        // pass probes it again.
        state.clearDiskReadDone(suppressed);
        service.tick();
        var probes2 = probesInLastSnapshot(uuid);
        assertNotNull(probes2);
        assertTrue(probes2.containsKey(suppressed),
                "an un-suppressed (dirty-cleared) position probes again immediately");
    }


    // ---- RP-002: one-tick merge and single consumption ----



    // ---- RP-008: the one-tick hold-release pipeline aligns requests with their probes ----






    // ---- RP-003: the ownership guard bounds what the region task may read ----


    // ---- RP-004: dimension-change discard ----


    // ---- RP-005: departed-player sweep ----


    // ---- RP-006: generation-outcome skip contract, both sides ----



    // ---- RP-007: per-task position cap ----


    // ---- Folia review 2026-08-27: R1 (the published-want-set arm) + R9 (races, N>1) ----






    private boolean currentProbe(LoadedColumnData data,SpongePlayerRequestState state) throws Exception {
        var method=dev.vox.lss.common.processing.OffThreadProcessor.class.getDeclaredMethod("currentLoadedProbe",
                LoadedColumnData.class,String.class,long.class,dev.vox.lss.common.processing.AbstractPlayerRequestState.class);
        method.setAccessible(true);
        return (boolean)method.invoke(processor,data,"minecraft:overworld",PositionUtil.packPosition(data.cx(),data.cz()),state);
    }


    @Test
    void synchronousCapturePrecedesSerializerAndFreshCaptureRemainsUsable() throws Exception {
        var uuid=UUID.randomUUID();var state=service.registerPlayer(playerIn(uuid,level(Level.OVERWORLD)),1);
        long packed=PositionUtil.packPosition(6,6);
        service.setLoadedColumnProbe((level,cx,cz)->{processor.invalidateTimestamps("minecraft:overworld",new long[]{packed},null);return column(cx,cz);});
        offer(state,new IncomingRequest(6,6,-1));service.tick();assertFalse(currentProbe(probesInLastSnapshot(uuid).get(packed),state));
        service.setLoadedColumnProbe((level,cx,cz)->column(cx,cz));service.tick();assertTrue(currentProbe(probesInLastSnapshot(uuid).get(packed),state));
    }




}
