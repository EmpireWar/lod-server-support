### Configuration

- **Commented YAML settings** — Client and server settings are grouped by feature, with inline explanations of units, defaults, restart requirements, and resource costs such as the LOD store's disk/CPU tradeoff.
- **Reload from disk** — `/lsslod reload` reloads server settings; `/lss reload` reloads client settings. Generation limits, bandwidth, concurrent disk reads, cache/store limits, and supported backfill controls can change while running. Settings that require a restart or a new connection are reported separately.
- **Automatic JSON migration** — On startup, existing JSON settings migrate when neither brand's YAML file exists. Existing values and distance overrides are preserved, and the original JSON plus an exact migration backup are retained. Once YAML exists, edit YAML; an invalid YAML file does not silently fall back to JSON.
- **New-install distances** — Fresh configurations use radii of 512 chunks in the Overworld and End, and 64 chunks in the Nether. Migrated configurations retain their previous effective distances.
- **Command changes** — The old `set` and preset-based settings commands are removed. Edit the YAML and reload, or use the client options in Sodium. Invalid reloads keep the current settings active.

### Client Settings

- **Chinese translations** — Provides Simplified and Traditional Chinese labels, tooltips, and settings feedback for the supported Sodium menus.

### Bug Fixes

- **SQLite mod conflicts** — Loads the bundled SQLite driver privately, fixing LSS's NeoForge startup collisions with other SQLite providers without requiring an additional library mod. Verified separately with GriefLogger 1.2.10 and Minecraft SQLite JDBC on Minecraft 1.21.1, including store writes and reads after a server restart.

### Support and Compatibility

- **Platforms** — Fabric, Paper/Purpur, and NeoForge on Minecraft 26.2. NeoForge remains best-effort, and its distant-player renderer is unavailable. Folia support remains experimental.
- **Compatibility** — Preserves the existing network protocol and LSS/VSS adoption behavior. This release publishes LSS only. SQLite isolation does not fix conflicts between two unrelated third-party SQLite providers.
- **Known limitations** — Existing Xaero first-spawn gaps, region-boundary shading seams, and shader/LOD-depth limitations remain.
