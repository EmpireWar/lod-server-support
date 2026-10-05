package dev.vox.lss.mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sponge-only stand-in for Fabric's {@code ServerChunkEvents.CHUNK_LOAD} / NeoForge's
 * {@code ChunkEvent.Load}: the FULL-status task body, the same spot Fabric API hooks.
 * A chunk read from disk arrives already wrapped ({@link ImposterProtoChunk}); anything
 * else was just generated. {@code require = 0}: a moved lambda degrades the dirty
 * filter's load baseline, never the server.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkLoadHook {

    @Inject(method = "lambda$full$2", at = @At("RETURN"), require = 0)
    private static void lss$onChunkLoad(ChunkAccess protoChunk, WorldGenContext context,
                                        GenerationChunkHolder holder,
                                        CallbackInfoReturnable<ChunkAccess> cir) {
        dev.vox.lss.networking.server.LSSServerNetworking.onChunkLoad(context.level(),
                cir.getReturnValue(), !(protoChunk instanceof ImposterProtoChunk));
    }
}
