package dev.vox.lss.common.config;

/** Paper-only paths from the shared schema. */
public final class PaperSerializedSettings {
    public static java.util.List<SettingDescriptor> descriptors() {
        return SettingsSchema.server(true).descriptors().stream()
                .filter(d -> d.path().startsWith("paper."))
                .map(ServerSerializedSettings::descriptor).toList();
    }
    private PaperSerializedSettings() {}
}
