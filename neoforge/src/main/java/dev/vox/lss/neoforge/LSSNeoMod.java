package dev.vox.lss.neoforge;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.platform.NeoForgeLoaderServices;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entrypoint (stage N-2, neoforge-support-plan.md): the fabric
 * {@code LSSMod}/{@code LSSClient} pair's twin. Brand first, the LoaderServices
 * seam second (before any config/compat touch), then payload + event wiring.
 * The client half boots reflectively behind a dist gate (the fabric
 * BenchmarkBridge / vss-fork pattern) so a dedicated server never links a
 * client class.
 */
@Mod("lss")
public final class LSSNeoMod {

    private static final String CLIENT_BOOTSTRAP_CLASS = "dev.vox.lss.neoforge.LSSNeoClientBootstrap";

    public LSSNeoMod(IEventBus modBus) {
        // FIRST: display branding (the fabric ordering contract — before any config
        // touch, LSSClientConfig's static init reads Brand.shortName()).
        Brand.load(LSSNeoMod.class.getClassLoader());
        // SECOND: the loader seam — everything xplat reads flows through it.
        NeoForgeLoaderServices.installProduction();

        modBus.addListener(RegisterPayloadHandlersEvent.class, LSSNetworking::register);

        // Service-gate permission nodes (service-permission-gate-plan.md §2.1):
        // registered UNCONDITIONALLY — an unregistered node throws at query time,
        // and a runtime `set requireServicePermission true` must find them live.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.server.permission.events.PermissionGatherEvent.Nodes.class,
                LSSNeoPermissions::onGatherNodes);
        // Issue #304 (2026-09-27, this line): the SQLite driver is EXTERNAL on NeoForge —
        // the "Minecraft SQLite JDBC" library mod, an OPTIONAL dependency. Runs BEFORE
        // ServerStartedEvent opens the store, so an admin sees WHAT to install above the
        // shared factory's generic "SQLite engine unavailable" degrade.
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class,
                LSSNeoMod::warnIfSqliteDriverAbsent);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                LSSServerNetworking::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,
                LSSServerNetworking::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
                LSSServerNetworking::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.level.ChunkEvent.Load.class,
                LSSServerNetworking::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                LSSServerNetworking::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                LSSServerNetworking::onRegisterCommands);

        if (FMLEnvironment.dist.isClient()) { // 1.21.1 line: dist is a field on 21.1
            initClientReflectively();
        }
    }

    /** The JDBC entry point sqlite-jdbc registers — its presence is the driver's. */
    static final String SQLITE_DRIVER_CLASS = "org.sqlite.JDBC";

    /**
     * Issue #304: this line's NeoForge jar ships NO sqlite-jdbc (a nested or flat copy is
     * a JPMS ResolutionException beside the "Minecraft SQLite JDBC" library mod every
     * other NeoForge SQLite consumer depends on), so the driver is an optional mod
     * dependency and its absence is a SUPPORTED state: the store degrades to store-less
     * inside the shared factory. What the shared code cannot say is what to install —
     * this per-loader hint does, once, on DEDICATED servers that would open the store
     * (enabled + lodStore not off). Integrated servers skip it: a singleplayer client
     * without the library would otherwise warn on every world load for a store it opens
     * only when publishing to LAN (the shared degrade line still covers that path).
     * Presence-only probe ({@code initialize=false}) through the mod's own loader — the
     * same resolution the store class gets; a native-load failure is the factory's
     * separate degrade. Contained: a hint must never take the server down.
     */
    static void warnIfSqliteDriverAbsent(ServerAboutToStartEvent event) {
        try {
            if (!event.getServer().isDedicatedServer()) return;
            var config = dev.vox.lss.config.LSSServerConfig.CONFIG;
            if (!config.enabled || dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
                    == dev.vox.lss.common.store.LodStoreMode.OFF) {
                return;
            }
            if (sqliteDriverPresent(LSSNeoMod.class.getClassLoader())) return;
            dev.vox.lss.common.LSSLogger.warn("lodStore=on but no SQLite JDBC driver ("
                    + SQLITE_DRIVER_CLASS + ") is visible to " + Brand.shortName()
                    + " — the LOD store will be unavailable (terrain still serves from"
                    + " disk; warm-join acceleration is off). On NeoForge the driver is"
                    + " a separate library mod: install \"Minecraft SQLite JDBC\""
                    + " (https://modrinth.com/mod/minecraft-sqlite-jdbc, mod id sqlite_jdbc)"
                    + " into mods/ next to " + Brand.shortName() + " and restart.");
        } catch (Throwable t) {
            dev.vox.lss.common.LSSLogger.warn("SQLite driver presence check failed (ignored)", t);
        }
    }

    /** Package-visible for the contract suite's reasoning; presence only, never init. */
    static boolean sqliteDriverPresent(ClassLoader loader) {
        try {
            Class.forName(SQLITE_DRIVER_CLASS, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** N-3 ships the bootstrap class; until then the miss is an inert INFO (the client
     *  half is compiled-and-inert on 26.2 anyway — plan §0.1). Contained: a client-boot
     *  failure must never take the whole mod down with it. */
    private static void initClientReflectively() {
        try {
            Class.forName(CLIENT_BOOTSTRAP_CLASS).getMethod("init").invoke(null);
        } catch (ClassNotFoundException e) {
            dev.vox.lss.common.LSSLogger.info(
                    "No NeoForge client bootstrap present — client half inert (N-2 server-only build)");
        } catch (ReflectiveOperationException e) {
            dev.vox.lss.common.LSSLogger.error("Failed to initialize the "
                    + Brand.shortName() + " client half — client features disabled", e);
        }
    }
}
