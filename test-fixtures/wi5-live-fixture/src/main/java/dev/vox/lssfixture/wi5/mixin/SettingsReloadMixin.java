package dev.vox.lssfixture.wi5.mixin;

import dev.vox.lssfixture.wi5.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only observation; production reload remains the only lifecycle publisher. */
@Mixin(targets = "dev.vox.lss.networking.client.ClientNetGlue", remap = false)
public abstract class SettingsReloadMixin {
    @Inject(method = "reconcileClientConfig()V", at = @At("HEAD"), require = 1, remap = false)
    private static void before(CallbackInfo ci) { Probe.beforeSettingsReconcile(); }
    @Inject(method = "reconcileClientConfig()V", at = @At("RETURN"), require = 1, remap = false)
    private static void after(CallbackInfo ci) { Probe.afterSettingsReconcile(); }
}
