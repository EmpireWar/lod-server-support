package dev.vox.lss.sponge;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages chunk generation requests for the Sponge plugin, ported from the Paper twin.
 * Sponge runs vanilla's chunk system (no Moonrise), so generation uses the Fabric twin's
 * shape: a loading ticket per admitted column, polled on the main tick until the chunk is
 * FULL, then serialized and released. Everything here runs on the main thread.
 */
public class SpongeChunkGenerationService {

    record GenerationCallback(UUID playerUuid, RequestRegistration registration, long submissionOrder) {}

    // Package-visible (with launchAsyncLoad/onChunkReady) so T1 tests can drive the async
    // boundary directly.
    record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}

    /**
     * Tracks an active async chunk load. The async future fires {@link #onChunkReady}
     * when Paper finishes loading/generating the chunk to FULL status.
     */
    static class ActiveGeneration {
        // Monotonic id of this launch. A stale async completion (from an entry that already
        // timed out and was resubmitted under the same key) carries the OLD token, so
        // onChunkReady can reject it and leave the current entry for its own completion —
        // otherwise the stale (possibly failed) result consumes the fresh entry by key match.
        final long token;
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;
        int timeoutTicks;

        final ServerLevel level;
        ActiveGeneration(long token, ServerLevel level) { this.token = token; this.level = level; }
    }

    // Main-thread-owned: assigns a unique token to each ActiveGeneration launch.
    private long nextGenerationToken = 0;

    private final LinkedHashMap<PendingGenerationKey, ActiveGeneration> active = new LinkedHashMap<>();
    private final Map<RequestRegistration, Integer> perPlayerActiveCount = new HashMap<>();
    // Pump-thread-owned (onChunkReady is scheduled to the pump via the GlobalRegionScheduler;
    // tick() swaps it out on the same thread)
    private List<TickSnapshot.GenerationReadyData> mainReady = new ArrayList<>();

    // Reload policy updates and submit/tick run on the same service owner pump,
    // so plain fields suffice (the per-player genSlotCap is the volatile one).
    private int maxConcurrent;
    private int maxPerPlayerActive;
    private int timeoutTicks;
    private boolean admissionEnabled;
    private long policyRevision;

    public void updatePolicy(boolean enabled, int global, int perPlayer, int timeoutTicks, long revision) {
        if (revision < this.policyRevision) return;
        this.admissionEnabled = enabled;
        this.timeoutTicks = timeoutTicks;
        this.policyRevision = revision;
        updateCaps(global, perPlayer);
    }

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;
    private volatile long totalRemovedInFlight = 0;
    // Null-chunk (Moonrise permanent-failure) completions. Multi-writer on Folia (two region
    // threads can complete concurrently), so atomic: the boolean latches the one warning via CAS,
    // the counter is exact. Exposed in getDiagnostics()/getNullChunkFailures() for operators.
    private final AtomicBoolean nullChunkWarned = new AtomicBoolean(false);
    private final AtomicLong nullChunkFailures = new AtomicLong(0);
    // Vanished-in-the-completion-window (transient) — SEPARATE from the Moonrise-null
    // flavor (R2-8): the two regress independently and a shared latch/counter let either
    // suppress the other's warning and made the diag unable to distinguish them.
    private final AtomicBoolean vanishedWarned = new AtomicBoolean(false);
    private final AtomicLong vanishedFailures = new AtomicLong(0);

    // Vanilla loading ticket, the Fabric twin's: kept until the chunk is FULL, then released.
    private static final TicketType LSS_GEN_TICKET =
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    // Releasing many tickets in one tick re-runs the distance graph for each (the
    // disconnect freeze), so removals that are not completions drain a few per tick.
    private final DeferredTicketReleases deferredReleases = new DeferredTicketReleases();

    public SpongeChunkGenerationService(SpongeConfig config) {
        var generationLimits = config.generationLimits();
        this.maxConcurrent = generationLimits.global();
        this.maxPerPlayerActive = generationLimits.perPlayer();
        this.timeoutTicks = config.generationTimeoutTicks();
        this.admissionEnabled = config.enableChunkGeneration();
    }

    /** Runtime cap change (v0.11.0 stage C — the tick-poll pattern, twin of the Fabric
     *  method): called from the pump before admission. Lowering never cancels in-flight
     *  generations — it only gates NEW admissions. */
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

        // Already active — piggyback on existing async load
        var existingActive = this.active.get(key);
        if (existingActive != null) {
            existingActive.callbacks.add(new GenerationCallback(playerUuid, registration, submissionOrder));
            incrementCount(this.perPlayerActiveCount, registration);
            return true;
        }

        // Try to add directly to active and launch async load
        int playerActive = this.perPlayerActiveCount.getOrDefault(registration, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var gen = new ActiveGeneration(++this.nextGenerationToken, level);
            gen.timeoutTicks = this.timeoutTicks;
            gen.callbacks.add(new GenerationCallback(playerUuid, registration, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, registration);
            this.totalSubmitted++;

            // A timeout is a TRANSIENT outcome — the client re-declares and the load is
            // retried, never blanked NOT_GENERATED.
            launchAsyncLoad(key, level, cx, cz);
            return true;
        }

        // At capacity — reject. Client's retry loop will re-request later.
        return false;
    }

    /** Main thread. No Moonrise here: a loading ticket drives the chunk to FULL and
     *  {@link #tick} polls for it, as on Fabric. */
    void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz) {
        if (!this.deferredReleases.cancel(key)) {
            level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, new ChunkPos(cx, cz), 0);
        }
    }

    private void releaseLater(PendingGenerationKey key, ServerLevel level) {
        var pos = new ChunkPos(key.cx(), key.cz());
        this.deferredReleases.defer(key,
                () -> level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, pos, 0));
    }

    /**
     * The completion-thread extraction outcome. {@code data == null} is a failure;
     * {@code chunkVanished} splits its disposition — see {@link #extractColumnData}.
     */
    record ExtractionOutcome(LoadedColumnData data, boolean chunkVanished) {}

    /**
     * Serializes the just-loaded chunk on the completion thread; a null {@code data} on any
     * failure — the failure books happen on the pump when {@link #onChunkReady} sees it.
     * Errors propagate to the tick, which still releases the ticket.
     *
     * <p>Serializes the chunk Moonrise DELIVERED (R2-8): the old re-fetch via
     * {@code getChunkNow} re-opened the completion-window unload race this
     * completion-thread extraction exists to close — the delivered reference cannot
     * vanish. The instanceof guard is LOAD-BEARING, not decorative: a
     * ChunkStatus.FULL schedule should always deliver a {@code LevelChunk}, but there is
     * no contract pin for that, so any other shape falls back to the re-fetch, whose miss
     * stays the ONE transient flavor ({@code chunkVanished}). An extraction exception
     * stays permanent (a corrupt chunk must not be hammered), as does Moonrise's null.
     */
    ExtractionOutcome extractColumnData(ServerLevel level, ChunkAccess delivered, int cx, int cz) {
        try {
            LevelChunk nmsChunk = delivered instanceof LevelChunk lc ? lc
                    : level.getChunkSource().getChunkNow(cx, cz);
            if (nmsChunk == null) {
                // The fallback re-fetch missed (non-LevelChunk delivery + unload in the
                // window). TRANSIENT: the chunk was generated, it simply unloaded before we
                // could read it, so the next want-set declaration re-resolves it. Answering
                // the session-permanent NOT_GENERATED here would blank the column until
                // reconnect — and on Paper walk-in generation does not mark dirty by
                // default, so the dirty-broadcast revival never fires. Separate counter +
                // warn latch from the Moonrise-null flavor (R2-8): the two regress
                // independently and a shared latch let either suppress the other's warning.
                if (this.vanishedWarned.compareAndSet(false, true)) {
                    LSSLogger.warn("Chunk at " + cx + "," + cz + " vanished in the completion window"
                            + " — further vanished-chunk failures are logged silently (counted)");
                }
                this.vanishedFailures.incrementAndGet();
                return new ExtractionOutcome(null, true);
            }
            return new ExtractionOutcome(SpongeSectionSerializer.serializeColumn(level, nmsChunk, cx, cz), false);
        } catch (Exception e) {
            LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, e);
            return new ExtractionOutcome(null, false);
        }
    }

    /**
     * Called on the pump thread with the completion-thread's extraction result (null = the
     * load failed, the chunk vanished, or extraction threw). Books only — no chunk access.
     * Package-visible so tests can fire the completion that completeAsyncLoad would schedule.
     */
    void onChunkReady(PendingGenerationKey key, LoadedColumnData columnData,
                      int cx, int cz, long token) {
        onChunkReady(key, columnData, cx, cz, token, false);
    }

    /** @param transientFailure a null {@code columnData} that must NOT answer the
     *  session-permanent NOT_GENERATED (today: the chunk unloaded in the completion
     *  window — it generated fine and the next declaration re-resolves it). */
    void onChunkReady(PendingGenerationKey key, LoadedColumnData columnData,
                      int cx, int cz, long token, boolean transientFailure) {
        var gen = this.active.get(key);
        // Reject a stale completion: the entry that launched this load already timed out and a
        // new entry was resubmitted under the same key (different token). Leaving the current
        // entry untouched lets ITS own completion serve it — a stale (often failed) result must
        // not consume the fresh entry. null = the entry was cleaned up by removePlayer/timeout.
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
            // PERMANENT (the load failed or extraction threw — a corrupt chunk must not be
            // hammered): NOT_GENERATED, dirty broadcast revives. TRANSIENT (the chunk
            // unloaded in the completion window): silent drop + superseded, healed by the
            // next want-set declaration. The books are identical either way.
            addFailures(gen.callbacks, key, cx, cz, transientFailure);
            this.totalRemovedInFlight++;
        }
    }

    /**
     * Add a failure outcome (columnData == null) for every callback. Main thread only.
     * Callers that removed the active entry must also count it exactly once — totalTimeouts
     * on the timeout path, totalRemovedInFlight on the failure paths (mirrors the Fabric
     * twin) — so the generation books (submitted == completed + timeouts + removed) balance
     * (soak law A4). {@code transientFailure} picks the wire disposition downstream: true =
     * silent drop + superseded (timeout), false = ColumnNotGenerated (permanent — failed
     * load / extraction). The books are identical either way.
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

    /**
     * Tick the generation service. Returns generation-ready data for the processing thread to voxelize.
     */
    public List<TickSnapshot.GenerationReadyData> tick() {
        this.deferredReleases.drain(DeferredTicketReleases.MAX_RELEASES_PER_TICK);
        this.tickActive();

        if (this.mainReady.isEmpty()) return List.of();
        var ready = this.mainReady;
        this.mainReady = new ArrayList<>();
        return ready;
    }

    /** Main thread: completes loads whose chunk reached FULL, expires the rest. */
    private void tickActive() {
        if (this.active.isEmpty()) return;
        List<java.util.Map.Entry<PendingGenerationKey, ActiveGeneration>> loaded = null;
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var key = entry.getKey();
            var gen = entry.getValue();
            gen.ticksWaiting++;

            if (gen.level.getChunkSource().getChunkNow(key.cx(), key.cz()) != null) {
                if (loaded == null) loaded = new ArrayList<>();
                loaded.add(entry);
            } else if (gen.ticksWaiting > gen.timeoutTicks) {
                // Timeout is TRANSIENT (a starved load is routine on a busy server): silent
                // drop downstream, the client's re-declaration retries.
                addFailures(gen.callbacks, key, key.cx, key.cz, true);
                iter.remove();
                releaseLater(key, gen.level);
                this.totalTimeouts++;
            }
        }
        if (loaded == null) return;
        for (var entry : loaded) {
            var key = entry.getKey();
            var gen = entry.getValue();
            var chunk = gen.level.getChunkSource().getChunkNow(key.cx(), key.cz());
            ExtractionOutcome extracted;
            try {
                extracted = extractColumnData(gen.level, chunk, key.cx(), key.cz());
            } finally {
                // Served (or failed) now: the ticket has done its job, release it promptly.
                gen.level.getChunkSource().removeTicketWithRadius(
                        LSS_GEN_TICKET, new ChunkPos(key.cx(), key.cz()), 0);
            }
            onChunkReady(key, extracted.data(), key.cx(), key.cz(), gen.token, extracted.chunkVanished());
        }
    }

    public void removePlayer(UUID playerUuid, RequestRegistration registration) {
        // Clean active callbacks first — decrementCount needs perPlayerActiveCount to still exist
        var activeIter = this.active.entrySet().iterator();
        while (activeIter.hasNext()) {
            var entry = activeIter.next();
            var gen = entry.getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid) && cb.registration == registration);
            if (gen.callbacks.isEmpty()) {
                activeIter.remove();
                releaseLater(entry.getKey(), gen.level);
                // Submitted but neither completed nor timed out — without this counter the
                // submitted/completed books can never re-balance after a kick or dimension change
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
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d, null_failures=%d, vanished=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts, totalRemovedInFlight,
                nullChunkFailures.get(), vanishedFailures.get());
    }

    public long getTotalSubmitted() { return this.totalSubmitted; }

    /** Null-chunk (permanent-failure) completions — warn-once logged, counted here. */
    public long getNullChunkFailures() { return this.nullChunkFailures.get(); }
    public long getVanishedFailures() { return this.vanishedFailures.get(); }

    public long getTotalCompleted() { return this.totalCompleted; }

    public long getTotalTimeouts() { return this.totalTimeouts; }

    public long getTotalRemovedInFlight() { return this.totalRemovedInFlight; }

    /** Pump thread for exact values; command threads read racily (stale-tolerable admin
     *  diagnostics — never iterate {@code active} off-pump, size() is a plain field read). */
    public int getActiveCount() { return this.active.size(); }

    private static void incrementCount(Map<RequestRegistration, Integer> map, RequestRegistration uuid) {
        map.merge(uuid, 1, Integer::sum);
    }

    private static void decrementCount(Map<RequestRegistration, Integer> map, RequestRegistration uuid) {
        var count = map.get(uuid);
        if (count != null) {
            if (count <= 1) map.remove(uuid);
            else map.put(uuid, count - 1);
        }
    }
}
