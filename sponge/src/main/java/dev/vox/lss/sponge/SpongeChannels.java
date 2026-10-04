package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.network.ServerConnectionState;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugin-message seam: Sponge's raw data channels in place of Bukkit's messenger.
 * Vanilla's DiscardedPayload drops its bytes (Paper patches it to carry them), so S2C
 * frames cannot go out as raw NMS packets here. Registering every channel also makes
 * Sponge announce them in minecraft:register, which Fabric clients check before sending.
 */
final class SpongeChannels {

    /** Client -> server. */
    static final List<String> INCOMING = List.of(
            LSSConstants.CHANNEL_HANDSHAKE,
            LSSConstants.CHANNEL_CHUNK_REQUEST,
            LSSConstants.CHANNEL_CLIENT_INFO,
            LSSConstants.CHANNEL_FAR_PLAYER_PREFS,
            LSSConstants.CHANNEL_REGION_SUMMARY_REQ);

    /** Server -> client. */
    static final List<String> OUTGOING = List.of(
            LSSConstants.CHANNEL_SESSION_CONFIG,
            LSSConstants.CHANNEL_DIRTY_COLUMNS,
            LSSConstants.CHANNEL_VOXEL_COLUMN,
            LSSConstants.CHANNEL_BATCH_RESPONSE,
            LSSConstants.CHANNEL_FAR_PLAYER_ROSTER,
            LSSConstants.CHANNEL_FAR_PLAYER_UPDATES,
            LSSConstants.CHANNEL_REGION_SUMMARY,
            LSSConstants.CHANNEL_COL_STAMPS);

    @FunctionalInterface
    interface Receiver {
        void receive(String channel, ServerPlayer player, byte[] message);
    }

    private static final Map<String, RawDataChannel> CHANNELS = new ConcurrentHashMap<>();
    private static volatile Receiver receiver;

    private SpongeChannels() {}

    static void register(RegisterChannelEvent event) {
        for (String id : INCOMING) {
            var channel = event.register(ResourceKey.resolve(id), RawDataChannel.class);
            channel.play().addHandler(ServerConnectionState.Game.class,
                    (data, state) -> dispatch(id, state, data));
            CHANNELS.put(id, channel);
        }
        for (String id : OUTGOING) {
            CHANNELS.put(id, event.register(ResourceKey.resolve(id), RawDataChannel.class));
        }
    }

    /** Set on enable, cleared on disable: frames outside that window are dropped. */
    static void setReceiver(Receiver r) {
        receiver = r;
    }

    private static void dispatch(String id, ServerConnectionState.Game state, ChannelBuf data) {
        var r = receiver;
        if (r == null) return;
        r.receive(id, (ServerPlayer) (Object) state.player(), data.readBytes(data.available()));
    }

    /** @return false when the channel is unknown or the player has no connection */
    static boolean send(ServerPlayer player, String channelId, byte[] data) {
        var channel = CHANNELS.get(channelId);
        if (channel == null || player.connection == null) return false;
        var spongePlayer = (org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player;
        channel.play().sendTo(spongePlayer, buf -> buf.writeBytes(data));
        return true;
    }
}
