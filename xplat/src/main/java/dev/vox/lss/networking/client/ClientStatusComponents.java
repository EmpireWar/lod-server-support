package dev.vox.lss.networking.client;

import dev.vox.lss.common.diagnostics.ClientStatusSnapshot;
import dev.vox.lss.common.diagnostics.ClientStatusText;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Minecraft translation boundary for the common status model. */
final class ClientStatusComponents {
    private ClientStatusComponents() {}
    static List<Component> lines(ClientStatusSnapshot snapshot) {
        return ClientStatusText.lines(snapshot, System.currentTimeMillis()).stream()
                .map(ClientStatusComponents::component).toList();
    }
    private static Component component(ClientStatusText.Message message) {
        return Component.translatable(message.key(), message.arguments().stream().map(ClientStatusComponents::argument).toArray());
    }
    private static Object argument(Object value) {
        if (value instanceof ClientStatusText.Message message) return component(message);
        if (value instanceof List<?> list) {
            var joined = Component.empty();
            for (Object entry : list) {
                if (!joined.getSiblings().isEmpty()) joined.append(Component.translatable("lss.status.separator"));
                joined.append((Component) argument(entry));
            }
            return joined;
        }
        return value;
    }
}
