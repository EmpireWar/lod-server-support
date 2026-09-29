# Independent common settings implementation review

Reviewed commit `83876c4c` against its parent and the approved `docs/planning/2026-09-28-yaml-settings-implementation-plan.md` in `/home/vox/projects/lss-settings-plan`. This is a review, not implementation; no repository files were edited. The surrounding working tree contains ongoing tooling/documentation changes, so those are not represented as reviewed commit contents.

I read the codec/schema, migration, store, handle/reload, CLI, templates, relevant original JSON implementation, and existing common tests. I ran two small standalone Java probes against the prebuilt common classes. The schema probe also ran against the actual nested, relocated common jar extracted from the current Fabric artifact, with only Gson additionally supplied: it produced the same results and did not require the stock parser. Existing broad test greens were supplied by the parent; I did not rerun Gradle or rigs.

## Validated findings

### S1 — P2: dotted YAML keys bypass structural/duplicate validation and can silently defeat a successful save

**Location:** `common/src/main/java/dev/vox/lss/common/config/SettingsSchema.java:108-109`; related document writer: `YamlSettingsCodec.java:119` onward.

`flatten` concatenates mapping keys into a dotted path and accepts any resulting descriptor, without checking that a schema key is exactly one segment or that the flattened path was already present. Thus distinct YAML keys can describe the same setting, and the last one silently wins. The writer only traverses actual nested mappings, so an accepted literal dotted key can continue to override the value the user just saved.

Reproduction, accepted by the client codec:

```yaml
config_version: 1
lod:
  receive: true
lod.receive: false
```

`codec.edit(document, Map.of("lod.receive", true))` completes successfully and produces exactly the same effective `receive=false` document. This is more than permissive syntax: saving the intended value does not save that value. The same issue applies to any scalar, including sharing consent. The strict unknown-key/duplicate-key contract in plan §3.1 requires rejection here; either reject dotted schema keys at structural traversal (while retaining arbitrary keys inside documented dynamic maps) or otherwise make parse/edit path semantics identical and reject duplicate semantic paths.

**Coverage gap:** `YamlSettingsCodecTest.rejectsMalformedOrUnsafeYaml` checks duplicate literal keys, not structurally distinct spellings of the same descriptor. `SettingsStoreTest` does not verify the requested value after an edit on this accepted document shape.

### S2 — P2: a failed subsystem adoption is forgotten; retrying reload reports unchanged without retrying or retaining failure

**Location:** `common/src/main/java/dev/vox/lss/common/config/SettingsReload.java:100-112`; `SettingsHandle.java:8-10,61`.

The handle publishes first. When reconciliation fails, the operation releases `busy` and returns `RECONCILIATION_FAILED`, but neither the handle nor the coordinator retains outstanding adoption state. A second reload of the same YAML sees no effective diff, skips the reconciler, and reports `UNCHANGED`. The accepted configuration remains indistinguishable from successfully adopted settings in the handle.

Standalone repro changes `generation.enabled` to false; the first reconciler returns a failed future; the second would succeed:

```text
first=RECONCILIATION_FAILED, retry=UNCHANGED, reconciler calls=1, effective generation=false
```

Plan §5 explicitly requires recording adopted revision/outstanding reconciliation in the handle and retaining accurate inactive/failed/pending outcomes. A genuinely inactive owner may require restart rather than retry, but a later reload still must not erase that outstanding status. For recoverable failures, retries need the previous adopted baseline, not merely the already-accepted snapshot. The production services contain real exceptional adoption paths (inactive processing/store/backfill owners); some are terminal and should remain visibly inactive. I have validated the coordinator failure/retry behavior, not a live transient owner failure.

**Coverage gap:** `SettingsReloadTest.busyPersistsUntilSubsystemAcknowledgesAndFailureDoesNotClaimRollback` ends after the failed outcome. It does not test subsequent reload/status or eventual retry. Existing deadline test covers a still-pending future, not an exceptionally completed one.

### S3 — P2: valid legacy string-list coercions can turn an otherwise working upgrade into inactive LSS

**Location:** `common/src/main/java/dev/vox/lss/common/config/LegacySettingsMigration.java:40-43`.

The old Gson `List<String>` deserializer accepts JSON primitive numbers/booleans as strings. The new adapter only removes null list entries, then applies strict YAML string-list validation to the untouched primitive objects. For example, an old server JSON with an entirely numeric player name left unquoted is valid JSON and was accepted:

```json
{"farPlayersExclude":[12345,"Alice"]}
```

A Gson deserialization using the same `List<String>` field type yields `["12345", "Alice"]`; the new migration throws `Expected string at far_players.excluded_players[0]`. This rejects the whole configuration and leaves server LSS inactive on upgrade. Unlike comments/unquoted keys/nonsensical boolean strings, this is not one of the expressly excluded non-JSON leniencies. The analogous conversion is already explicitly retained for `crossVersionBlockFallbacks` map values at lines 50-55 and in its test.

Keep these legacy primitive-to-string conversions in the migration adapter only, with structural values still rejected. A numeric-only player name is a concrete meaningful exclusion; other string-list fields have the same adapter gap.

**Coverage gap:** null list elements and string-valued fields are covered, but primitive entries in legacy `List<String>` fields are not. The original deserializer's generic string behavior should join the migration corpus.

### S4 — P2: new VSS settings files instruct users to run commands that VSS does not register

**Location:** `common/src/main/resources/dev/vox/lss/settings/client.yaml:2,81,83`; `server.yaml:2`; `paper.yaml:2`; emission at `YamlSettingsCodec.java:37` / `SettingsStore.java:112`.

Creation and migration use the literal LSS templates unchanged, irrespective of the selected prefix/running brand. Creating a VSS client store yields `vss-client-config.yaml` whose second line says `run /lss reload`, and server files say `/lsslod reload`. VSS registers `/vss` and `/vsslod`. In particular, the privacy option's generated explanatory comment directs the user to the wrong activation command.

This was reproduced with `new SettingsStore<>(dir, "vss", SettingsSchema.client()).initialize()`, including against the packaged nested common jar. Build-time VSS repackagers copy the common jar/templates unchanged; their rewrites cover metadata/brand properties and selected locale display text. Generate brand-aware explanatory text for new/migrated files while preserving adopted user files byte-for-byte on reads. The correct approach can remain runtime branding; changing nested-common class/wire identity is unnecessary.

**Coverage gap:** brand tests verify selected filenames/adoption, not generated command instructions. UI/locale command instructions need the separate UI review (parent notified).

### S5 — P2: server migration/candidate warnings are discarded, and clamp feedback omits the promised path/value details

**Locations:** `common/src/main/java/dev/vox/lss/common/config/YamlServerConfig.java:33`; `ReloadFeedback.java:22`; initial document consumption in `SettingsHandle.java:26-27`.

The runtime server facade constructs `SettingsStore` with its three-argument constructor, whose report consumer is a no-op. Consequently normal server startup never emits the store's selected-path ambiguity warning, migration source/target/backup report, ignored-key count, or migration warnings. This affects both mod-loader servers and Paper. The client passes `LSSLogger::info`; the server does not. Plan §4 explicitly requires those local reports, especially when two brand candidates exist or ambiguous Paper names are duplicated.

Separately, startup consumes `initial.normalized()` and discards initial normalizations/inactive-path feedback, while server reload emits only a normalization count. Repro for `generation.concurrency.global: 999`:

```text
normalization records=[Normalization[path=generation.concurrency.global, requested=999, effective=512]]
server feedback=[Reloaded lss-server-config.yaml: 1 active setting(s) applied., 1 value(s) normalized; YAML was left unchanged.]
```

The operator is never told which path changed or that 999 became 512. Plan §3.1 requires every normalization to report YAML path, requested value and effective value. Private descriptors are already redacted in `Normalization`, so retaining useful numeric feedback does not require exposing private lists.

**Coverage gap:** store-level tests use direct stores and inspect file results; they do not assert runtime server logger wiring. No test asserts full server clamp feedback or startup normalization notices.

## Smaller validated contract deviations

- **P3, strict integer-map type mismatch:** `SettingsSchema.java:146` calls `integer` without the `BigDecimal`/`Double`/`Float` rejection used for scalar INTEGER at line 124. YAML `lod.distance.by_dimension: {minecraft:overworld: 12.0}` is accepted and becomes integer 12, whereas `default_chunks: 12.0` is correctly rejected. This is an inconsistent strict integer-type contract. The test matrix only exercises scalar floating-point rejection.
- **P3 / plan disposition needed, migration shape:** `LegacySettingsMigration.java:76-77` emits `by_dimension: {}` (or only legacy overrides) instead of the three explicit vanilla values populated from the old scalar, expressly required by plan §4. A scalar 128 currently remains 128 in every dimension at initial migration, so this is not an immediate radius regression. It does change later fallback-edit semantics relative to the approved schema shape. Existing tests deliberately assert the empty map, which pins an implementation/plan discrepancy rather than validating the written requirement. Either implement the specified explicit vanilla entries or record an approved plan change explaining the equivalent-at-migration alternative.

## Areas checked without a validated defect

- Byte/depth/node limits run before YAML/JSON tree construction; aliases/anchors/tags are rejected at YAML event traversal, and literal mapping duplicates are caught before typed mapping.
- Deep-copy behavior covers maps, nested aliases and list fields. Pending restart/session values use merged immutable snapshots and do not plainly leak through the handle on subsequent reloads.
- Store adoption order, invalid-authoritative-YAML behavior, source-stem migration, original-byte backup collision handling, missing-target saves, and create-only hard-link installation have real focused tests. The portable last-window external-editor limitation is documented rather than falsely claimed to be a filesystem CAS.
- The actual current Fabric jar contains exactly one relocated parser in its nested common jar, with notices/templates and no stock parser namespace; `check_yaml_parser(..., "fabric")` returned no problems. A standalone probe using the extracted packaged jar successfully parsed/generated YAML without the unrelocated parser available.
- Paper/NeoForge final jars did not exist when inspected, so those packaging shapes and Windows/DrvFS atomic creation behavior remain unverified by this review. These are evidence limits, not inferred bugs.
- CLI native create/edit/validate/migrate paths reuse the product schema/store. I found no separate parser implementation or confirmed CLI-only correctness bug in the reviewed commit.

## Reproduction artifacts

- `/tmp/lss-yaml-validation/astra-probes/SchemaProbe.java`
- `/tmp/lss-yaml-validation/astra-probes/ReloadProbe.java`
- `/tmp/lss-yaml-validation/astra-probes/nested-common.jar` (copied from the built Fabric artifact solely for isolated classpath verification)

Compile/run probes with the existing `common/build/settings-cli/classpath.txt`; no Gradle execution is needed. All fixture directories were newly allocated under `/tmp`.

## Root completion record

All validated findings S1–S5 and the fractional integer-map/explicit migrated
dimension-distance findings are resolved. The
[implementation ledger](2026-09-28-yaml-settings-progress.md#independent-implementation-review)
maps each finding to its fix and regression evidence. Final full builds and all
six LSS/VSS artifact checks passed on every maintained line, including the
Paper/NeoForge packaging unavailable at this review's original checkpoint.
Thirty real DrvFS store/handle/CLI tests passed; this does not claim a native
Windows JVM run. Native migration on the three representative support lines
also verified exact JSON preservation/backups, all three explicit migrated
vanilla distances and seconds-to-ticks conversion.
