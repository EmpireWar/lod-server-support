package dev.vox.lss.sponge;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.common.PlayerServiceGate;
import dev.vox.lss.common.ServiceGateState;
import net.minecraft.server.level.ServerPlayer;
import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.event.world.LoadWorldEvent;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Sponge plugin entry point for LOD Server Support, ported from the Paper plugin.
 * Registers the raw data channels, handles handshake/request lifecycle,
 * and ticks the request processing service on the server main thread.
 *
 * <p>The environment-free glue — enable-plan ordering ({@link #runEnablePlan}),
 * plugin-message dispatch containment ({@link #dispatchPluginMessage}), and the
 * handshake reply/registration wiring ({@link #handleHandshake(byte[], String,
 * SpongeConfig, boolean, SessionConfigSender, HandshakeRegistrar)}) — is static and
 * package-private so it is testable without a server; the instance methods
 * only bind the production environment.
 */
@Plugin("lodserversupport")
public class LSSSpongePlugin {
    private final PluginContainer container;
    private final Path configDir;
    private SpongeConfig lssConfig;
    private volatile SpongeRequestProcessingService requestService;
    private volatile boolean enabled;

    @Inject
    public LSSSpongePlugin(PluginContainer container, @ConfigDir(sharedRoot = false) Path configDir) {
        this.container = container;
        this.configDir = configDir;
        // FIRST: resolve display branding before anything logs or registers its name.
        Brand.load(LSSSpongePlugin.class.getClassLoader());
    }

    // lss:client_info sidecar facts (XVER §2.2): the client's MC data version, keyed by
    // UUID, swept at quit. Absence = legacy client (no sidecar channel). Consumed as
    // diagnostics + the C5 Via-guard input.
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer>
            CLIENT_DATA_VERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return CLIENT_DATA_VERSIONS.get(uuid);
    }

    /**
     * Service-gate permission nodes, consulted only while {@link SpongeConfig#requireServicePermission}
     * is on. BOTH spellings must be held — a negative grant on EITHER denies (see
     * {@link #holdsServicePermission}). Checking both keeps an operator's grant alive across an
     * LSS&lt;-&gt;VSS jar swap. Both default to allowed ({@link SpongePlayers#holds}: only an
     * explicit false denies — user decision, 2026-08-25): arming the gate alone therefore
     * changes nothing for anyone, and a denial is always an explicit negative grant an admin
     * made in their permission plugin.
     */
    static final String PERMISSION_SERVICE_LSS = dev.vox.lss.common.LSSPermissions.SERVICE_LSS;
    /** The VSS spelling of {@link #PERMISSION_SERVICE_LSS} — see there. */
    static final String PERMISSION_SERVICE_VSS = dev.vox.lss.common.LSSPermissions.SERVICE_VSS;


    /** The pre-gate overloads' gate (see {@link PlayerServiceGate#OPEN} — the seam
     *  interface and the open-gate landmine doc live in common now, shared with the
     *  Fabric/NeoForge glue). */
    static final PlayerServiceGate SERVICE_GATE_OPEN = PlayerServiceGate.OPEN;

    /** The production {@link PlayerServiceGate}: a real Sponge permission read (CONTAINED
     *  — a throwing permissible answers TRUE with a once-warn: fail-open, serve; a throw
     *  must never escape into handshake silence), the log latch + denied-handshake memo on
     *  the service's {@link ServiceGateState}, and the denial hook marshaling the
     *  unregistration composite onto the pump. Extracted static so both halves are
     *  pinnable without a live player — a hard-coded {@code true} here would make the
     *  whole feature inert on a live server while every core test stayed green.
     *
     *  @param state                 the owning service's gate state, or null when no
     *                               service exists (the conjunction then never deposits)
     *  @param deniedWhileRegistered the pump-marshaled unregistration composite
     *                               (enqueueServiceGateUnregister), or null */
    static PlayerServiceGate serviceGateFor(java.util.function.Predicate<String> holds, java.util.UUID uuid,
                                            String playerName, ServiceGateState state,
                                            Runnable deniedWhileRegistered) {
        return new PlayerServiceGate() {
            @Override
            public boolean hasPermission(String node) {
                try {
                    return holds.test(node);
                } catch (Throwable e) {
                    if (e instanceof VirtualMachineError vme) throw vme;
                    if (PERMISSIBLE_THROW_WARNED.compareAndSet(false, true)) {
                        LSSLogger.warn("Sponge permission read threw for " + playerName
                                + " on " + node + " — serving (fail-open; the gate is not"
                                + " a security boundary): " + e);
                    }
                    return true;
                }
            }

            @Override
            public boolean claimDenialLog() {
                return state != null && state.claimDenialLog(uuid);
            }

            @Override
            public void onServiceDenied(int protocolVersion, int capabilities) {
                if (state == null) return; // unreachable: the conjunction requires servicePresent
                state.rememberDenied(uuid, playerName, protocolVersion, capabilities);
                if (deniedWhileRegistered != null) deniedWhileRegistered.run();
            }
        };
    }

    /** Once-per-JVM warn latch for a throwing permission read (CAS — the handshake read
     *  and the pump sweep may run on different threads; test seam resets). */
    private static final java.util.concurrent.atomic.AtomicBoolean PERMISSIBLE_THROW_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test seam. */
    static void resetPermissibleThrowWarnedForTest() {
        PERMISSIBLE_THROW_WARNED.set(false);
    }

    /** Whether the player clears the service gate — BOTH brand spellings, AND not OR
     *  (the De Morgan mirror of the far-player privacy nodes' grant-model OR; the full
     *  rationale lives on {@link PlayerServiceGate#holdsService}, shared with the
     *  Fabric/NeoForge glue). */
    static boolean holdsServicePermission(PlayerServiceGate gate) {
        return PlayerServiceGate.holdsService(gate);
    }

    /**
     * The onEnable step set, in the order {@link #runEnablePlan} drives them. The
     * production implementation lives in {@link #onEnable}; the interface is a test
     * seam (the plan's step order and enabled gate are pinned without a server).
     */
    interface EnableSteps {
        void loadBranding();

        SpongeConfig loadConfig();

        void registerChannels();

        void registerQuitListener();

        SpongeRequestProcessingService startService(SpongeConfig config);

        void registerWorldHandler(SpongeRequestProcessingService service, SpongeConfig config);

        void registerCommands();

        void scheduleServiceTick();

        void initSoakBridge();
    }

    /**
     * Executes the enable plan. Step order is load-bearing: config before channels
     * (handlers read it), service before the world handler (it feeds the service's
     * dirty tracker), and the soak bridge last so the driver sees a fully wired plugin.
     * /reload runs onDisable then onEnable, so this sequence is also the re-enable
     * contract.
     */
    static void runEnablePlan(EnableSteps steps) {
        // FIRST: resolve display branding before any service/thread/log line is created.
        steps.loadBranding();
        var config = steps.loadConfig();

        // Start accepting C2S frames (the channels themselves were registered at
        // RegisterChannelEvent, before the server started).
        steps.registerChannels();
        // Register event listener for player quit
        steps.registerQuitListener();

        // Start processing service
        var service = steps.startService(config);
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");

        // Register dirty chunk event listeners. enabled=false gates here (mirrors Fabric's
        // ChunkSaveDataHook gate): the service tick — and so the dirty-broadcast drain — is
        // disabled, so marking would grow the DirtyColumnTracker without bound for the whole
        // server run. enabled is immutable per run, so skipping registration is safe.
        if (config.enabled()) {
            steps.registerWorldHandler(service, config);
        }

        // Register command
        steps.registerCommands();

        // Tick the processing service every server tick (50ms)
        steps.scheduleServiceTick();

        // Dev-only soak harness (no-op unless -Dlss.soak.scenario is set)
        steps.initSoakBridge();

        LSSLogger.info(Brand.displayName() + " (Sponge) enabled");
    }

    @Listener
    public void onRegisterChannels(RegisterChannelEvent event) {
        SpongeChannels.register(event);
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        var executor = new SpongeCommands(this);
        // The brand's command name, so the Voxy Server Side repackage (which rewrites
        // lss-brand.properties) registers /vsslod without a code fork.
        event.register(this.container, new Command.Raw() {
            @Override
            public CommandResult process(CommandCause cause, ArgumentReader.Mutable arguments) {
                executor.onCommand(cause.audience(), Brand.serverCommand(), args(arguments.remaining()));
                return CommandResult.success();
            }

            @Override
            public List<CommandCompletion> complete(CommandCause cause, ArgumentReader.Mutable arguments) {
                return executor.onTabComplete(args(arguments.remaining())).stream()
                        .map(CommandCompletion::of).toList();
            }

            @Override
            public boolean canExecute(CommandCause cause) {
                return cause.hasPermission(Brand.lowerShortName() + ".admin");
            }

            @Override
            public Optional<Component> shortDescription(CommandCause cause) {
                return Optional.of(Component.text(Brand.displayName() + " admin commands"));
            }

            @Override
            public Optional<Component> extendedDescription(CommandCause cause) {
                return shortDescription(cause);
            }

            @Override
            public Component usage(CommandCause cause) {
                return Component.text("<stats|diag|diagnostics|store|reload|help>");
            }
        }, Brand.serverCommand());
    }

    /** Argument split matching Bukkit's: a trailing space starts an empty next argument. */
    static String[] args(String remaining) {
        String trimmed = remaining.stripLeading();
        return trimmed.isEmpty() ? new String[0] : trimmed.split(" +", -1);
    }

    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        runEnablePlan(new EnableSteps() {
            @Override
            public void loadBranding() {
                // Done in the constructor: the command registers before the server starts.
            }

            @Override
            public SpongeConfig loadConfig() {
                lssConfig = SpongeConfig.load(configDir);
                return lssConfig;
            }

            @Override
            public void registerChannels() {
                SpongeChannels.setReceiver(LSSSpongePlugin.this::onPluginMessageReceived);
            }

            @Override
            public void registerQuitListener() {
                // The disconnect listener is a method of this registered plugin instance.
            }

            @Override
            public SpongeRequestProcessingService startService(SpongeConfig config) {
                var nmsServer = (net.minecraft.server.MinecraftServer) event.engine();
                requestService = new SpongeRequestProcessingService(nmsServer, LSSSpongePlugin.this, config);
                return requestService;
            }

            @Override
            public void registerWorldHandler(SpongeRequestProcessingService service, SpongeConfig config) {
                Sponge.eventManager().registerListeners(container,
                        new SpongeWorldHandler(service.getDirtyTracker()),
                        java.lang.invoke.MethodHandles.lookup());
            }

            @Override
            public void registerCommands() {
                // Registered at RegisterCommandEvent, which fires before the server starts.
            }

            @Override
            public void scheduleServiceTick() {
                // The main thread is the plugin's single pump — every single-owner
                // structure in the pipeline hangs off this cadence.
                Sponge.server().scheduler().submit(Task.builder()
                        .plugin(container)
                        .interval(Ticks.single())
                        .execute(() -> {
                            var service = requestService;
                            if (service != null) {
                                service.tick();
                            }
                        })
                        .build());
            }

            @Override
            public void initSoakBridge() {
                // The soak harness drives Paper and Fabric only.
            }
        });
        this.enabled = true;
    }

    @Listener
    public void onServerStopping(StoppingEngineEvent<Server> event) {
        this.enabled = false;
        if (this.lssConfig != null) this.lssConfig.close();
        // Null the field BEFORE shutting down so the next pump fire no-ops.
        var service = this.requestService;
        this.requestService = null;
        // Static sidecar facts must not outlive the service (review C1-9).
        CLIENT_DATA_VERSIONS.clear();
        // Stop dispatching BEFORE shutdown (2026-08-05 review H5): a frame already
        // dispatched proceeds with its captured service reference (everything it touches
        // is individually thread-safe), but no NEW frame can dispatch concurrently with
        // the teardown below.
        SpongeChannels.setReceiver(null);
        Sponge.server().scheduler().tasks(this.container).forEach(task -> task.cancel());
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
        }

        LSSLogger.info(Brand.displayName() + " (Sponge) disabled");
    }

    /** Sponge loads worlds after startup too; each gets its region directory resolved. */
    @Listener
    public void onWorldLoaded(LoadWorldEvent event) {
        var service = this.requestService;
        if (service != null) service.onLevelLoaded((ServerLevel) (Object) event.world());
    }

    void onPluginMessageReceived(String channel, ServerPlayer nmsPlayer, byte[] message) {
        var service = this.requestService;
        if (service == null) return;
        if (message == null || message.length == 0) return;

        dispatchPluginMessage(channel, nmsPlayer.getName().getString(), message,
                data -> handleHandshake(nmsPlayer, data),
                data -> handleBatchChunkRequest(nmsPlayer, data),
                data -> CLIENT_DATA_VERSIONS.put(nmsPlayer.getUUID(),
                        SpongePayloadHandler.decodeClientInfo(data)),
                // Far players (E1): decode here (pure), apply on the PUMP via the
                // runtime-task queue — drained AFTER the lifecycle mailbox, so a prefs
                // frame racing its own Register lands post-registration.
                data -> {
                    var prefs = dev.vox.lss.common.farplayers.FarPlayerWire.decodePrefs(data);
                    var uuid = nmsPlayer.getUUID();
                    service.enqueueRuntimeTask(
                            () -> service.getFarPlayerService().onPrefs(uuid, prefs));
                },
                // Region summaries (P2 §5): offer into the latest-wins mailbox; the pump
                // reads player state at admission.
                data -> service.handleRegionSummaryRequest(nmsPlayer.getUUID(), data));
    }

    boolean isEnabled() {
        return this.enabled;
    }

    /** Runs the task on the main thread (the plugin's single pump). */
    void runOnMain(Runnable task) {
        Sponge.server().scheduler().submit(Task.builder().plugin(this.container).execute(task).build());
    }

    String version() {
        return this.container.metadata().version().toString();
    }

    static String spongeVersion() {
        return Sponge.platform().container(org.spongepowered.api.Platform.Component.IMPLEMENTATION)
                .metadata().version().toString();
    }

    /** Test seam: a per-channel message handler; hostile-frame decodes may throw. */
    @FunctionalInterface
    interface PluginMessageHandler {
        void handle(byte[] message) throws Exception;
    }

    /** Contained hostile-frame ERROR rate limit. Any authenticated client can spam malformed
     *  frames at packet rate on these channels, and an unthrottled stack trace per frame is a
     *  log-flood vector (Fabric self-limits — a bad codec decode kicks the client; Plugin
     *  Messaging has no equivalent). First frame logs immediately with the stack; later ones
     *  aggregate into a suppressed count released at most once per interval. Package-visible
     *  and swappable so the glue tests' one-ERROR-row containment pins stay deterministic. */
    static volatile LogThrottle hostileFrameLog = new LogThrottle(60_000);

    /**
     * Channel switch + exception containment for {@link #onPluginMessageReceived},
     * extracted so hostile-frame containment is testable without a player: one
     * malformed frame must be caught and logged — never propagate into Sponge's
     * channel dispatch — and later messages must still dispatch. Unknown channels are ignored.
     * Errors deliberately propagate (only Exception is contained).
     */
    static void dispatchPluginMessage(String channel, String playerName, byte[] message,
                                      PluginMessageHandler handshakeHandler,
                                      PluginMessageHandler chunkRequestHandler,
                                      PluginMessageHandler clientInfoHandler,
                                      PluginMessageHandler farPlayerPrefsHandler,
                                      PluginMessageHandler regionSummaryReqHandler) {
        try {
            switch (channel) {
                case LSSConstants.CHANNEL_HANDSHAKE -> handshakeHandler.handle(message);
                case LSSConstants.CHANNEL_CHUNK_REQUEST -> chunkRequestHandler.handle(message);
                case LSSConstants.CHANNEL_CLIENT_INFO -> clientInfoHandler.handle(message);
                case LSSConstants.CHANNEL_FAR_PLAYER_PREFS -> farPlayerPrefsHandler.handle(message);
                case LSSConstants.CHANNEL_REGION_SUMMARY_REQ -> regionSummaryReqHandler.handle(message);
            }
        } catch (Exception e) {
            long released = hostileFrameLog.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (released > 0) {
                String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                LSSLogger.error("Error handling plugin message on channel " + channel
                        + " from " + playerName + suffix, e);
            }
        }
    }

    /**
     * Test seam: the session-config reply send, production-wired to
     * {@link SpongePayloadHandler#sendSessionConfig} (CURRENT echoes {@code PROTOCOL_VERSION};
     * the V18 dialect echoes 18 on the same 4-field layout — the old client's gate
     * hard-requires its own version) or {@link SpongePayloadHandler#sendSessionConfigV16}
     * (V16 dialect — the legacy 6-field layout echoing protocol 16; the caps are the old
     * client's pacing) for the handshaking player.
     */
    @FunctionalInterface
    interface SessionConfigSender {
        void send(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                  int syncCap, int genCap, boolean generationEnabled);
    }

    /**
     * Test seam: player registration, production-wired to
     * {@link SpongeRequestProcessingService#enqueueRegister} — registration is mailboxed and
     * the pump applies it next tick. EVERY dialect identity mark (v16 and v18 alike) rides
     * that mailbox too, as the {@link #dialectFlipFor} runnable the pump runs during its
     * lifecycle drain — never directly from the handshake handler. (This javadoc used to
     * describe a pre-round-3 design where V16 marked its session identity directly; that
     * direct mark IS the hard-kick race the round-3 review fixed — a column egress
     * deciding its wire shape off a half-published identity — so do not reintroduce it.
     * 2026-08-05 review D1.) Only invoked when the {@link HandshakeGate} decision says to
     * register, so the production lambda may capture a service reference that is non-null
     * whenever servicePresent was true.
     */
    @FunctionalInterface
    interface HandshakeRegistrar {
        /** {@code replyAfterRegister} MUST run after the registration is applied (the pump's
         *  lifecycle drain in production) — never inline before it; see enqueueRegister. */
        void register(int capabilities, HandshakeGate.WireDialect dialect, Runnable replyAfterRegister);
    }

    private void handleHandshake(ServerPlayer nmsPlayer, byte[] data) {
        var service = this.requestService;
        // R4 (review 2026-08-27): every handshake stamps its connection's epoch BEFORE any
        // gate/summary state can be written for it — the late mailboxed Remove of a
        // PREVIOUS connection compares against this and skips its belts for a fast rejoiner.
        if (service != null) service.markConnection(nmsPlayer.getUUID());
        // XVER §7: capture Via's answer once per handshake (the && keeps a disabled
        // guard from ever triggering probe resolution); the pure seam applies the rule.
        int viaProtocol = this.lssConfig.enableViaMismatchGuard()
                ? dev.vox.lss.common.compat.ViaProbe.playerProtocol(nmsPlayer.getUUID())
                : dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL;
        handleHandshake(data, nmsPlayer.getName().getString(), nmsPlayer, this.lssConfig, service != null,
                viaProtocol, net.minecraft.SharedConstants.getProtocolVersion(),
                // Ticket #6: the per-player service gate, read inline in the handshake handler.
                serviceGateFor(node -> SpongePlayers.holds(nmsPlayer, node), nmsPlayer.getUUID(),
                        nmsPlayer.getName().getString(),
                        service == null ? null : service.getServiceGateState(),
                        service == null ? null
                                : () -> service.enqueueServiceGateUnregister(nmsPlayer.getUUID())),
                (dialect, enabled, lodDistanceChunks, syncCap, genCap, generationEnabled) -> {
                    if (service != null) generationEnabled = service.generationEnabledForSession();
                    // A cross-dialect re-handshake sheds the stale compat identities it is
                    // NOT — otherwise columns keep shipping the old dialect's shape and
                    // hard-kick the re-armed decoder. Placed on the sender seam because it
                    // fires for every replying outcome (REGISTER and NO_CONSUMER/DISABLED
                    // alike); reply-only sheds run inline in the handler, an inherited
                    // accepted residual (v18-compat design §2.3).
                    if (service != null) {
                        if (dialect != HandshakeGate.WireDialect.V16) {
                            service.getV16CompatManager().onNonV16Handshake(nmsPlayer.getUUID());
                        }
                        // Reply-only outcomes shed a stale CROSS-dialect membership; a
                        // REGISTER's mark happens on the pump via dialectFlipFor.
                        service.getDialectTracker().onNonRegisterHandshake(
                                nmsPlayer.getUUID(), dialect);
                    }
                    if (dialect == HandshakeGate.WireDialect.V16) {
                        SpongePayloadHandler.sendSessionConfigV16(nmsPlayer, enabled,
                                lodDistanceChunks, syncCap, genCap, generationEnabled);
                    } else {
                        SpongePayloadHandler.sendSessionConfig(nmsPlayer,
                                sessionConfigVersionFor(dialect),
                                enabled, lodDistanceChunks, generationEnabled);
                    }
                },
                (capabilities, dialect, replyAfterRegister) -> {
                    // The dialect flip runs on the PUMP, immediately before registerPlayer,
                    // via the mailbox's pre-register hook. Both halves of that placement are
                    // load-bearing:
                    //   * BEFORE registerPlayer, because that is where
                    //     wantsCompressedColumns is derived from isV16(). Doing it after —
                    //     which is what the sender seam amounts to for a REGISTER outcome,
                    //     since the drain runs replyAfterRegister last — left a v16 ->
                    //     current re-handshake running its whole session uncompressed.
                    //   * ON THE PUMP, not in the handshake handler. A flip applied there
                    //     takes effect instantly while the SessionConfig that re-arms the
                    //     client's decoder waits for the next drain, so a flush in between
                    //     could ship
                    //     new-dialect columns to a decoder still armed for the old one — a
                    //     malformed frame, and a disconnect. (Round-3 review; the first
                    //     bullet's fix originally introduced the second bullet's race.)
                    // All directions go through the hook so none can drift off-pump: each
                    // dialect marks its own identity and sheds the other's (the v18
                    // membership especially must NEVER be marked in the handler —
                    // v18-compat design §2.3, review F1).
                    service.enqueueRegister(nmsPlayer, capabilities,
                            dialectFlipFor(dialect, service.getV16CompatManager(),
                                    service.getDialectTracker(), nmsPlayer.getUUID()),
                            replyAfterRegister);
                });
    }

    /** The SessionConfig version echo per dialect (v18-compat design §2.4): the V18
     *  dialect echoes 18 on the CURRENT 4-field layout — the v0.8.x client's gate
     *  hard-requires its own version and self-disables on 19. Extracted static so the
     *  literal is pinnable (execution-review finding 1: the production lambda sat one
     *  seam above every test, and a silent V18->PROTOCOL_VERSION regression compiled
     *  clean). V16 never reaches this — it takes the 6-field legacy sender. */
    static int sessionConfigVersionFor(HandshakeGate.WireDialect dialect) {
        return switch (dialect) {
            case V18 -> LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
            case V19 -> LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
            case V16, CURRENT -> LSSConstants.PROTOCOL_VERSION;
        };
    }

    /** The pump-deferred dialect flip: mark the session's dialect in the single-map
     *  tracker (any cross-dialect shed is the overwrite itself) and create/shed the v16
     *  manager's ingress-shim session. Extracted static so the BODY is pinnable against
     *  real manager/tracker instances (execution-review finding 1: dropping the V18
     *  case's mark — which mis-derives wantsCompressedColumns and leaks the codec byte
     *  to every v0.8.x client — passed the whole suite). */
    static Runnable dialectFlipFor(HandshakeGate.WireDialect dialect,
                                   dev.vox.lss.common.compat.V16CompatManager v16,
                                   dev.vox.lss.common.compat.WireDialectTracker dialects,
                                   java.util.UUID uuid) {
        return () -> {
            dialects.onHandshake(uuid, dialect);
            if (dialect == HandshakeGate.WireDialect.V16) {
                // Session identity first, so drip batches merge from the first frame.
                v16.onHandshake(uuid);
            } else {
                v16.onNonV16Handshake(uuid);
            }
        };
    }

    /**
     * Handshake decode → {@link HandshakeGate} → reply/registration glue, extracted
     * behind the sender/registrar seams so call-site obedience is testable. Contract:
     * a VERSION_MISMATCH decision sends NOTHING (any reply would kick the skewed
     * client — see {@link HandshakeGate.Outcome#VERSION_MISMATCH}); NO_CONSUMER
     * replies but never registers; the reply advertises the gate's effectiveEnabled
     * and wires each config field to its session-config slot.
     */
    static void handleHandshake(byte[] data, String playerName, SpongeConfig config,
                                boolean servicePresent, SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, null, config, servicePresent, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                SpongeConfig config, boolean servicePresent,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        // No-Via-signal overload: every pre-C5 glue pin rides this unchanged. The
        // native slot gets 0 — a value no real MC protocol can be — never an LSS
        // protocol number (review m6: pairing NO_SIGNAL with PROTOCOL_VERSION was a
        // landmine one edit from denying everyone via 20 != <real via protocol>).
        handleHandshake(data, playerName, nmsPlayer, config, servicePresent,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0,
                configSender, registrar);
    }

    /** Pre-service-gate overload: rides {@link #SERVICE_GATE_OPEN}, i.e. the behavior of
     *  every build before {@code requireServicePermission} existed. Test/legacy only — the
     *  production call site passes a real {@link #serviceGateFor} gate. */
    static void handleHandshake(byte[] data, String playerName, SpongeConfig config,
                                boolean servicePresent, int viaProtocol, int nativeProtocol,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, null, config, servicePresent, viaProtocol, nativeProtocol,
                configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                SpongeConfig config, boolean servicePresent, int viaProtocol,
                                int nativeProtocol, SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, nmsPlayer, config, servicePresent, viaProtocol,
                nativeProtocol, SERVICE_GATE_OPEN, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, SpongeConfig config,
                                boolean servicePresent, int viaProtocol, int nativeProtocol,
                                PlayerServiceGate serviceGate,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, null, config, servicePresent, viaProtocol, nativeProtocol,
                serviceGate, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                SpongeConfig config, boolean servicePresent, int viaProtocol,
                                int nativeProtocol, PlayerServiceGate serviceGate,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        var handshake = SpongePayloadHandler.decodeHandshake(data);
        if (handshake == null) return;

        LSSLogger.info(Brand.shortName() + " handshake received from " + playerName
                + " (protocol v" + handshake.protocolVersion()
                + ", capabilities=" + handshake.capabilities() + ")");

        // Per-player service gate. It rides the SAME input the server-wide kill switch uses,
        // so a denied player takes the already-pinned DISABLED path verbatim: a SessionConfig
        // advertising enabled=false (in the client's OWN dialect) and no registration. That
        // shape — not silence — is deliberate: silence is the version-skew signal and sends
        // the client's discovery ladder into its retry rungs, while enabled=false is the
        // clean disarm ClientSessionGate already implements. Evaluated BEFORE the ladder
        // because the ladder is pure; the log below waits until the reply-bearing rungs.
        boolean serviceDenied = config.requireServicePermission()
                && !holdsServicePermission(serviceGate);

        var decision = HandshakeGate.evaluate(handshake.protocolVersion(),
                handshake.capabilities(), config.enabled() && !serviceDenied, servicePresent,
                config.enableV16Compat(), config.enableV18Compat(), config.enableV19Compat(),
                dev.vox.lss.common.compat.ViaProbe.isMismatch(viaProtocol, nativeProtocol));

        if (decision.outcome() == HandshakeGate.Outcome.VIA_MISMATCH) {
            // Silent deny — "Minecraft protocol" to keep the number space distinct
            // from the LSS protocol the handshake INFO above prints (review m5). Like
            // VERSION_MISMATCH below, an EXISTING registration deliberately survives
            // (review m1): the reachable window is a no-signal FIRST handshake (Via
            // mid-init) that registered legacy, then a later denial — bounded to that
            // race, healed by rejoin (the re-attach prompt loop this can enter is
            // 1/minute-bounded; see sendReattachPromptPayload).
            LSSLogger.info("LOD unavailable for " + playerName
                    + ": Via reports client Minecraft protocol " + viaProtocol
                    + " vs server " + nativeProtocol + " (cross-MC legacy session"
                    + " cannot be served) — the client must update "
                    + Brand.shortName());
            return;
        }
        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            // An EXISTING registration deliberately survives this rung (and NO_CONSUMER):
            // only a hostile/buggy client re-handshakes cross-capability on a live
            // connection, and a stray duplicate frame must not kill a working stream.
            // The NO_CONSUMER keeps-registration shape is pinned (ServiceLifecycleGameTests,
            // Fabric); the mismatch-survives shape follows from the same early return but
            // is argued, not pinned. Accepted residual: such a client keeps receiving
            // columns it just disclaimed, bounded to its own connection.
            LSSLogger.warn("Player " + playerName
                    + " has incompatible " + Brand.shortName() + " protocol version " + handshake.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        // Anchored on the DECISION, not merely on serviceDenied: the line (and the
        // once-per-session claim it burns) may fire only where the missing grant is what
        // actually took the service away.
        //   * outcome DISABLED, not NO_CONSUMER: a capability-less client is classified by
        //     the rung ABOVE the enabled check, so it is denied for having no LOD consumer
        //     whatever its permissions. Logging there would double up with the NO_CONSUMER
        //     line below AND spend the session's one release on a handshake the gate never
        //     decided — the client's later, consumer-bearing handshake would then be denied
        //     in silence.
        //   * config.enabled() + servicePresent: with LSS off server-wide (or its service
        //     absent) the player is dark regardless, so naming a permission would send the
        //     admin hunting a grant that changes nothing.
        //   * past the two silent rungs (above): a version/Via denial is a protocol skew,
        //     not a missing grant.
        boolean deniedByServiceGate = serviceDenied && config.enabled() && servicePresent
                && decision.outcome() == HandshakeGate.Outcome.DISABLED;
        if (deniedByServiceGate && serviceGate.claimDenialLog()) {
            LSSLogger.info("LOD unavailable for " + playerName
                    + ": requireServicePermission is on and this player does not hold both "
                    + PERMISSION_SERVICE_LSS + " and " + PERMISSION_SERVICE_VSS
                    + " (a negative grant on either spelling denies) — the client was told"
                    + " " + Brand.shortName() + " is disabled and will stop asking. Restore"
                    + " both nodes to serve this player.");
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        boolean v18 = decision.dialect() == HandshakeGate.WireDialect.V18;
        int lodDistance = SpongeWorldLod.distance(config, nmsPlayer);
        Runnable reply = () -> configSender.send(decision.dialect(),
                decision.effectiveEnabled(),
                lodDistance,
                // The caps ARE the old client's pacing — the server's real admission values
                // (ignored by the V18 sender branch; see the v16 compat design §4.1).
                LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                config.generationLimits().perPlayer(),
                config.enableChunkGeneration());

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // Reply-only outcome: no state will exist, so the inline reply cannot race it.
            reply.run();
            // Visible to admins via this log.
            LSSLogger.info("Player " + playerName
                    + " has no LOD consumer (caps=" + handshake.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            // REGISTERING outcome: the reply is DEFERRED into the registration so the
            // client cannot declare before its state exists (the pre-registration drop,
            // soak-diagnosed 2026-07-27 — the pump applies registrations next tick, and a
            // SessionConfig sent from here invited a first want-set into the gap, dropped
            // uncounted).
            registrar.register(handshake.capabilities(), decision.dialect(), reply);
            LSSLogger.info("Player " + playerName
                    + " registered for " + Brand.shortName() + " LOD request processing (caps="
                    + handshake.capabilities()
                    + (v16 ? ", v16-compat" : "") + (v18 ? ", v18-compat" : "") + ")");
        } else {
            // Reply-without-register (e.g. DISABLED advertisement): nothing to race.
            reply.run();
            if (deniedByServiceGate) {
                // AFTER the reply: the enabled=false config is the client's disarm; the
                // hook deposits the denied-handshake memo (re-offerable on a later
                // grant) and pump-marshals the unregistration composite for a live
                // session re-handshaking after a revocation (an ADMIN fact, unlike the
                // protocol facts an existing registration deliberately survives).
                serviceGate.onServiceDenied(handshake.protocolVersion(), handshake.capabilities());
            }
        }
    }

    /**
     * The grant sweep's re-offer entry (service-permission-gate-plan.md §2.3): rebuilds
     * the remembered handshake frame and drives it through the PRODUCTION receiver body
     * — the full ladder re-runs (version, Via, consumer, enabled, gate), the reply
     * lands in the client's own dialect, and a REGISTER outcome takes the registrar's
     * DEFERRED reply (the pre-registration gap stays closed). Called on the pump;
     * the player reference was freshly resolved by the sweep this tick.
     */
    void replayServiceGateHandshake(ServerPlayer nmsPlayer,
                                    ServiceGateState.DeniedHandshake remembered) {
        handleHandshake(nmsPlayer,
                SpongePayloadHandler.encodeHandshakeFrame(
                        remembered.protocolVersion(), remembered.capabilities()));
    }

    private void handleBatchChunkRequest(ServerPlayer nmsPlayer, byte[] data) {
        var decoded = SpongePayloadHandler.decodeBatchChunkRequest(data);
        if (decoded == null) return;
        var service = this.requestService;
        if (service != null) {
            service.handleBatchRequest(nmsPlayer, decoded);
        }
    }

    @Listener
    public void onPlayerQuit(ServerSideConnectionEvent.Disconnect event) {
        // No profile = the connection never finished logging in, so nothing was registered.
        var profile = event.profile().orElse(null);
        if (profile == null) return;
        var service = this.requestService;
        if (service != null) {
            // Mailboxed: removal mutates pump-owned state (generation service maps among others).
            service.enqueueRemove(profile.uuid());
            // Network-level and immediate (both structures are any-thread safe): the quit
            // drops the compat session identities; the mailboxed removePlayer above only
            // resets a want-set that no longer exists — a no-op. The v18 membership is
            // ALSO dropped by the mailbox Remove drain (the quit-race leak guard —
            // v18-compat design §2.3).
            service.getV16CompatManager().onDisconnect(profile.uuid());
            service.getDialectTracker().onDisconnect(profile.uuid());
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        CLIENT_DATA_VERSIONS.remove(profile.uuid());
        // Service gate: the denied-handshake memo, the denial-log latch, and the
        // revocation streak are swept by the EPOCH-GUARDED mailbox Remove drain
        // (enqueueRemove above), NOT here, matching the platform twins: a same-UUID
        // reconnection may already have deposited a fresh denial memo, and an un-guarded
        // sweep would wipe the successor's memo and strand a disarmed rejoiner with no
        // re-offer (the R4 belt exists precisely to skip the sweep when a newer
        // connection exists).
    }

    public SpongeRequestProcessingService getRequestService() {
        return this.requestService;
    }

    public SpongeConfig getLssConfig() {
        return this.lssConfig;
    }
}
