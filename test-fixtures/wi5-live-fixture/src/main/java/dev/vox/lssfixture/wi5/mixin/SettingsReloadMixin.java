package dev.vox.lssfixture.wi5.mixin;

import dev.vox.lssfixture.wi5.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe reload adoption only; ordinary tick reconciliation can precede async publication. */
@Mixin(targets = "dev.vox.lss.config.LSSClientConfig", remap = false)
public abstract class SettingsReloadMixin {
    @Inject(method = "reconcile()V", at = @At("HEAD"), require = 1, remap = false)
    private void before(CallbackInfo ci) { Probe.beforeSettingsReconcile(); }
    @Inject(method = "reconcile()V", at = @At("RETURN"), require = 1, remap = false)
    private void after(CallbackInfo ci) { Probe.afterSettingsReconcile(); }
}
