package dev.vox.lss.config.menu;

/** Both generations share one storage transaction per Apply, with no live side effects. */
public enum SaveHook {
    SAVE;

    public void run(ClientSettingsEditSession draft) {
        if (!draft.save()) ClientSettingsSaveScreen.show(draft);
    }
}
