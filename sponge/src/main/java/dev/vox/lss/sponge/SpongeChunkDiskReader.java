package dev.vox.lss.sponge;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Async region file reader for Paper. The shared base owns the executor, result queues,
 * and error triage; this class only captures the NMS handles (Paper uses Mojang mappings,
 * so no mixin accessor needed) and serializes via {@link SpongeNbtSectionSerializer}.
 */
public class SpongeChunkDiskReader extends AbstractChunkDiskReader {

    // Test seam: when set, replaces the NMS read (always null in production).
    // Volatile: set on the test thread, read on the submitting thread.
    private volatile SpongeNbtSectionSerializer.ChunkNbtRead readOverride;

    private volatile SerializationPolicy serializationPolicy;
    public record SerializationPolicy(boolean transcode, boolean selective) {}
    public void updateSerializationPolicy(boolean transcode, boolean selective) {
        this.serializationPolicy = new SerializationPolicy(transcode, selective);
    }

    /** Convenience for tests: production defaults for the serialize path
     *  (transcode ON, the {@code useNbtTranscode} default). */
    public SpongeChunkDiskReader(int threadCount) {
        this(threadCount, true);
    }

    /** Reads go through vanilla's IOWorker: Sponge has no Moonrise priority pool, so
     *  {@code useBackgroundReadPriority} has nothing to select. */
    public SpongeChunkDiskReader(int threadCount, boolean useNbtTranscode) {
        super(threadCount);
        this.serializationPolicy = new SerializationPolicy(useNbtTranscode, false);
    }

    void setReadOverride(SpongeNbtSectionSerializer.ChunkNbtRead read) {
        this.readOverride = read;
    }

    public void submitReadDirect(UUID playerUuid, RequestRegistration registration, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder,
                                  long clientTimestamp) {
        var readPolicy = this.serializationPolicy;
        var registryAccess = level.registryAccess();
        var override = this.readOverride;
        SpongeNbtSectionSerializer.ChunkNbtRead read;
        if (override != null) {
            read = override;
        } else {
            var chunkMap = level.getChunkSource().chunkMap;
            read = (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        // The mask entry is captured at submit time (the level is in hand here); the read
        // itself runs on the reader pool where only the dimension string survives.
        var maskEntry = SpongeXrayMaskManager.entryForActive(level);
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY();
        submitRead(playerUuid, registration, chunkX, chunkZ, dimension, submissionOrder, clientTimestamp,
                () -> SpongeNbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ,
                        maskEntry, minSectionY, maxSectionY, readPolicy.transcode()));
    }
}
