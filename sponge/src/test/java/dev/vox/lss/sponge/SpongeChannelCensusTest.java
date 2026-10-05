package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sponge mirror of the Fabric {@code ClientReceiverCensusTest} lesson (final review
 * G1): a C2S channel added to {@code LSSConstants} and {@code dispatchPluginMessage} but
 * forgotten in {@link SpongeChannels#INCOMING} compiles, boots, and then silently never
 * receives a frame. The incoming registrations and the plugin's dispatch cases must be the
 * SAME set.
 */
class SpongeChannelCensusTest {

    private static final Pattern DISPATCH_CASE = Pattern.compile(
            "case\\s+LSSConstants\\.(CHANNEL_\\w+)\\s*->");

    private static Path source(String moduleRelative) {
        var p = Path.of(moduleRelative);
        if (Files.exists(p)) return p;
        return Path.of("sponge").resolve(moduleRelative);
    }

    @Test
    void registeredIncomingChannelsMatchTheDispatchCases() throws Exception {
        String plugin = Files.readString(source(
                "src/main/java/dev/vox/lss/sponge/LSSSpongePlugin.java"));

        Set<String> dispatched = new LinkedHashSet<>();
        for (String constant : collect(DISPATCH_CASE.matcher(plugin))) {
            dispatched.add((String) LSSConstants.class.getField(constant).get(null));
        }

        assertTrue(SpongeChannels.INCOMING.size() >= 5,
                "expected the full C2S surface registered, found only " + SpongeChannels.INCOMING);
        assertEquals(dispatched, new LinkedHashSet<>(SpongeChannels.INCOMING),
                "every dispatchPluginMessage case needs a registered incoming channel (an"
                        + " unregistered channel is never delivered), and every registration"
                        + " needs a dispatch case (an orphan registration is dead wire surface)");
    }

    @Test
    void noChannelIsRegisteredBothWays() {
        var both = new LinkedHashSet<>(SpongeChannels.INCOMING);
        both.retainAll(SpongeChannels.OUTGOING);
        assertTrue(both.isEmpty(), "a channel is either client->server or server->client: " + both);
    }

    private static Set<String> collect(Matcher m) {
        var found = new LinkedHashSet<String>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
