package dev.vox.lss.config.menu;

/** Both Sodium generations save once per Apply, then request the normal client reload. */
public enum SaveHook {
    SAVE;

    public void run(ClientSettingsEditSession draft) {
        if (!draft.apply()) ClientSettingsSaveScreen.show(draft);
    }
}
