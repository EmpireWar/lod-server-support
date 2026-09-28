package dev.vox.lssfixture.wi5;

import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.config.menu.ClientOptionCatalog;
import dev.vox.lss.config.menu.OptionSpec.BoolSpec;
import dev.vox.lss.networking.client.ClientNetGlue;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** External marker-controlled draft-save/reload driver. Never alters bridge state or cadence. */
public final class Probe {
    private static final Logger LOG = LoggerFactory.getLogger("LSS-WI5-Fixture");
    private static Path dir;
    private static int phase; // 0 idle, 1 await positive premise, 2 OFF, 3 ON negotiation, 4 complete
    private static long ticks, armedAt, offAt, written, drops, flushes, received;
    private static boolean failed, drained, reconciled;
    private static String beforeReconcile;
    private static Object level, connection, oldManager, bridge;
    private static String worldId, subKey;
    private Probe() {}

    private static long count(String diag, String key) {
        Matcher match = Pattern.compile("(?:^|, )" + Pattern.quote(key) + "=(\\d+)(?:,|$)").matcher(diag);
        if (!match.find()) throw new IllegalStateException("Missing diagnostic " + key);
        return Long.parseLong(match.group(1));
    }
    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static Object bridge() throws ReflectiveOperationException {
        Field field = Class.forName("dev.vox.lss.compat.XaeroSession").getDeclaredField("instance");
        field.setAccessible(true);
        return field.get(null);
    }
    private static String subKey(Object manager) throws ReflectiveOperationException {
        Method method = manager.getClass().getDeclaredMethod("worldSubKeySnapshot");
        method.setAccessible(true);
        return String.valueOf(method.invoke(manager));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static String snapshot() {
        return "receive=" + LSSClientConfig.CONFIG.receiveServerLods()
                + " enabled=" + ClientNetGlue.isServerEnabled()
                + " sessionConfig=" + ClientNetGlue.hasReceivedSessionConfig()
                + " version=" + ClientNetGlue.getSessionVersion()
                + " manager=" + System.identityHashCode(ClientNetGlue.getRequestManager())
                + " received=" + ClientNetGlue.getColumnsReceived()
                + " decodeQueued=" + ClientNetGlue.getQueuedColumnCount()
                + " " + ModCompat.xaeroDiagLine();
    }
    private static void log(String event, String detail) throws java.io.IOException {
        String line = Instant.now() + " [WI5-FIXTURE] " + event + " tick=" + ticks + " " + detail;
        LOG.info("{}", line);
        Files.writeString(dir.resolve("lss-wi5-fixture.log"), line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void apply(boolean enabled) throws java.io.IOException {
        // Exercise the real disk-draft binding, then the same explicit reload as the command.
        var config = LSSClientConfig.CONFIG;
        var active = config.snapshot();
        Object manager = ClientNetGlue.getRequestManager();
        var draft = config.edits();
        draft.open(new Object());
        BoolSpec option = (BoolSpec) ClientOptionCatalog.find(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS).orElseThrow();
        option.setter().accept(draft, enabled);
        option.saveHook().run(draft);
        require(!draft.hasRetainedEdits() && draft.bool("lod.receive") == enabled, "Draft save failed");
        require(config.snapshot() == active && ClientNetGlue.getRequestManager() == manager,
                "Menu SaveHook activated the saved draft before reload");
        log(enabled ? "SAVED_ON_INERT" : "SAVED_OFF_INERT", "requested=" + enabled + " " + snapshot());
        phase = enabled ? 6 : 5;
        reconciled = false;
        armedAt = System.nanoTime();
        config.reload(message -> {
            if (message.getContents() instanceof TranslatableContents contents) {
                String key = contents.getKey();
                if (key.equals("lss.settings.reloaded")) {
                    if (!reconciled) { failObservation(new IllegalStateException("Reload acknowledged without owner reconciliation witness")); return; }
                    try { log(enabled ? "RELOAD_ON_ACK" : "RELOAD_OFF_ACK", snapshot()); }
                    catch (java.io.IOException error) { failObservation(error); return; }
                    phase = enabled ? 3 : 2;
                    armedAt = System.nanoTime();
                } else if (key.equals("lss.settings.reload_failed") || key.equals("lss.settings.reload_busy")
                        || key.equals("lss.settings.reload_unchanged"))
                    failObservation(new IllegalStateException("Explicit reload did not adopt the changed draft: " + key));
            }
        });
    }
    /** Test-only mixin samples the actual client-owner adoption boundary. */
    public static void beforeSettingsReconcile() {
        if (failed || phase != 5 && phase != 6) return;
        try {
            sameWorld(Minecraft.getInstance());
            beforeReconcile = ModCompat.xaeroDiagLine();
            if (phase == 5) {
                require(count(beforeReconcile, "pending_updates") > 0, "Committed native debt premise expired before reload");
                written = count(beforeReconcile, "written");
                drops = count(beforeReconcile, "dropped_updates");
                flushes = count(beforeReconcile, "frame_flushes");
                received = ClientNetGlue.getColumnsReceived();
            }
        } catch (Throwable error) { failObservation(error); }
    }
    public static void afterSettingsReconcile() {
        if (failed || phase != 5 && phase != 6) return;
        try {
            sameWorld(Minecraft.getInstance());
            if (phase == 5) {
                String after = ModCompat.xaeroDiagLine();
                require(!LSSClientConfig.CONFIG.receiveServerLods() && ClientNetGlue.getRequestManager() == null,
                        "Explicit OFF reload did not retire manager on the owner");
                require(count(after, "queued") == 0 && count(after, "owed") == 0,
                        "Explicit OFF reload retained acquisition queue/debt");
                require(count(after, "pending_updates") == count(beforeReconcile, "pending_updates"),
                        "Explicit OFF reload discarded committed native pending updates");
                require(count(after, "dropped_updates") == drops, "Explicit OFF reload dropped native updates");
                log("AFTER_OFF", snapshot());
                offAt = System.nanoTime();
            } else {
                require(LSSClientConfig.CONFIG.receiveServerLods(), "Explicit ON reload left reception disabled");
                require(!ClientNetGlue.hasReceivedSessionConfig(), "ON did not begin fresh real negotiation");
                log("AFTER_ON", snapshot());
            }
            reconciled = true;
        } catch (Throwable error) { failObservation(error); }
    }
    private static void failObservation(Throwable error) {
        failed = true;
        LOG.error("[WI5-FIXTURE] FAIL tick=" + ticks + " phase=" + phase, error);
        try { if (dir != null) log("FAIL", error.toString()); } catch (Throwable ignored) {}
    }
    private static void sameWorld(Minecraft mc) throws ReflectiveOperationException {
        require(mc.level == level && mc.getConnection() == connection, "Native world/connection changed");
        require(bridge() == bridge, "Xaero bridge instance changed");
        require(Objects.equals(worldId, read(bridge, "lastWorldId")), "Xaero world identity changed");
    }
    public static void tick() {
        if (System.getProperty("lss.rig.runId", "").isBlank()) return;
        if (failed || phase == 4) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (dir == null) {
                dir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                log("READY", "markerRoot=" + dir + " hooks=Minecraft.tick.HEAD/catalog.draft/SaveHook.run/explicit.reload/owner.reconcile");
            }
            ticks++;
            if (phase == 5 || phase == 6) {
                if (System.nanoTime() - armedAt > 30_000_000_000L)
                    throw new IllegalStateException("Explicit reload deadline exceeded");
                return;
            }
            if (phase == 0) {
                if (!Files.isRegularFile(dir.resolve("lss-wi5-arm-off"))) return;
                require(!Files.exists(dir.resolve("lss-wi5-arm-on")), "Stale ON marker exists before OFF arming");
                phase = 1;
                armedAt = System.nanoTime();
                log("ARMED", snapshot());
            }
            if (phase == 1) {
                if (System.nanoTime() - armedAt > 180_000_000_000L) {
                    log("PRECONDITION_TIMEOUT", snapshot());
                    phase = 4;
                    return;
                }
                String before = ModCompat.xaeroDiagLine();
                if (mc.level == null || mc.getConnection() == null || !LSSClientConfig.CONFIG.receiveServerLods()
                        || !ClientNetGlue.isServerEnabled() || !ClientNetGlue.hasReceivedSessionConfig()
                        || ClientNetGlue.getRequestManager() == null || before == null
                        || !before.startsWith("XaeroMap: state=active")
                        || count(before, "queued") <= 0 || count(before, "pending_updates") <= 0) return;
                level = mc.level;
                connection = mc.getConnection();
                oldManager = ClientNetGlue.getRequestManager();
                bridge = bridge();
                require(bridge != null, "Positive Xaero diagnostics lack bridge");
                worldId = (String) read(bridge, "lastWorldId");
                require(worldId != null, "Positive Xaero premise lacks native world ID");
                subKey = subKey(oldManager);
                written = count(before, "written");
                drops = count(before, "dropped_updates");
                flushes = count(before, "frame_flushes");
                received = ClientNetGlue.getColumnsReceived();
                log("PRECONDITION", "nativeWorld=" + System.identityHashCode(level)
                        + " connection=" + System.identityHashCode(connection) + " xaeroWorld=" + worldId
                        + " subKey=" + subKey + " " + snapshot());
                apply(false);
                return;
            }
            if (phase == 2) {
                sameWorld(mc);
                String diag = ModCompat.xaeroDiagLine();
                require(!LSSClientConfig.CONFIG.receiveServerLods(), "External reception change while fixture OFF");
                require(ClientNetGlue.getRequestManager() == null, "Manager resumed before ON marker");
                require(count(diag, "queued") == 0 && count(diag, "owed") == 0, "OFF acquisition queue/debt returned");
                require(count(diag, "written") == written, "New map writes occurred while reception OFF");
                require(count(diag, "dropped_updates") == drops, "Native pending work dropped while OFF");
                if (!drained && count(diag, "pending_updates") == 0 && count(diag, "frame_flushes") > flushes) {
                    drained = true;
                    log("OFF_NATIVE_REBUILDS_DRAINED", snapshot());
                }
                if (ticks % 100 == 0) log("OFF_OBSERVE", snapshot());
                if (!Files.isRegularFile(dir.resolve("lss-wi5-arm-on"))) return;
                // Keep the marker pending until native retained work naturally drains.
                if (!drained) return;
                log("BEFORE_ON", "offMillis=" + (System.nanoTime() - offAt) / 1_000_000 + " " + snapshot());
                apply(true);
                return;
            }
            if (phase == 3) {
                sameWorld(mc);
                require(LSSClientConfig.CONFIG.receiveServerLods(), "External reception change during ON negotiation");
                if (System.nanoTime() - armedAt > 180_000_000_000L) {
                    log("RESUME_TIMEOUT", snapshot());
                    phase = 4;
                    return;
                }
                Object manager = ClientNetGlue.getRequestManager();
                if (manager == null || !ClientNetGlue.isServerEnabled() || !ClientNetGlue.hasReceivedSessionConfig()) return;
                require(manager != oldManager, "ON reused retired manager");
                require(Objects.equals(subKey, subKey(manager)), "ON changed server world/cache sub-key");
                String diag = ModCompat.xaeroDiagLine();
                if (ClientNetGlue.getColumnsReceived() > received && count(diag, "written") > written) {
                    log("PASS_SAME_WORLD_OFF_ON", "sameNativeWorld=true sameXaeroWorld=true sameConnection=true"
                            + " freshManager=true nativeRebuildsDrained=true " + snapshot());
                    phase = 4;
                } else if (ticks % 100 == 0) log("ON_NEGOTIATED_AWAIT_WRITES", snapshot());
            }
        } catch (Throwable error) {
            failObservation(error);
            // Observation driver fails closed; it never repairs or retries bridge state.
        }
    }
}
