package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.RequestRegistration;
import dev.vox.lss.common.processing.TickSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages chunk generation requests for the Sponge plugin. Sponge runs vanilla's chunk
 * system (no Moonrise), so this follows the Fabric twin: a loading ticket per admitted
 * column, polled on the main tick until the chunk is FULL, then serialized and released.
 * Everything here runs on the main thread, which is also the service pump.
 */
public class SpongeChunkGenerationService {

    record GenerationCallback(UUID playerUuid, RequestRegistration registration, long submissionOrder) {}

    // Package-visible (with launchAsyncLoad/onChunkReady) so tests can drive loads directly.
    record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}

    static class ActiveGeneration {
        // Monotonic id of this launch: a completion carrying an older token (its entry timed
        // out and the column was resubmitted) must not consume the fresh entry.
        final long token;
        final ServerLevel level;
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;
        int timeoutTicks;

        ActiveGeneration(long token, ServerLevel level) {
            this.token = token;
            this.level = level;
        }
    }

    // Vanilla loading ticket, the Fabric twin's: kept until the chunk is FULL, then released.
    private static final TicketType LSS_GEN_TICKET =
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    private long nextGenerationToken = 0;

    private final LinkedHashMap<PendingGenerationKey, ActiveGeneration> active = new LinkedHashMap<>();
    private final Map<RequestRegistration, Integer> perPlayerActiveCount = new HashMap<>();
    private List<TickSnapshot.GenerationReadyData> mainReady = new ArrayList<>();
    // Releasing many tickets in one tick re-runs the distance graph for each (the
    // disconnect freeze), so releases that are not completions drain a few per tick.
    private final DeferredTicketReleases deferredReleases = new DeferredTicketReleases();

    // Reload policy updates and submit/tick run on the same pump, so plain fields suffice.
    private int maxConcurrent;
    private int maxPerPlayerActive;
    private int timeoutTicks;
    private boolean admissionEnabled;
    private long policyRevision;

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;
    private volatile long totalRemovedInFlight = 0;

    public SpongeChunkGenerationService(SpongeConfig config) {
        var generationLimits = config.generationLimits();
        this.maxConcurrent = generationLimits.global();
        this.maxPerPlayerActive = generationLimits.perPlayer();
        this.timeoutTicks = config.generationTimeoutTicks();
        this.admissionEnabled = config.enableChunkGeneration();
    }

    public void updatePolicy(boolean enabled, int global, int perPlayer, int timeoutTicks, long revision) {
        if (revision < this.policyRevision) return;
        this.admissionEnabled = enabled;
        this.timeoutTicks = timeoutTicks;
        this.policyRevision = revision;
        updateCaps(global, perPlayer);
    }

    /** Runtime cap change: lowering never cancels in-flight generations — it only gates
     *  NEW admissions. */
    public void updateCaps(int global, int perPlayer) {
        this.maxConcurrent = global;
        this.maxPerPlayerActive = perPlayer;
    }

    /**
     * Submit a generation request. Returns true if accepted (piggyback or new active slot),
     * false if at capacity (caller should feed back a rejection result).
     */
    public boolean submitGeneration(UUID playerUuid, RequestRegistration registration, ServerLevel level, int cx, int cz, long submissionOrder) {
        if (!this.admissionEnabled || registration.isRetired()) return false;
        var key = new PendingGenerationKey(level.dimension(), cx, cz);

        // Already loading — piggyback on it
        var existing = this.active.get(key);
        if (existing != null) {
            existing.callbacks.add(new GenerationCallback(playerUuid, registration, submissionOrder));
            incrementCount(this.perPlayerActiveCount, registration);
            return true;
        }

        int playerActive = this.perPlayerActiveCount.getOrDefault(registration, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var gen = new ActiveGeneration(++this.nextGenerationToken, level);
            gen.timeoutTicks = this.timeoutTicks;
            gen.callbacks.add(new GenerationCallback(playerUuid, registration, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, registration);
            this.totalSubmitted++;
            launchAsyncLoad(key, level, cx, cz, gen.token);
            return true;
        }

        // At capacity — reject. The client's next declaration asks again.
        return false;
    }

    /** Adds the loading ticket; {@link #tick} polls for the chunk. Test seam. */
    void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz, long token) {
        if (!this.deferredReleases.cancel(key)) {
            level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, new ChunkPos(cx, cz), 0);
        }
    }

    private void releaseLater(PendingGenerationKey key, ServerLevel level) {
        var pos = new ChunkPos(key.cx(), key.cz());
        this.deferredReleases.defer(key,
                () -> level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, pos, 0));
    }

    /** Serializes a loaded chunk; null when extraction throws (logged). */
    LoadedColumnData extractColumnData(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        try {
            return SpongeSectionSerializer.serializeColumn(level, chunk, cx, cz);
        } catch (Exception e) {
            LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, e);
            return null;
        }
    }

    /**
     * Books for one finished load: hands the column (or, when {@code columnData} is null, a
     * permanent failure) to every callback. A null column answers NOT_GENERATED — a chunk
     * that cannot be serialized must not be hammered — and a dirty broadcast revives it.
     * Package-visible so tests can complete loads directly.
     */
    void onChunkReady(PendingGenerationKey key, LoadedColumnData columnData, int cx, int cz, long token) {
        var gen = this.active.get(key);
        // null = cleaned up by removePlayer/timeout; another token = a resubmitted entry
        if (gen == null || gen.token != token) return;
        this.active.remove(key);

        if (columnData != null) {
            long columnTimestamp = LSSConstants.epochSeconds();
            String dimension = key.dimension().identifier().toString();
            for (var cb : gen.callbacks) {
                this.mainReady.add(new TickSnapshot.GenerationReadyData(
                        cb.playerUuid, cb.registration, cx, cz, dimension,
                        columnData, columnTimestamp, cb.submissionOrder));
                decrementCount(this.perPlayerActiveCount, cb.registration);
            }
            this.totalCompleted++;
        } else {
            addFailures(gen.callbacks, key, cx, cz, false);
            this.totalRemovedInFlight++;
        }
    }

    /**
     * Adds a failure outcome (columnData == null) for every callback. Callers that removed
     * the active entry must also count it exactly once — totalTimeouts or
     * totalRemovedInFlight — so the books (submitted == completed + timeouts + removed)
     * balance. {@code transientFailure} picks the wire disposition downstream: true = silent
     * drop (timeout, the client asks again), false = NOT_GENERATED.
     */
    private void addFailures(List<GenerationCallback> callbacks, PendingGenerationKey key,
                             int cx, int cz, boolean transientFailure) {
        String dimension = key.dimension().identifier().toString();
        for (var cb : callbacks) {
            this.mainReady.add(new TickSnapshot.GenerationReadyData(
                    cb.playerUuid, cb.registration, cx, cz, dimension, null, 0L, cb.submissionOrder, transientFailure));
            decrementCount(this.perPlayerActiveCount, cb.registration);
        }
    }

    /** Completes loads whose chunk is ready, expires stalled ones, and returns the outcomes. */
    public List<TickSnapshot.GenerationReadyData> tick() {
        this.deferredReleases.drain(DeferredTicketReleases.MAX_RELEASES_PER_TICK);
        tickActive();

        if (this.mainReady.isEmpty()) return List.of();
        var ready = this.mainReady;
        this.mainReady = new ArrayList<>();
        return ready;
    }

    private record Loaded(PendingGenerationKey key, ActiveGeneration gen, LevelChunk chunk) {}

    private void tickActive() {
        if (this.active.isEmpty()) return;
        List<Loaded> loaded = null;
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var key = entry.getKey();
            var gen = entry.getValue();
            gen.ticksWaiting++;

            LevelChunk chunk = gen.level.getChunkSource().getChunkNow(key.cx(), key.cz());
            if (chunk != null) {
                // Completed below: onChunkReady removes the entry, which would break this iterator
                if (loaded == null) loaded = new ArrayList<>();
                loaded.add(new Loaded(key, gen, chunk));
            } else if (gen.ticksWaiting > gen.timeoutTicks) {
                // Timeout is TRANSIENT (a starved load is routine on a busy server): silent
                // drop downstream, the client's re-declaration retries.
                addFailures(gen.callbacks, key, key.cx(), key.cz(), true);
                iter.remove();
                releaseLater(key, gen.level);
                this.totalTimeouts++;
            }
        }
        if (loaded == null) return;

        Error error = null;
        for (var load : loaded) {
            LoadedColumnData columnData = null;
            try {
                columnData = extractColumnData(load.gen.level, load.chunk, load.key.cx(), load.key.cz());
            } catch (Error e) {
                // Books first, so no slot leaks; the Error still surfaces after this tick's work
                LSSLogger.error("Failed to extract primitives for generated chunk at "
                        + load.key.cx() + ", " + load.key.cz(), e);
                if (error == null) error = e;
            }
            // Served (or failed) now: the ticket has done its job
            load.gen.level.getChunkSource().removeTicketWithRadius(
                    LSS_GEN_TICKET, new ChunkPos(load.key.cx(), load.key.cz()), 0);
            onChunkReady(load.key, columnData, load.key.cx(), load.key.cz(), load.gen.token);
        }
        if (error != null) throw error;
    }

    public void removePlayer(UUID playerUuid, RequestRegistration registration) {
        // Clean active callbacks first — decrementCount needs perPlayerActiveCount to still exist
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var gen = entry.getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid) && cb.registration == registration);
            if (gen.callbacks.isEmpty()) {
                iter.remove();
                releaseLater(entry.getKey(), gen.level);
                // Submitted but neither completed nor timed out — without this counter the
                // books can never re-balance after a kick or dimension change
                this.totalRemovedInFlight++;
            }
        }

        this.perPlayerActiveCount.remove(registration);
    }

    public void shutdown() {
        this.deferredReleases.flush(); // correctness over smoothness — never strand a ticket
        for (var entry : this.active.entrySet()) {
            var key = entry.getKey();
            entry.getValue().level.getChunkSource().removeTicketWithRadius(
                    LSS_GEN_TICKET, new ChunkPos(key.cx(), key.cz()), 0);
        }
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.mainReady.clear();
    }

    /** Capture on the service owner; admitted work may still be draining when false. */
    public boolean isAdmissionEnabled() { return admissionEnabled; }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts, totalRemovedInFlight);
    }

    public long getTotalSubmitted() { return this.totalSubmitted; }

    public long getTotalCompleted() { return this.totalCompleted; }

    public long getTotalTimeouts() { return this.totalTimeouts; }

    public long getTotalRemovedInFlight() { return this.totalRemovedInFlight; }

    /** Pump thread for exact values; command threads read racily (stale-tolerable admin
     *  diagnostics — never iterate {@code active} off-pump, size() is a plain field read). */
    public int getActiveCount() { return this.active.size(); }

    private static void incrementCount(Map<RequestRegistration, Integer> map, RequestRegistration registration) {
        map.merge(registration, 1, Integer::sum);
    }

    private static void decrementCount(Map<RequestRegistration, Integer> map, RequestRegistration registration) {
        var count = map.get(registration);
        if (count != null) {
            if (count <= 1) map.remove(registration);
            else map.put(registration, count - 1);
        }
    }
}
