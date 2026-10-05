package dev.vox.lss.sponge;

import dev.vox.lss.common.config.ServerSettings;
import dev.vox.lss.common.config.SettingsSchema;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only consumer fixture. Each edit swaps one complete immutable snapshot;
 * production facades expose no equivalent publisher. YAML reload is tested separately. */
final class MutableSpongeSettings extends SpongeConfig {
    private volatile ServerSettings value;
    MutableSpongeSettings() {
        super(initial());
        value = initial();
    }
    private static ServerSettings initial() {
        var values = new LinkedHashMap<>(SettingsSchema.server(false).defaultValues());
        // These consumer fixtures isolate fallback/range behavior, independent of
        // the separately tested fresh-install vanilla dimension overrides.
        values.put("lod.distance.by_dimension", Map.of());
        return ServerSettings.fromValues(values);
    }
    @Override public ServerSettings snapshot() { return value == null ? super.snapshot() : value; }
    static void set(SpongeConfig config, String path, Object next) {
        var fixture = (MutableSpongeSettings) config;
        var values = new LinkedHashMap<>(fixture.snapshot().values());
        values.put(path, next);
        fixture.value = ServerSettings.fromValues(values);
    }
    static void normalize(SpongeConfig config) {
        var fixture = (MutableSpongeSettings) config;
        fixture.value = SettingsSchema.server(false).fromValues(fixture.snapshot().values()).normalized();
    }
    static void dimension(SpongeConfig config, String id, int chunks) {
        var map = new LinkedHashMap<>(config.snapshot().lod().distance().byDimension());
        map.put(id, chunks);
        set(config, "lod.distance.by_dimension", map);
    }
}
