# YAML settings cross-line source review — 2026-09-28

Independent Astra review of the YAML implementation and all four support ports. The review covers source, Minecraft API adaptations and exact classification bindings. Build and live acceptance evidence belongs to the [implementation progress ledger](2026-09-28-yaml-settings-progress.md); source review does not establish live menu or multiplayer acceptance.

## Exact reviewed refs

- MC 26.2: `88028b3aceedf2d9aaca128a8628fddda89ee3f3`
- MC 26.1: `965cbffe090f5939e4313b351bf70aabd88feb97`
- MC 1.21.11: `3e87a5f97a4bbf89cd19d108722c8be07a70e9fb`
- MC 1.21.10: `7f2cc016a33559d6b3cdb5146f286b6960086349`
- MC 1.21.1: `4c2c2221b7bd1147ebcae239b4443f6a59633e6d`

These commits contain the runtime adoption fixes, final client feedback/recovery controls, typed CLI numeric transport, legacy page footer scoping, native fixture migration and ordered rig receipts. `classification.basis` retains the previous accepted comparison baseline. `config/compatibility/source-refs.json` names the reviewed candidates so default local compatibility CI compares coherent snapshots.

## Findings and disposition

| Finding | Severity | Resolution |
| --- | --- | --- |
| New generation reload gametests called native ChunkPos record accessors on the field-based 1.21.10/1.21.11 APIs; one 1.21.10 TwoPlayer path had the same error. | P2 | Corrected to fields on those lines. Verified mapped native API and declaration origins. TestPositions.ChunkAt remains a record on 26.2/1.21.1; 26.1 native record access remains intact. |
| LSSClientConfig acquired a line-specific direct Screen getter while classified shared. | P2 cross-line contract | Moved the getter into the existing adapted ClientStatusScreen seam. LSSClientConfig is byte-identical; its classification was not weakened. |
| 1.21.1 NeoForge renderer contract still named a removed enabled helper. | P2 validation | Updated the contract to require one settings snapshot capture and both capability/enabled checks; runtime gating remains unchanged. Renderer comments and historical references were cleaned without code changes. |
| Fixture script printed nonexistent YAML path `compatibility.v16.allow_generation`. | P3 | Corrected to `compatibility.v16_generation` on all five lines. |

Runtime failure/retry findings and their fixes are detailed in the [runtime review](2026-09-28-yaml-settings-astra-runtime-review.md). The subsequent CLI fix preserves schema-typed numeric nodes and requested unclamped values; client feedback distinguishes failed/pending adoption from success and retains one startup-created settings publisher. Legacy footer selection uses registered page identity and restores unrelated tabs to their full viewport. No unresolved source blocker was identified in the final reviewed delta.

## Review scope and preserved boundaries

- Compared immutable schema/specification, annotated defaults, migrations, SettingsHandle/SettingsReload and store/backfill/cache policy sources across all five commits. Shared code remains byte-identical. Session payload, protocol constants and handshake codec sources retain each line's baseline behavior; FarPlayerWire changes are comments only.
- Read residual differences for both runtime service owners, disk readers, generation controllers, radius resolution, receivers, save glue, mask managers, Fabric lifecycle registration and plugin metadata. Policy capture, adoption retries, session ordering, terminal fencing and shutdown receipts remain equivalent.
- Preserved native ChunkPos field/record differences, ResourceKey and registry naming, section bounds, 1.21.1 exclusive upper-bound handling, ticket creation/removal, Paper priority/IO/scheduler APIs, split-world directory roots, and Fabric chunk-load/generation event signatures. Existing per-line Folia metadata and NeoForge renderer availability remain intact.
- Compared adapted files' feature deltas against their previously reviewed blobs, then read their residual variants. Thirty-eight of the initial fifty-one remaining paths had identical edit sequences; thirteen variant changes required explicit annotation/assertion/API/renderer/loader review. That comparison was a review aid only: classification still binds complete exact blobs without normalized exemptions.
- Reviewed modern/legacy Sodium draft save, explicit reload, recovery, translated status, adoption feedback and footer changes. Screen, render/text/chat and 1.21.1 integer-key adaptations remain inside existing seams. Modern Sodium bytecode confirms clearWidgets precedes layout; legacy selected-page bytecode contract confirms selection precedes rebuild. Inherited name-tag-distance locale wording is the only locale residual.
- Reviewed YAML staging, historical JSON-only comparisons, platform soak inputs, retained launch guards, parser relocation/resource/license checks and all six branded loader discovery calls. Rig values cache immutable document content, reread paths and return independent maps. Native fixture observers sample the actual owner adoption boundary; ordered evidence requires Save-inert and successful reload receipts.

The external native YAML observer and recipes were reviewed through `4ee7e545`: exact class hashes/descriptors, actual owner-return callbacks, bounded evidence writer, owned command receipts and Folia overlap inside the reload window. Reproduced checker gaps were closed for platform downgrade, frozen-input drift, reused/out-of-order phases, enabled quiet state, unchanged high-rate pacing and new admissions during disable drain. The checker requires unchanged submitted count and disabled revision across off/drained/quiet. Final pacing uses legal 10→40 columns/second targets, measures actual deposits above the old-rate carry bound and below the new-rate carry bound, and preserves worker identity. Numeric driver edits first pass through the real codec normalization; clamped or group-capped intent fails before file writes or console commands. This is source/checker validation, not a claim that the native scenario has run.

Final localization followup `c91c3a65` translates export feedback on the client owner without changing ticket capture or method signatures; retained recovery failures log technical detail once and display translated guidance. The 1.21.1 parser classpath followup is development-only and leaves packaged relocation unchanged.

Final server diagnostics followup `4b3d62fa` captures the actual generation owner admission gate together with whole-service enablement, retains the documented schema-1 configured field, and removes false restart guidance. The same getter/capture/test deltas are present on every port without changing native API adaptations. Updated troubleshooting and smoke expectations match explicit save/reload behavior.

Final native checker registration `c82ea6ac` declares both the YAML reload checker and driver as scenario identity roots. Its regression changes each root and a transitive evidence helper to prove identity invalidation, and excludes unselected known branches from unrelated routes. Both files remain byte-identical across all five reviewed commits; their existing identical classifications are unchanged. Fourteen focused closure tests passed.

Final observer packaging followup `d96be18d` omits embedded ASM from the Fabric agent because the frozen Knot server startup classpath already supplies ASM 9.10.1. Paper/Folia retain bundled ASM for Paperclip premain. Build-time checks inspect actual ZIP entries for duplicate classes and recorder-only bootstrap contents. The standalone premain probes exercised both layouts, required exactly one ClassReader resource, parsed real bytecode and closed the evidence writer without overflow. This validates fixture packaging; native scenario acceptance remains in the progress ledger. The build script remains identical across all five lines.

Final fixture premise correction `45dc975b` filters inherited game-mode properties and emits `gamemode=creative` and `force-gamemode=true` exactly once. All three regenerated Fabric/Paper/Folia recipes were inspected for those exact values; removing the former source fixture plugin no longer leaves fresh clients in survival during high-altitude teleports. The preparer remains identical across all five lines and product code is unchanged.

Final Fabric observer visibility correction `153daf44` adds the two owned observer jars through the supported `fabric.systemLibraries` property, retaining and deduplicating any inherited entries. Actual generated Fabric properties and the inherited-library preservation case were inspected; Paper/Folia receive no added property. A cached real Knot probe rejects the recorder without the allowlist and resolves the identical bootstrap class and evidence sink with it, including a callback from a child-loaded fixture object. This is fixture classloader evidence, not a native product acceptance claim.

Native driver timing correction `a173d929` stages the inert generation-disable file before fixture movement, then requires a fresh enabled/active sample after both teleports and sends reload without another codec subprocess. The checker still requires active work at the actual disable callback and unchanged drain/conservation checks; a remaining timing miss fails the premise. No runtime behavior or acceptance threshold was weakened. The driver is identical on all five lines.

Paper observer followup `88028b3a` accounts for the actual PluginClassLoader call to `UnsafeValues.processClass` before class definition. The observer first verifies the frozen raw CodeSource class hash and actual plugin/loader/source identity, then reproduces the platform transformation using the real description and requires complete byte equality with incoming class bytes. Exact hashes and method descriptors remain mandatory; unexpected source or transformed bytes fail observation. Build checks parse both actual target classes with the packaged ASM and reject mutated source/incoming bytes. Cached Commodore probe evidence confirms the expected conversion. The fresh-world recipe now uses normal terrain to give the strict in-flight premise a realistic generation interval; checker thresholds remain unchanged.

The companion WI5 correction `585a1d71` observes the instance `LSSClientConfig.reconcile()V` reload-adoption boundary instead of ordinary client tick reconciliation, retaining required HEAD/RETURN hooks. All six changed/new fixture files are identical across the five reviewed snapshots; no product rebuild is implied by these fixture changes.

## Classification and verification

Added 104 explicit identical entries and three precise adapted entries: SettingsReloadGameTests, its modern environment resource (absent on 1.21.1), and ClientSettingsSaveScreen. Updated 77 existing adapted bindings after residual review. No identical production source was reclassified to hide divergence; no wildcard was widened. All reviewed production and test edits are committed in the refs above.

The explicit-ref classification check passed with zero issues at these five commits. Default `python3 tools/compat/ci.py` also passed locally without `--fetch` after updating source refs and regenerating the catalog. Earlier focused checks passed eight rig receive tests and seven native YAML checker tests. The final diagnostic and numeric-preflight unit evidence is recorded in the progress ledger; this refresh performs only classification/catalog and whitespace checks. The final legacy ordering test reader was reviewed to ensure it includes method bytecode and rejects an empty instruction list.

Local machine-readable evidence: `/tmp/lss-yaml-validation/runtime-line-check-final10-reviewed.json` and `/tmp/lss-yaml-validation/runtime-default-compat-ci-final10-26.2.json`. Both record the exact source identities; the former is reproducible with `tools/lines/lines.py check` using the source-ref catalog.

The catalog's generated compatibility table uses the same exact source commits. No fetch, push, publication or heavy build was performed as part of this final source audit.
