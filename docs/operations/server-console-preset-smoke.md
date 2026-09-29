# Owned server console reload smoke

Use `tools/rig/server_control_smoke.py` in a disposable settings attempt while its
supervisor polls `commands/`. The driver writes only into the run-owned settings
file and submits single-line commands through the existing queue. It records
terminal responses, active exports and exact YAML bytes.

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --config "$LSS_CONFIG_FILE" --mode reload-restore
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --config "$LSS_CONFIG_FILE" --mode invalid-retry
```

`reload-restore` saves the opposite `generation.enabled` value, proves the running
state stays unchanged until `lsslod reload`, verifies hot adoption, checks a no-op
reload leaves bytes unchanged, and restores the original file and active state.
`invalid-retry` also introduces a deliberately malformed run-owned YAML document,
verifies rejection preserves effective settings, restores valid bytes and retries.
VSS selects its actual command root with `--brand vss`; always provide the adopted
config path rather than inventing a second branded file.

After the supervisor has stopped and completed owned-process cleanup, verify the
receipt without sending new commands:

```bash
python3 tools/rig/server_control_smoke.py "$LSS_RUN_DIR" \
  --verify-receipt "$LSS_RECEIPT"
```

Receipts bind the run/profile/scenario hashes, console responses and log offsets,
export hashes, complete phase sequence and restored final YAML. Failed attempts
stay failed. This driver covers a bounded reload subset, not the complete
[settings live checklist](status-settings-live-acceptance.md), restart adoption,
client UI, Folia multi-region correctness or any performance claim.
