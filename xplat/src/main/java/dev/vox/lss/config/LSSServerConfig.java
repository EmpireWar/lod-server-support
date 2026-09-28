package dev.vox.lss.config;

import dev.vox.lss.common.config.ServerSettings;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.YamlServerConfig;
import java.nio.file.Path;

public class LSSServerConfig extends YamlServerConfig {
    public static final LSSServerConfig CONFIG = new LSSServerConfig(
            dev.vox.lss.platform.LoaderServices.get().configDir());
    public static void beginServerLifecycle() { CONFIG.restartSettingsLifecycle(); }
    public LSSServerConfig() { super(SettingsSchema.server(false).defaults(), false); }
    public LSSServerConfig(ServerSettings settings) { super(settings, false); }
    public LSSServerConfig(Path directory) { super(directory, false); }
}
