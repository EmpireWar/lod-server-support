package dev.vox.lss.sponge;

import dev.vox.lss.common.config.ServerSettings;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.YamlServerConfig;

import java.nio.file.Path;

/**
 * Sponge server settings: the Fabric (vanilla chunk system) template, not Paper's. Sponge
 * has no Bukkit update events or Moonrise read priority, and every Sponge world is its own
 * dimension key, so {@code lod.distance.by_dimension} already gives per-world distances.
 */
public class SpongeConfig extends YamlServerConfig {
    public SpongeConfig() { super(SettingsSchema.server(false).defaults(), false); }
    public SpongeConfig(ServerSettings settings) { super(settings, false); }
    public SpongeConfig(Path directory) { super(directory, false); }
    public static SpongeConfig load(Path directory) { return new SpongeConfig(directory); }
}
