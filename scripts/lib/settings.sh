# Shared Java settings codec for task-owned staging. Source after harness-lock.sh.
# The classpath is frozen before a game starts; settings edits never launch builds
# beside a running rig or bypass the harness ownership lock.
HARNESS_SETTINGS_ROOT="$(cd "$HARNESS_LIB_DIR/../.." && pwd)"

harness_settings_prepare() {
    if [[ -z "${LSS_SETTINGS_CLASSPATH:-}" ]]; then
        harness_gradle_at "$HARNESS_SETTINGS_ROOT" :common:settingsCliClasspath --no-parallel --quiet
        LSS_SETTINGS_CLASSPATH="$(cat "$HARNESS_SETTINGS_ROOT/common/build/settings-cli/classpath.txt")"
        export LSS_SETTINGS_CLASSPATH
    fi
}

harness_stage_yaml() { # <path> <server|client> <path=JSON-value>...
    local target="$1" side="$2"
    shift 2
    harness_settings_prepare
    local args=(stage --path "$target" --side "$side") assignment
    for assignment in "$@"; do args+=(--set "$assignment"); done
    python3 "$HARNESS_SETTINGS_ROOT/tools/settings/settings_file.py" "${args[@]}" >/dev/null
}

harness_settings_extension() { # <checkout>; JSON is retained only for historical arms.
    if [[ -f "$1/common/src/main/java/dev/vox/lss/common/config/SettingsSchema.java" ]]; then
        printf 'yaml'
    else
        printf 'json'
    fi
}
