package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.config.LSSClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SettingInventoryTest {
    @Test void everyStoredClientPathHasMetadataAndTypedBinding(@TempDir Path directory) {
        var paths = SettingsSchema.client().defaultValues().keySet();
        var bindings = ClientOptionCatalog.serializedBindings();
        assertEquals(paths, bindings.stream().map(b -> b.descriptor().key()).collect(Collectors.toSet()));
        assertEquals(paths.size(), bindings.size());
        var config = new LSSClientConfig(directory);
        for (var binding : bindings) assertEquals(config.snapshot().values().get(binding.descriptor().key()),binding.storedValue().apply(config));
        assertEquals(10, bindings.stream().filter(b -> b.descriptor().exposure()
                == dev.vox.lss.common.config.SettingDescriptor.Exposure.UI).count());
    }
}
