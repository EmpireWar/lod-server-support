package dev.vox.lss.test;

import dev.vox.lss.common.config.ServerSettings;
import dev.vox.lss.config.LSSServerConfig;

/** Isolated policy-consumer fixture, never a publisher on the global server facade.
 * Actual reload timing is covered by SettingsReloadGameTests. */
final class ServiceSettingsFixture extends LSSServerConfig {
    private volatile ServerSettings value;
    ServiceSettingsFixture() {
        super(LSSServerConfig.CONFIG.snapshot());
        value = super.snapshot();
        // Consumer tests vary the fallback radius explicitly; fresh dimension defaults
        // are covered separately by schema and real reload tests.
        set("lod.distance.by_dimension", java.util.Map.of());
    }
    @Override public ServerSettings snapshot() { return value == null ? super.snapshot() : value; }
    void set(String path, Object next) {
        var values = new java.util.LinkedHashMap<>(snapshot().values());
        values.put(path, next);
        value = ServerSettings.fromValues(values);
    }
}
