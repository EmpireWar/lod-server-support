package dev.vox.lss.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A status entry in both Sodium generations, using only vanilla widgets/mappings.
 * Install after every widget clear so page switches retain it. No optional classes
 * are loaded, and status collection never runs from this screen hook. */
@Mixin(Screen.class)
public abstract class SodiumStatusEntryHook {
    @Shadow public int width;
    @Shadow public int height;
    @Shadow public abstract java.util.List<? extends GuiEventListener> children();
    @org.spongepowered.asm.mixin.Unique private boolean lss$statusLayoutPending;
    @org.spongepowered.asm.mixin.Unique private Button lss$statusButton;
    @org.spongepowered.asm.mixin.Unique private dev.vox.lss.common.diagnostics.SettingsNoticeLayout.Bounds lss$noticeBounds;
    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Inject(method = "clearWidgets", at = @At("RETURN"))
    private void lss$statusEntry(CallbackInfo callback) {
        Class<?> type = getClass();
        boolean sodium = false;
        lss$statusButton = null;
        for (int depth = 0; type != null && depth < 8; depth++, type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")) {
                sodium = true;
                break;
            }
        }
        if (!sodium) return;
        Screen parent = (Screen) (Object) this;
        // Modern options scroll. Legacy rows do not: only our own pages reserve
        // a footer, leaving unrelated Sodium/mod tabs at their original full size.
        height = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
        lss$noticeBounds = null;
        if (dev.vox.lss.config.menu.SodiumDraftRefresh.usesNoticeFooter(parent)) {
            lss$noticeBounds = dev.vox.lss.common.diagnostics.SettingsNoticeLayout.fit(height,
                    dev.vox.lss.config.menu.SodiumSettingsNotice.reservedHeight(
                            net.minecraft.client.Minecraft.getInstance().font, width));
            height = lss$noticeBounds.usableHeight();
        }
        dev.vox.lss.config.menu.SodiumDraftRefresh.open(parent);

        lss$statusButton = addRenderableWidget(Button.builder(Component.translatable("lss.status.open"), button ->
                {
                    var draft = dev.vox.lss.config.LSSClientConfig.CONFIG.edits();
                    if (draft.hasRetainedEdits()) dev.vox.lss.config.menu.ClientSettingsSaveScreen.show(draft);
                    else net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                            new dev.vox.lss.networking.client.ClientStatusScreen(parent));
                })
                .bounds(Math.max(0, (width - 105) / 2), lss$noticeBounds == null ? 0 : lss$noticeBounds.buttonY(),
                        Math.min(105, width), lss$noticeBounds == null ? 20 : lss$noticeBounds.buttonHeight()).build());
        lss$statusButton.visible = lss$noticeBounds != null;
        lss$statusLayoutPending = lss$noticeBounds == null;
    }
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void lss$placeStatusEntry(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if (lss$statusButton == null) return;
        var draft = dev.vox.lss.config.LSSClientConfig.CONFIG.edits();
        if (draft.hasRetainedEdits()) lss$statusButton.setMessage(Component.translatable("lss.settings.recovery"));
        else lss$statusButton.setMessage(Component.translatable("lss.status.open"));
        if (lss$noticeBounds != null) {
            // Paint before Screen renders its widgets, including our footer button.
            graphics.fill(0, height, width, lss$noticeBounds.physicalHeight(), 0xf0101010);
        } else if (lss$statusLayoutPending) {
            lss$statusLayoutPending = false;
            var bounds = dev.vox.lss.common.diagnostics.StatusEntryLayout.find(width, height, false,
                    (x, y) -> {
                        for (var child : children())
                            if (child != lss$statusButton && child.isMouseOver(x, y)) return true;
                        return false;
                    });
            if (bounds != null) {
                lss$statusButton.setX(bounds.x());
                lss$statusButton.setY(bounds.y());
                lss$statusButton.setWidth(bounds.width());
                lss$statusButton.visible = true;
            }
        }
    }


    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void lss$settingsNotice(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if (lss$statusButton == null || lss$noticeBounds == null) return;
        var font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.enableScissor(0, height, width, lss$noticeBounds.noticeBottom());
        int y = height + 4;
        for (var component : dev.vox.lss.config.menu.SodiumSettingsNotice.lines(this)) {
            for (var line : font.split(component, dev.vox.lss.config.menu.SodiumSettingsNotice.textWidth(width))) {
                graphics.text(font, line, 8, y, 0xffffff88);
                y += 11;
            }
        }
        graphics.disableScissor();
    }
}
