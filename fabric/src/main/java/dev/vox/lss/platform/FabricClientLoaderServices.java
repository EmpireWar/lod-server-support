package dev.vox.lss.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The physical-client Fabric impl: the common impl plus the client send.
 * Installed by {@code LSSClient}, overwriting {@code LSSMod}'s common install
 * (entrypoint order guarantees main-then-client). Lives in its own class so
 * the common impl never links a client-only Fabric API class on a dedicated
 * server.
 */
public final class FabricClientLoaderServices extends FabricLoaderServices {

    public static void installProductionClient() {
        LoaderServices.install(new FabricClientLoaderServices());
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
    @Override
    public EnqueueOutcome enqueueToServer(CustomPacketPayload payload) {
        try {
            if (!ClientPlayNetworking.canSend(payload.type())) return EnqueueOutcome.NO_CHANNEL;
            ClientPlayNetworking.send(payload);
            return EnqueueOutcome.SENT;
        } catch (RuntimeException failure) {
            return EnqueueOutcome.FAILED;
        }
    }
}
