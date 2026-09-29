package dev.vox.lss.common.config;

import java.util.List;

/** Descriptor compatibility surface backed by the authoritative nested YAML schema. */
public final class ServerSerializedSettings {
    public static List<SettingDescriptor> descriptors() {
        return SettingsSchema.server(false).descriptors().stream()
                .filter(d -> !d.path().startsWith("paper."))
                .map(ServerSerializedSettings::descriptor).toList();
    }
    static SettingDescriptor descriptor(SettingsSchema.Descriptor d) {
        var type = switch (d.kind()) {
            case BOOLEAN -> SettingDescriptor.Type.BOOLEAN;
            case INTEGER -> SettingDescriptor.Type.INTEGER;
            case NUMBER -> SettingDescriptor.Type.DECIMAL;
            case STRING -> SettingDescriptor.Type.STRING;
            case STRING_LIST, STRING_GROUPS -> SettingDescriptor.Type.LIST;
            case STRING_MAP, INTEGER_MAP -> SettingDescriptor.Type.MAP;
        };
        var scopes = d.path().startsWith("lod.distance.")
                ? java.util.Set.of(SettingDescriptor.Scope.SERVER, SettingDescriptor.Scope.WORLD_DISTANCE)
                : java.util.Set.of(SettingDescriptor.Scope.SERVER);
        return new SettingDescriptor(d.path(), type, d.units(), d.translationKey(),
                String.valueOf(d.defaultValue()), d.description(), "SettingsSchema", scopes,
                d.path().startsWith("lod.distance.") ? "world name > dimension > default" : "server",
                d.platform().name(), d.timing() == SettingsSchema.Timing.H ? "explicit reload" : "server restart",
                d.timing() == SettingsSchema.Timing.R, "reload", SettingDescriptor.Exposure.ADVANCED);
    }
    public static List<SettingBinding<ServerConfigBase>> bindings() {
        return descriptors().stream().map(d -> new SettingBinding<ServerConfigBase>(d,
                config -> config.configuredSnapshot().values().get(d.key()))).toList();
    }
    private ServerSerializedSettings() {}
}
