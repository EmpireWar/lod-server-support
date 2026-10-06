package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload;
import dev.vox.lss.platform.LoaderServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.UUID;

/**
 * Sponge server-side networking glue — the fabric/neoforge {@code LSSServerNetworking}
 * twin (same FQN). The receiver bodies are the shared {@link ServerReceiverGlue}; this
 * class owns the static service holder and the lifecycle/payload entry points that
 * {@code LSSSpongePlugin} and the mixin twins call. Dedicated server only: Sponge has no
 * integrated-server/LAN path.
 */
public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(UUID uuid) {
        return ServerReceiverGlue.clientDataVersion(uuid);
    }

    /** Hook body for the {@code ChunkSaveDataHook} mixin twin. */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk) {
        ServerReceiverGlue.onChunkSaveData(level, chunk, requestService);
    }

    /** Hook body for {@code ChunkLoadHook}: the FULL-status promotion, as Fabric's
     *  CHUNK_LOAD / NeoForge's ChunkEvent.Load (Sponge's own ChunkEvent.Load only fires at
     *  ENTITY_TICKING, which misses the border of the loaded area). */
    public static void onChunkLoad(ServerLevel level, ChunkAccess chunk, boolean newlyGenerated) {
        ServerReceiverGlue.onChunkLoaded(level, chunk, requestService, newlyGenerated);
    }

    // ---- Lifecycle (driven by LSSSpongePlugin) ----

    public static void onServerStarted(MinecraftServer server) {
        dev.vox.lss.config.LSSServerConfig.beginServerLifecycle();
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");
        requestService = new RequestProcessingService(server);
        ServerReceiverGlue.flushPendingLoadSeeds(server, requestService); // the pre-service spawn set
    }

    public static void onServerStopping() {
        dev.vox.lss.config.LSSServerConfig.CONFIG.close();
        var service = requestService;
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
            requestService = null;
        }
        // Sidecar facts die with the server (review C1-9).
        ServerReceiverGlue.clearClientInfo();
    }

    public static void onServerTick() {
        var service = requestService;
        if (service != null) {
            service.tick();
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        var service = requestService;
        if (service != null) {
            service.removePlayer(uuid);
            // Connection-scoped state: compat identities, far-player subscription and
            // prefs, region summaries and the service-gate memo die with the connection,
            // never with the dimension-change remove+register cycle.
            service.getV16CompatManager().onDisconnect(uuid);
            service.getDialectTracker().onDisconnect(uuid);
            service.getFarPlayerService().onDisconnect(uuid);
            service.getRegionSummaries().removePlayer(uuid);
            service.getServiceGateState().onDisconnect(uuid);
        }
        // Service-independent: recorded at the network level, possibly before any service.
        ServerReceiverGlue.sweepClientInfo(uuid);
    }

    // ---- Payload handlers (main thread: Sponge hops channel payloads onto it) ----

    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.handleHandshake(payload, player, requestService,
                reply -> LoaderServices.get().sendToPlayer(player, reply));
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, ServerPlayer player) {
        var service = requestService;
        if (service != null) {
            service.handleBatchRequest(player, payload);
        }
    }

    public static void handleClientInfo(ClientInfoC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.recordClientInfo(player.getUUID(), payload.dataVersion());
    }

    public static void handleFarPlayerPrefs(FarPlayerPrefsC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.onFarPlayerPrefs(requestService, player, payload.body());
    }

    public static void handleRegionSummaryRequest(RegionSummaryRequestC2SPayload payload, ServerPlayer player) {
        var service = requestService;
        if (service != null) {
            service.handleRegionSummaryRequest(player, payload.body());
        }
    }
}
