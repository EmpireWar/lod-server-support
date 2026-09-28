# YAML settings cross-line source review — 2026-09-28

Independent Astra review of the YAML implementation and all four support ports. The review covers source, Minecraft API adaptations and exact classification bindings. Build and live acceptance evidence belongs to the [implementation progress ledger](2026-09-28-yaml-settings-progress.md); source review does not establish live menu or multiplayer acceptance.

## Exact reviewed refs

- MC 26.2: `7c161ec0f1a91cb2a175abac7945cfbd5eba694a`
- MC 26.1: `f573d12cba7eacc9d159ff3b3b418ce22a63dccf`
- MC 1.21.11: `a938d75eefec875e07ef3abe78b9374bf69bf5c4`
- MC 1.21.10: `9dc9ee0b96a9f1bd6a118394c41322ff157a9f9e`
- MC 1.21.1: `96c435c3d86cd5ef07546991438e7ac29fef5bb9`

These commits contain the runtime adoption fixes, final client feedback/recovery controls, typed CLI numeric transport, legacy page footer scoping, native fixture migration and ordered rig receipts. `classification.basis` retains the previous accepted comparison baseline. `config/compatibility/source-refs.json` names the reviewed candidates so default local compatibility CI compares coherent snapshots.

## Findings and disposition

| Finding | Severity | Resolution |
| --- | --- | --- |
| New generation reload gametests called native ChunkPos record accessors on the field-based 1.21.10/1.21.11 APIs; one 1.21.10 TwoPlayer path had the same error. | P2 | Corrected to fields on those lines. Verified mapped native API and declaration origins. TestPositions.ChunkAt remains a record on 26.2/1.21.1; 26.1 native record access remains intact. |
| LSSClientConfig acquired a line-specific direct Screen getter while classified shared. | P2 cross-line contract | Moved the getter into the existing adapted ClientStatusScreen seam. LSSClientConfig is byte-identical; its classification was not weakened. |
| Fixture script printed nonexistent YAML path `compatibility.v16.allow_generation`. | P3 | Corrected to `compatibility.v16_generation` on all five lines. |

Runtime failure/retry findings and their fixes are detailed in the [runtime review](2026-09-28-yaml-settings-astra-runtime-review.md). The subsequent CLI fix preserves schema-typed numeric nodes and requested unclamped values; client feedback distinguishes failed/pending adoption from success and retains one startup-created settings publisher. Legacy footer selection uses registered page identity and restores unrelated tabs to their full viewport. No unresolved source blocker was identified in the final reviewed delta.

## Review scope and preserved boundaries

- Compared immutable schema/specification, annotated defaults, migrations, SettingsHandle/SettingsReload and store/backfill/cache policy sources across all five commits. Shared code remains byte-identical. Session payload, protocol constants and handshake codec sources retain each line's baseline behavior; FarPlayerWire changes are comments only.
- Read residual differences for both runtime service owners, disk readers, generation controllers, radius resolution, receivers, save glue, mask managers, Fabric lifecycle registration and plugin metadata. Policy capture, adoption retries, session ordering, terminal fencing and shutdown receipts remain equivalent.
- Preserved native ChunkPos field/record differences, ResourceKey and registry naming, section bounds, 1.21.1 exclusive upper-bound handling, ticket creation/removal, Paper priority/IO/scheduler APIs, split-world directory roots, and Fabric chunk-load/generation event signatures. Existing per-line Folia metadata and NeoForge renderer availability remain intact.
- Compared adapted files' feature deltas against their previously reviewed blobs, then read their residual variants. Thirty-eight of the initial fifty-one remaining paths had identical edit sequences; thirteen variant changes required explicit annotation/assertion/API/renderer/loader review. That comparison was a review aid only: classification still binds complete exact blobs without normalized exemptions.
- Reviewed modern/legacy Sodium draft save, explicit reload, recovery, translated status, adoption feedback and footer changes. Screen, render/text/chat and 1.21.1 integer-key adaptations remain inside existing seams. Modern Sodium bytecode confirms clearWidgets precedes layout; legacy selected-page bytecode contract confirms selection precedes rebuild. Inherited name-tag-distance locale wording is the only locale residual.
- Reviewed YAML staging, historical JSON-only comparisons, platform soak inputs, retained launch guards, parser relocation/resource/license checks and all six branded loader discovery calls. Rig values cache immutable document content, reread paths and return independent maps. Native fixture observers sample the actual owner adoption boundary; ordered evidence requires Save-inert and successful reload receipts.

## Classification and verification

Added 95 explicit identical entries and three precise adapted entries: SettingsReloadGameTests, its modern environment resource (absent on 1.21.1), and ClientSettingsSaveScreen. Updated 75 existing adapted bindings after residual review. No identical production source was reclassified to hide divergence; no wildcard was widened. All reviewed production and test edits are committed in the refs above.

The explicit-ref classification check passed with zero issues at these five commits. Default `python3 tools/compat/ci.py` also passed locally without `--fetch` after updating source refs and regenerating the catalog. Eight focused rig receive checker tests passed; `bash -n test-server.sh` and `git diff --check` passed. The final legacy ordering test reader was reviewed to ensure it includes method bytecode and rejects an empty instruction list.

Local machine-readable evidence: `/tmp/lss-yaml-validation/runtime-line-check-final-reviewed.json` and `/tmp/lss-yaml-validation/runtime-default-compat-ci-final.json`. Both record the exact source identities; the former is reproducible with `tools/lines/lines.py check` using the source-ref catalog.

The catalog's generated compatibility table uses the same exact source commits. No fetch, push, publication or heavy build was performed as part of this final source audit.
