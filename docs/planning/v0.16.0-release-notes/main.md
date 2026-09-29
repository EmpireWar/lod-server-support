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

- **SQLite compatibility** — Fixes NeoForge SQLite startup conflicts without requiring an additional library mod.
- **Backfill fixes (Fabric/NeoForge)** — Enabling during startup waits for the store; failed manual starts no longer report “already running”.

### Support and Compatibility

- **Platforms** — Fabric, Paper/Purpur/Folia, and NeoForge for Minecraft 26.2; Folia is experimental, and NeoForge is best-effort without distant-player rendering.
- **Compatibility** — Preserves the network protocol and LSS/VSS configuration adoption; this release publishes LSS only.
- **Known limitations** — Existing Xaero first-spawn gaps, region-boundary shading seams, and shader/LOD-depth limitations remain.
