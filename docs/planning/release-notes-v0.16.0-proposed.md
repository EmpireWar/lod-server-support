# LSS 0.16.0 — proposed release notes

Prepared for review; not published. The shared changes below apply to all five maintained lines. Per-line tag notes are in `v0.16.0-release-notes/`.

### Configuration

- **Commented YAML settings** — Client and server settings are grouped by feature, with comments explaining units and resource costs.
- **Reload settings** — Use `/lsslod reload` on servers or `/lss reload` on clients; settings that need a restart or reconnect are reported.
- **Automatic JSON migration** — Existing settings migrate to YAML on startup, preserving values and accepting the old loader's comments and loose JSON syntax.
- **One-way migration** — Downgrading to v0.15.x reads the retained JSON and ignores YAML edits; copy changes back into JSON before downgrading.
- **New-install distances** — Fresh configurations use 512 chunks in the Overworld and End and 64 in the Nether; migrated distances stay unchanged.
- **Removed commands** — `/lsslod set` and preset commands are removed; edit the YAML, then run `/lsslod reload`.

### Client Settings

- **Updated Chinese translations** — Adds Simplified and Traditional Chinese translations for the new settings labels, tooltips, and feedback.

### Bug Fixes

- **SQLite compatibility** — Isolates the bundled driver, including fixes for NeoForge startup conflicts on lines that ship NeoForge.
- **Backfill fixes (Fabric/NeoForge)** — Enabling during startup waits for the store; failed manual starts no longer report “already running”.

### Supported releases

| Minecraft line | Published builds |
| --- | --- |
| 1.21.1 | Fabric, Paper/Purpur, NeoForge |
| 1.21.10 | Fabric, Paper/Purpur |
| 1.21.11 | Fabric, Paper/Purpur/Folia |
| 26.1 | Fabric 26.1–26.1.2; Paper/Purpur/Folia and NeoForge 26.1.2 |
| 26.2 | Fabric, Paper/Purpur/Folia, NeoForge |

Folia remains experimental. NeoForge remains best-effort; distant-player rendering on NeoForge is available on 1.21.1 and unavailable on the other published lines. The whole 1.21.1 line remains best-effort.

- **Compatibility** — Preserves the network protocol and LSS/VSS configuration adoption.
- **Known limitations** — Existing Xaero first-spawn gaps, region-boundary shading seams, and shader/LOD-depth limitations remain.
