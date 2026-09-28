package dev.vox.lss.config;

import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientAliasConfigValidationTest {
    @Test void yamlNullIsRejectedAndDefaultsAreEmptyWithWorldSplitOn() {
        var values=new HashMap<String,Object>();values.put("cache.address_aliases",null);
        assertThrows(SettingsException.class,()->SettingsSchema.client().fromValues(values));
        var defaults=SettingsSchema.client().defaults();
        assertEquals(List.of(),defaults.cache().addressAliases());
        assertTrue(defaults.cache().splitByWorld());
    }
    @Test void malformedGroupsStayConfiguredButAreExcludedFromTheWorkingSet() {
        var original=List.of(List.of("good.example.com","alt.good.example.com"),
                List.of("bad.example.com:25565","alt.bad.example.com"));
        var decoded=SettingsSchema.client().fromValues(Map.of("cache.address_aliases",original));
        assertEquals(original,decoded.configured().cache().addressAliases());
        var warnings=new ArrayList<String>();
        var working=dev.vox.lss.networking.client.CacheKeyAliases.validated(decoded.normalized().cache().addressAliases(),warnings::add);
        assertEquals(1,working.size());
        assertFalse(warnings.isEmpty());
        assertEquals(original,decoded.configured().cache().addressAliases());
        assertThrows(UnsupportedOperationException.class,()->decoded.configured().cache().addressAliases().getFirst().add("evil.example.com"));
    }
}
