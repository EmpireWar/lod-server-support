package dev.vox.lss.common.diagnostics;

import com.google.gson.JsonParser;
import dev.vox.lss.common.config.SettingsSchema;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStatusTextTest {
    @Test void everyRenderedStateAndArgumentHasAllThreeTranslations() throws Exception {
        var languages = new ArrayList<com.google.gson.JsonObject>();
        Path root=Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root=root.getParent();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw"))
            languages.add(JsonParser.parseString(Files.readString(root.resolve("fabric/src/main/resources/assets/lss/lang/"+locale+".json"))).getAsJsonObject());
        check(ClientStatusText.lines(null,0), languages);
        var defaults=SettingsSchema.client().defaults();
        var settings=ClientSettingsStatus.capture(true,defaults,defaults,defaults,Set.of("integrations.xaero_map.enabled"));
        for (var discovery : ClientStatusSnapshot.Discovery.values())
            for (var integration : ClientStatusSnapshot.Availability.values())
                for (boolean active : new boolean[]{false,true}) {
                    var snapshot=new ClientStatusSnapshot(2,1,100,active,active,active,active,active,active,
                            20,512,128,123,456,2,3,0,20,1,4,discovery,integration,null,DiagnosticVersions.unknown(),settings);
                    check(ClientStatusText.lines(snapshot,90),languages);
                }
        for (var reason : ClientStatusSnapshot.Reason.values()) check(List.of(new ClientStatusText.Message(
                "lss.status.reason."+reason.name().toLowerCase(Locale.ROOT),List.of())),languages);
    }
    private void check(List<?> values,List<com.google.gson.JsonObject> languages) {
        for(Object value:values) {
            if(value instanceof ClientStatusText.Message message) {
                for(var language:languages) {
                    assertTrue(language.has(message.key()), message.key());
                    String text=language.get(message.key()).getAsString();
                    assertEquals(message.arguments().size(), text.split("%s",-1).length-1, message.key()+": argument count");
                }
                check(message.arguments(),languages);
            } else if (value instanceof List<?> nested) check(nested,languages);
        }
    }
}
