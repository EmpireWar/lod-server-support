package dev.vox.lss.paper;

import dev.vox.lss.common.config.ServerSettings;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.YamlServerConfig;
import java.nio.file.Path;

/** Paper defaults and exact world-name overrides are owned by the shared schema. */
public class PaperConfig extends YamlServerConfig {
    public PaperConfig() { super(SettingsSchema.server(true).defaults(), true); }
    public PaperConfig(ServerSettings settings) { super(settings, true); }
    public PaperConfig(Path directory) { super(directory, true); }
    public static PaperConfig load(Path directory) { return new PaperConfig(directory); }
}
