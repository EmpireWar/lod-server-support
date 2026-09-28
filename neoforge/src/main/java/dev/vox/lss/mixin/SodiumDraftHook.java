package dev.vox.lss.mixin;

import dev.vox.lss.config.menu.SodiumDraftRefresh;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refresh draft bindings when either Sodium generation rebuilds its screen.
 * This lifecycle hook adds no widgets and leaves Sodium's layout unchanged. */
@Mixin(Screen.class)
public abstract class SodiumDraftHook {
    @Inject(method = "clearWidgets", at = @At("RETURN"))
    private void lss$refreshDraft(CallbackInfo callback) {
        Class<?> type = getClass();
        for (int depth = 0; type != null && depth < 8; depth++, type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")) {
                SodiumDraftRefresh.open((Screen) (Object) this);
                return;
            }
        }
    }
}
