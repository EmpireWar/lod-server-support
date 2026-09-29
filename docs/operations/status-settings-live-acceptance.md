# YAML settings and status live acceptance

This is a run checklist, not completed evidence. Use final candidate jars and the [owned disposable rig](disposable-rigs.md). Preserve the run/profile/scenario hashes, actual loaded mod versions, candidate and fixture hashes, action timeline, screenshots, command receipts, config before/after copies, and resulting exports. Keep private paths and raw logs out of public exports. A successful click or clean process exit does not prove the resulting state.

## Applicable UI matrix

Use the profile in the named line's worktree. Resolve blocked dependencies explicitly before launch; do not replace a jar or remove Sodium inside a locked profile without producing a new profile/run identity.

| Line / loader / UI | Profile ID | Required observation |
| --- | --- | --- |
| 1.21.1 Fabric modern | `mc1211-fabric-modern` | Modern Sodium LSS page controls |
| 1.21.1 Fabric legacy | `mc1211-fabric-legacy` | Legacy Sodium LSS page controls |
| 1.21.1 NeoForge modern | `mc1211-neo-modern` | Native modern page controls and effective settings |
| 1.21.1 NeoForge legacy | `mc1211-neo-legacy-xaero` | Legacy page controls and explicit Xaero preference |
| 1.21.10 Fabric legacy | `mc12110-fabric-legacy` | Legacy UI with this line's actual dependencies |
| 1.21.11 Fabric modern | `mc12111-fabric-modern` | Modern UI and current line's actual dependencies |
| 26.1 Fabric modern | `mc261-fabric-modern` | MC 26.1.2 render API and status screen |
| 26.2 Fabric modern | `mc262-fabric-modern` | Current render, screen replacement and chat APIs |
| 1.21.10 NeoForge legacy | `mc12110-neoforge-legacy` | Legacy UI; renderer capability remains unavailable |
| 1.21.11 NeoForge modern | `mc12111-neoforge-modern` | Modern UI; renderer capability remains unavailable |
| 26.1 NeoForge modern | `mc261-neoforge-modern` | MC 26.1.2 UI; renderer capability remains unavailable |
| 26.2 NeoForge modern | `mc262-neoforge-modern` | Current UI; renderer capability remains unavailable |

Confirm Sodium retains its normal layout with no LSS banner, status button or reserved footer space. Open the standalone status screen through `/lss status`.

Also create an explicitly reviewed no-Sodium variant to exercise the standalone command. Check maintained NeoForge renderer-stub lines against their actual applicable profiles: status must say renderer unavailable, not failed. Dependency/shipping/renderer applicability comes from the [catalog](../compatibility.md), independently of the table's UI requirement. A resolved profile remains unverified for a feature until the exact candidate run proves it.

## Prepare and drive the owned client

Run from the selected worktree. Fill `LSS_PROFILE`, `LSS_RUNTIME`, `LSS_RUN_DIR`, `LSS_WINDOW` and `LSS_WINDOW_IDENTITY` from the reviewed runtime and owned run records, not a personal launcher instance. `LSS_WINDOW_IDENTITY` names the recorded process-creation identity JSON for the client window.

```sh
tools/rig/rig doctor --runtime "$LSS_RUNTIME"
tools/rig/rig plan "$LSS_PROFILE" tools/rig/scenarios/ui-apply.json --runtime "$LSS_RUNTIME"
tools/rig/rig create "$LSS_PROFILE" tools/rig/scenarios/ui-apply.json --runtime "$LSS_RUNTIME"
tools/rig/rig run "$LSS_RUN_DIR"
```

While the owning runner remains active, use a second terminal for identity-checked private input. The following opens chat, enters the real command and presses Return; it does not use the host clipboard or desktop:

```sh
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" key t
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" text '/lss status'
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" key Return
python3 tools/rig/private_input.py "$LSS_RUN_DIR" --window "$LSS_WINDOW" --identity "$LSS_WINDOW_IDENTITY" capture status-open.png
```

Use observed widget coordinates for `click X Y`; do not reuse coordinates across GUI scales or generations. For VSS repeat the relevant branded surfaces with `/vss` and `/vsslod`. Server console command text omits the leading slash. The rig's `commands/<unique-id>.json` accepts a recorded `launch_id`, one-line `command` and optional `response_contains`; its receipt must report the semantic response, not merely submission.

## Client drafts, reload and privacy

1. Open Sodium through each applicable modern/legacy route and the standalone
   status screen. Check page order, reachable controls, resize/GUI scale, Done and
   Escape. Status is read-only; it cannot toggle reception.
2. Stage reception, a rate and a sharing change. Before Apply neither YAML nor the
   effective values may change. Apply saves only edited paths, preserves comments
   and unrelated high hand-authored rates, then requests one automatic reload.
   Verify automatic activation in English, Simplified Chinese and Traditional
   Chinese, including sharing changes. Rapid Apply clicks during reload must
   coalesce without losing the latest saved values.
3. Wait for Apply's reload completion; repeat with a direct YAML edit followed by
   `/lss reload`. Reception off retires acceptance; reception on resumes fresh
   work. Combined reception-off/sharing-off must still send the privacy preference
   when connected. Renderer-only changes must not rebuild the terrain session.
4. Retain committed Xaero map debt while reception is off. Reject late callbacks
   from a replaced session. Cache identity, protocol and Xaero enabling changes
   remain pending across dimension switches, server re-pushes and reset; only a new
   physical connection adopts them. Reconnect and verify the actual consumer path.
5. Open a menu, edit a value, then change YAML externally. Save must report a
   conflict and retain the draft. Rebase or discard explicitly. Save errors also
   retain edits; after correcting the failure, Retry must persist the intended
   patch and automatically reload without silently overwriting unrelated values.
6. Check malformed YAML, missing YAML, future config versions and repeated reloads.
   Rejected candidates preserve active settings. A no-op must not rewrite the file,
   rebuild sessions or send extra preference changes. Capture terminal receipts,
   not only the initial “Reading” message.

## Real save and export failures

Use only the run-owned adopted config. After opening the draft, move that file to
an owned backup in the same directory and create an empty directory at its original
filename. Apply must fail visibly, retain the draft and leave effective settings
unchanged. Remove only that empty directory, restore the exact saved file, then
retry. Capture hashes before and after. Never create a blocker over an existing
file, and restore it in failure cleanup. The settings writer uses unique temporary
names, so creating an arbitrary `.tmp` directory is not a valid save-failure test.

For export failure, use a fresh disposable run whose diagnostics destination does
not exist and put an owned regular file at that destination before requesting an
export. Expect failure feedback without raw exception details in the report.
Remove only that blocker, retry, and inspect the JSON/text output and bounded
retention. Reports must exclude fixture identity/address/seed/path markers.

## Server reload and owner adoption

Run on disposable Fabric, Paper and applicable NeoForge servers. Include a Folia
attempt with clients in separate regions. Use the shared settings-file helper for
edits, then the real server command; wait for the terminal response and verify the
active behavior independently.

- Apply generation off/on while work is admitted. New admission follows the new
  revision, admitted jobs drain, queued requests do not retain slots, and current
  clients receive the new policy after worker adoption. Legacy reconnect counts
  and draining messages must describe the actual result.
- Change global/per-player generation concurrency together, timeout, bandwidth,
  disk-read gate, queue limits, timestamp cache/miss TTL, region summaries and
  serialization policy. In-flight jobs retain their captured policy; subsequent
  work adopts the new one. No owner waits for a parser or disk flush.
- On mod servers, lower/raise backfill rate and stop/resume while normal serving
  continues. Test store cap/resweep adoption without opening a second worker or
  promising immediate file shrink. Paper must report inactive backfill fields.
- Change dimension distance, clear it, and verify handshake, range filtering and
  session re-push. On Paper, verify exact world-name override, dimension fallback
  and global fallback. Permission and far-player privacy tightening must shed
  stale subscriptions/rosters.
- Change restart-only reader-pool size, store enabling and masking, then perform
  another unrelated hot reload. Pending values must remain inactive. Revert their
  saved values and verify pending clears; in a separate run restart and prove boot
  adoption. A disabled server must still answer the reload command and report a
  pending service-enable change.
- Reject malformed/unknown/future-version files without publication. Check busy
  reloads, shutdown during preparation/adoption, and no-op behavior. An adoption
  timeout/failure must not be called a successful rollback.

The [owned server reload driver](server-console-preset-smoke.md) automates the
saved-only, hot generation, no-op, invalid/retry and restoration subset. It does
not replace the other transition, restart, multi-region or client UI evidence.

## Close the attempt

```sh
tools/rig/rig stop "$LSS_RUN_DIR"
tools/rig/rig status "$LSS_RUN_DIR"
tools/rig/rig collect "$LSS_RUN_DIR"
```

Wait for completed owned-process cleanup before collection. Tie each assertion to
its exact artifact, run identity, console receipt and observed behavior. Preserve
failed attempts. Keep this validation in private WSL displays; desktop coexistence
does not require Windows foreground input. Do not claim an unavailable renderer,
unsupported platform or unexercised state passed. Record results in the YAML
implementation ledger; historic performance evidence remains unchanged.
