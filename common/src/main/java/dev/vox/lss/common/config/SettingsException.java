package dev.vox.lss.common.config;

/** An actionable settings failure. Messages never include private collection values. */
public final class SettingsException extends RuntimeException {
    public SettingsException(String message) { super(message); }
    public SettingsException(String message, Throwable cause) { super(message, cause); }
}
