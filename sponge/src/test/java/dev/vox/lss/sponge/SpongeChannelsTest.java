package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpongeChannelsTest {

    /** A channel added to LSSConstants but not to the table compiles, boots, and then
     *  silently never sends or receives a frame. Direction follows the payload name. */
    @Test
    void everyChannelIsRegisteredInItsDirection() {
        var constants = Arrays.stream(LSSConstants.class.getFields())
                .filter(f -> f.getName().startsWith("CHANNEL_") && Modifier.isStatic(f.getModifiers()))
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                })
                .collect(Collectors.toSet());
        var registered = SpongeChannels.ENTRIES.stream()
                .map(e -> e.type().id().toString()).collect(Collectors.toSet());
        assertEquals(constants, registered);
        assertEquals(SpongeChannels.ENTRIES.size(), registered.size(), "duplicate channel");

        var inbound = SpongeChannels.ENTRIES.stream().filter(e -> e.handler() != null)
                .map(e -> e.type().id().toString()).collect(Collectors.toSet());
        assertEquals(java.util.Set.of(LSSConstants.CHANNEL_HANDSHAKE, LSSConstants.CHANNEL_CHUNK_REQUEST,
                LSSConstants.CHANNEL_CLIENT_INFO, LSSConstants.CHANNEL_FAR_PLAYER_PREFS,
                LSSConstants.CHANNEL_REGION_SUMMARY_REQ), inbound);
    }

    @Test
    void framesCarryExactlyTheCodecBytes() {
        var sent = new HandshakeC2SPayload(20, 0b101);
        var received = new AtomicReference<HandshakeC2SPayload>();
        var entry = new SpongeChannels.Entry<>(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC,
                (payload, player) -> received.set(payload));
        entry.receive(null, entry.encode(sent));
        assertEquals(sent, received.get());
    }
}
