package dev.vox.lss.platform;

import dev.vox.lss.common.diagnostics.DiagnosticVersions;
import dev.vox.lss.common.diagnostics.DiagnosticVersions.Component;
import dev.vox.lss.sponge.SpongeChannels;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.util.Tristate;

import java.nio.file.Path;
import java.util.EnumMap;

/**
 * The Sponge impl of {@link LoaderServices}, installed by {@code LSSSpongePlugin}. Sends
 * go through Sponge's raw data channels ({@link SpongeChannels}); permissions read the
 * Sponge subject with the caller's default for an unset node.
 */
public class SpongeLoaderServices implements LoaderServices {

    private final Path configDir;
    private final DiagnosticVersions diagnosticVersions = captureDiagnosticVersions();

    public SpongeLoaderServices(Path configDir) {
        this.configDir = configDir;
    }

    @Override public DiagnosticVersions diagnosticVersions() { return diagnosticVersions; }

    private static DiagnosticVersions captureDiagnosticVersions() {
        var values = new EnumMap<Component, String>(Component.class);
        values.put(Component.LSS, pluginVersion("lodserversupport", "voxyserverside"));
        values.put(Component.MINECRAFT, Sponge.platform().minecraftVersion().name());
        values.put(Component.LOADER, Sponge.platform().container(Platform.Component.IMPLEMENTATION)
                .metadata().version().toString());
        return new DiagnosticVersions(values);
    }

    private static String pluginVersion(String... ids) {
        for (String id : ids) {
            var plugin = Sponge.pluginManager().plugin(id);
            if (plugin.isPresent()) return plugin.get().metadata().version().toString();
        }
        return "absent";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Sponge.pluginManager().plugin(modId).isPresent();
    }

    @Override
    public Path configDir() {
        return configDir;
    }

    @Override
    public Path gameDir() {
        return Sponge.game().gameDirectory();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        SpongeChannels.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        throw new IllegalStateException("sendToServer on the Sponge (dedicated-server) LoaderServices impl");
    }

    @Override
    public boolean checkPermission(ServerPlayer player, String node, boolean defaultValue) {
        var value = ((org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player)
                .permissionValue(node);
        return value == Tristate.UNDEFINED ? defaultValue : value.asBoolean();
    }

    @Override
    public String permissionProviderToken() {
        return "sponge";
    }
}
