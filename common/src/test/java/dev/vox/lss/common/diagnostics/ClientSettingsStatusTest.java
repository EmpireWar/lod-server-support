package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientSettingsStatusTest {
    @TempDir Path directory;
    @Test void saveReloadAndReconnectHaveDistinctSafeExportStates() throws Exception {
        var store = new SettingsStore<ClientSettings>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        Object connection = new Object(); handle.beginSession(connection);
        var saved = store.saveDraft(store.read().hash(), Map.of("lod.receive", false,
                "integrations.xaero_map.enabled", true,
                "cache.address_aliases", List.of(List.of("private.example", "secret.example")))).configured();
        var before = capture(saved, handle);
        assertFalse(before.saved().receptionEnabled());
        assertTrue(before.configured().receptionEnabled());
        assertTrue(before.effective().receptionEnabled());
        assertEquals(List.of("cache.address_aliases", "integrations.xaero_map.enabled", "lod.receive"), before.pendingReload());
        handle.commit(handle.prepareReload());
        var accepted = capture(saved, handle);
        assertTrue(accepted.pendingReload().isEmpty());
        assertFalse(accepted.effective().receptionEnabled());
        assertTrue(accepted.configured().xaeroMapEnabled());
        assertFalse(accepted.effective().xaeroMapEnabled());
        assertEquals(List.of("cache.address_aliases", "integrations.xaero_map.enabled"), accepted.pendingReconnect());
        var snapshot = new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true,
                false, true, true, false, 20, 512, 128, 0, 0, 0, 0, 0, 0, 0, 0,
                ClientStatusSnapshot.Discovery.NEGOTIATED, ClientStatusSnapshot.Availability.DISABLED,
                null, DiagnosticVersions.unknown(), accepted);
        String json = DiagnosticExport.clientJson(snapshot);
        assertTrue(json.contains("\"schemaVersion\": 2"));
        assertTrue(json.contains("pendingReconnect"));
        assertFalse(json.contains("private.example")); assertFalse(json.contains("secret.example"));
        handle.endSession(connection);
        var reconnected = capture(saved, handle);
        assertTrue(reconnected.pendingReconnect().isEmpty());
        assertTrue(reconnected.effective().xaeroMapEnabled());
    }
    @Test void arbitraryStringsCannotMasqueradeAsPendingSettingsPaths() {
        var defaults = SettingsSchema.client().defaults();
        var status = ClientSettingsStatus.capture(true, defaults, defaults, defaults,
                Set.of("cache.address_aliases", "private.example", "/home/person/config"));
        assertEquals(List.of("cache.address_aliases"), status.pendingReconnect());
    }
    private ClientSettingsStatus capture(ClientSettings saved, SettingsHandle<ClientSettings> handle) {
        var s=handle.state();return ClientSettingsStatus.capture(true,saved,s.configured(),s.effective(),s.pendingReconnect());
    }
}
