package dev.vox.lss.common.config;

/** Persisted paths derive from the schema, including file-only and private settings. */
public final class ClientSerializedSettings {
    public static java.util.List<SettingDescriptor> descriptors() {
        return SettingsSchema.client().descriptors().stream().map(d -> new SettingDescriptor(
                d.path(), switch (d.kind()) {
                    case BOOLEAN -> SettingDescriptor.Type.BOOLEAN;
                    case INTEGER -> SettingDescriptor.Type.INTEGER;
                    case NUMBER -> SettingDescriptor.Type.DECIMAL;
                    case STRING -> SettingDescriptor.Type.STRING;
                    case STRING_LIST, STRING_GROUPS -> SettingDescriptor.Type.LIST;
                    case STRING_MAP, INTEGER_MAP -> SettingDescriptor.Type.MAP;
                }, d.units(), d.translationKey(), d.sensitive() ? "<private>" : String.valueOf(d.defaultValue()),
                d.minimum() == null ? d.kind().name() : d.minimum() + ".." + d.maximum(),
                "SettingsSchema.client", java.util.Set.of(SettingDescriptor.Scope.CLIENT),
                "client-global", "all client platforms", d.timing() == SettingsSchema.Timing.S
                        ? "Sodium Apply or client reload accepted; reconnect required" : "Sodium Apply or client reload",
                false, "Sodium Apply saves and reloads; file edits require client reload", SettingDescriptor.Exposure.ADVANCED)).toList();
    }
    private ClientSerializedSettings() {}
}
