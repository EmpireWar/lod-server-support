# Owned server settings acceptance tooling

This historical fixture directory now drives explicit YAML reloads. The product has
no preset commands. It uses the existing owned rig console queue and native observer;
it does not launch an independent supervisor or select performance values.

Stage these exact sources into each new private runtime under `settings-tools/`:
`drive.py`, `verify.py`, and the maintained `tools/rig/server_control_smoke.py`,
`conservative_native.py`, `check_conservative_native.py`, and `rig_settings.py`. Record their hashes in
`stage_files` and `settings_contract.fixture_artifacts`. Freeze the shared settings
helper and its Java codec/schema closure with the runtime tool dependencies too.

```sh
python3 "$RUN/settings-tools/drive.py" "$RUN" "$REPO/tools/rig"
python3 "$RUN/settings-tools/verify.py" "$RUN" "$REPO/tools/rig" --cleanup
```

`runtime.settings_contract` specifies `config` (the owned authoritative YAML path),
`platform`, `server_profile_hash`, exact `production_artifacts` and
`fixture_artifacts` maps, `modes`, and `numeric_target`. Modes are
`reload-restore`, `invalid-retry`, and `numeric-reload`. Numeric target keys are
`lod.distance.default_chunks`, `generation.concurrency.global`, and
`generation.concurrency.per_player`; values must be accepted measured integers.

The qualitative modes prove that saving does not publish, reload publishes generation
changes without reconnect, a no-op does not rewrite disk, invalid YAML leaves active
settings intact, and repair/reload restores the original bytes and active behavior.
Numeric phases additionally require actual client SessionConfig receipts and bounded
observation windows: saved-only, applied, unrelated, scope-restored, unchanged,
rejected, restored. The fixture uses `settings-client.private.log` and writes
`evidence/settings-report.json`. It restores all original settings on completion.

Use the native smoke observer with its ordinary client distance preference of one.
Require one actual v20 handshake, typed export and YAML capture hashes, exact console
receipts, wire receipt counts, observer closure, and owned process cleanup. Unit
controls use synthetic inputs; they do not prove native acceptance. Do not rewrite
historical evidence or automatically retry a failed native attempt.
