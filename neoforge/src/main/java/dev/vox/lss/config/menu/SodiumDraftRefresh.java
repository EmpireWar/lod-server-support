package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Screen-open boundary for registration-time modern bindings and legacy reopen. */
public final class SodiumDraftRefresh {
    private SodiumDraftRefresh() {}

    public static void open(Object screen) {
        var draft = LSSClientConfig.CONFIG.edits();
        draft.open(screen);
        visit(screen, (spec, option) -> {
            try {
                if (!Boolean.TRUE.equals(method(option.getClass(), "hasChanged").invoke(option)))
                    method(option.getClass(), modern(screen) ? "resetFromBinding" : "reset").invoke(option);
            } catch (ReflectiveOperationException failure) { noteFailure(failure); }
        });
    }

    /** Explicit save/discard resolves all retained edits, including Sodium's staged flags. */
    public static void resetOwnBindings(Object screen) {
        visit(screen, (spec, option) -> {
            try { method(option.getClass(), modern(screen) ? "resetFromBinding" : "reset").invoke(option); }
            catch (ReflectiveOperationException failure) { noteFailure(failure); }
        });
    }

    /** Reload must preserve edits that Sodium has staged but has not sent to our setters yet. */
    public static void retainDirtyBeforeReloadRefresh(Object screen) {
        var draft = LSSClientConfig.CONFIG.edits();
        visit(screen, (spec, option) -> {
            try {
                if (!Boolean.TRUE.equals(method(option.getClass(), "hasChanged").invoke(option))) return;
                Object value = method(option.getClass(), modern(screen) ? "getValidatedValue" : "getValue").invoke(option);
                switch (spec) {
                    case OptionSpec.BoolSpec bool -> bool.setter().accept(draft, (Boolean) value);
                    case OptionSpec.IntSpec integer -> integer.setter().accept(draft, (Integer) value);
                }
            } catch (ReflectiveOperationException failure) { noteFailure(failure); }
        });
    }

    private static boolean modern(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().endsWith(".VideoSettingsScreen")) return true;
        return false;
    }
    private static void visit(Object screen, BiConsumer<OptionSpec, Object> visitor) {
        try {
            if (modern(screen)) {
                Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager", false,
                        screen.getClass().getClassLoader());
                Object config = manager.getField("CONFIG").get(null);
                var field = config.getClass().getDeclaredField("options");
                if (!field.trySetAccessible()) return;
                for (var entry : ((Map<?, ?>) field.get(config)).entrySet())
                    ClientOptionCatalog.find(entry.getKey().toString()).ifPresent(spec -> visitor.accept(spec, entry.getValue()));
            } else {
                try (Stream<?> options = (Stream<?>) method(screen.getClass(), "getAllOptions").invoke(screen)) {
                    options.forEach(option -> {
                        try {
                            Object storage = method(option.getClass(), "getStorage").invoke(option);
                            if (method(storage.getClass(), "getData").invoke(storage) != LSSClientConfig.CONFIG.edits()) return;
                            Object name = method(option.getClass(), "getName").invoke(option);
                            if (name instanceof Component component && component.getContents() instanceof TranslatableContents text)
                                ClientOptionCatalog.pages().stream().flatMap(page -> page.options().stream())
                                        .filter(spec -> spec.nameKey().equals(text.getKey())).findFirst()
                                        .ifPresent(spec -> visitor.accept(spec, option));
                        } catch (ReflectiveOperationException failure) { noteFailure(failure); }
                    });
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { noteFailure(failure); }
    }
    private static void noteFailure(Exception failure) {
        dev.vox.lss.common.LSSLogger.debug("Sodium draft refresh unavailable: " + failure.getClass().getSimpleName());
    }
    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method result = current.getDeclaredMethod(name);
                result.trySetAccessible();
                return result;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
}
