# Structured YAML settings and explicit reload

Status: proposal; implementation is held for the user's approval of the YAML examples.
Date: 2026-09-28. Base: `origin/main` at `d4b415d5` (released v0.15.1 behavior).
Worktree: `/home/vox/projects/lss-settings-plan`, branch `docs/yaml-settings-plan-20260928`.

The request covers client and server configuration on all five maintained support
lines, Fabric/Paper/NeoForge and both branding variants. This document and its examples
are planning artifacts only. No runtime files, instances, releases or production code
are changed by this planning task.

## 1. Intended behavior and approval choices

- Replace the flat JSON files with `lss-server-config.yaml` and
  `lss-client-config.yaml`. Keep their current directories. The VSS equivalents
  use the `vss-` prefix and retain cross-brand adoption.
- Group related settings, in the file **and in typed Java settings objects**.
  Use `snake_case` keys and explicit units. Preserve the network protocol.
- `/lsslod reload` reads the server file, validates a complete candidate, applies
  supported changes together and lists settings waiting for a restart. The client
  command is `/lss reload`, using its existing distinct client command root;
  `/vsslod reload` and `/vss reload` follow the running brand. A client command must
  never intercept or shadow the server command.
- Make the main server load controls reloadable: generation admission/concurrency,
  backfill enable/rate, store cap/resweep cadence, timestamp-cache budget, disk-read
  concurrency and traffic limits. In-flight work drains safely; a reload is not a
  promise to instantly cancel Minecraft generation or reclaim disk/RAM. Section 6.1.1
  specifies the adoption boundaries and tests rather than treating every constructor
  parameter as permanently restart-only.
- Startup and these reload commands are the only paths that activate settings.
  Remove `set`, runtime preset apply/undo, the status screen's receive toggle,
  and any other settings mutation entry point. Diagnostics, cache/reset commands
  and operational store jobs remain; they must not publish settings.
- Sodium Apply saves a **draft** to YAML. It does not change the live client or send
  preferences. Show a persistent translated notice: “Saved; run /lss reload to
  apply.” Pending privacy changes must be visibly identified as not yet active.
  Restarting the client also reads the saved settings. No automatic menu-triggered
  reload, file watcher, or second apply mechanism.
- Fresh server defaults: Overworld **512 chunks**, Nether **64 chunks**, End
  **512 chunks**; other dimensions fall back to 512. Migration preserves existing
  distance choices (including an old all-world scalar); it does not silently force
  the new Nether default onto an existing configuration.
- Remove automatic far-player suppression based on another mod's presence and the
  associated override option, probe, messages, menu conditions and tests. Users
  control far players explicitly. Remove project references except one inspiration
  acknowledgement in README. Keep far-player sharing privacy independent from
  whether the local renderer is enabled.
- Complete English, Simplified Chinese (`zh_cn`) and Traditional Chinese (`zh_tw`)
  strings for every shipped Sodium option, label, conditional tooltip, status and
  pending-change message on both menu generations.

Approval examples:

- [Fabric/NeoForge server](2026-09-28-yaml-settings-examples/lss-server-config.yaml)
- [Client](2026-09-28-yaml-settings-examples/lss-client-config.yaml)
- [Paper server](2026-09-28-yaml-settings-examples/lss-server-config.paper.yaml)

The Paper example is a complete alternative server file, not a second file to load.
The intended decisions requiring user review are the layout, disk-only Sodium Apply,
distinct server/client reload roots, conservative migration, and restart classifications.

## 2. Current implementation and scope inventory

The existing inventory has 45 shared server fields, 30 client fields and one
Paper-only field. Two server fields are retired byte-rate aliases; one client
field is the removed automatic-coexistence override. Preserve the meaning of
every remaining setting, including hidden expert toggles, zero sentinels and
the existing-install store-off/fresh-install store-on distinction.

| Area | Current files and work required |
| --- | --- |
| Persistence | `common/.../config/JsonConfig.java`, `ServerConfigBase.java`, `xplat/.../config/LSSClientConfig.java`, `LSSServerConfig.java`, `paper/.../PaperConfig.java`: replace active JSON reflection and scratch serialization with YAML document IO, typed records, explicit migration and settings handles. |
| Server changes | `RuntimeSettings.java`, `SettingsPatch.java`, server/paper command classes and server presets: delete mutation registry, key parsers, listing/completion and apply/undo; retain genuinely reusable validation outside that registry. |
| Server consumers | RequestProcessingService twins, world-distance resolvers, handshake/session config, admission, processing/cache/store setup, broadcasters, diagnostics: read appropriate immutable active or boot snapshots. |
| Client changes | `ClientPresets`, `ClientCommandActions`, Fabric commands, `LSSNeoClientBootstrap`, `ClientStatusScreen`, both modern `LSSConfigMenu` twins and both `LegacySodiumPage` twins: remove alternate live mutations, use draft storage and client reload. |
| Client transitions | `ClientNetGlue`, `ClientSessionGate`, `LodRequestManager`, scanners, consumer bridges and far-player prefs/renderer: only effective snapshots drive behavior; preserve receipt retirement, committed map work and privacy. |
| Metadata | `SettingDescriptor`, `SettingBinding`, `*SerializedSettings`, `ClientOptionCatalog`, `tools/settings/{inventory,reference}.py`: replace flat-field/RuntimeSettings scraping with nested-path schema metadata. |
| Tooling | `test-server.sh`, soak configs/drivers, benchmark/backfill scripts, rig preparers, compatibility settings identity, release checker, test fixtures and docs: write/read YAML and exercise reload explicitly. |

Read the existing load, clamp, runtime batch, world-distance, client lifecycle,
Sodium draft and privacy tests before replacement. Replace obsolete behavior pins
with the new explicit contracts; do not delete their correctness coverage.

## 3. YAML contract

`config_version: 1` is mandatory in generated/migrated files and in user-authored
YAML. It versions the local schema, not the wire protocol. Unknown future versions
are rejected without rewriting. A missing YAML file is not a request to regenerate
it during reload: report the missing path and keep active settings unchanged.

Use UTF-8, two-space indentation, block mappings/sequences and inline `#` comments.
All platform-applicable settings are emitted on fresh creation, grouped by purpose; expert
options have explanatory comments rather than disappearing from the reference.
Fresh Fabric/NeoForge files omit the Paper-only world-name map and event section;
fresh Paper files omit the inactive backfill group. A cross-platform
copied file can retain recognized inactive settings with a clear warning.

Comments must explain features as well as individual values. Add a short comment
block above each related group describing its purpose, practical benefit, resource
costs and important interactions. Keep units, zero meanings and activation timing
beside the actual keys. Usually two to four lines are enough; the LOD store needs
more context because it deliberately trades additional disk space and writes for
less repeated parsing, serialization and compression work.

Required feature explanations in both generated defaults and migration output:

- Terrain radius versus covered area; distinguish distant data from vanilla render
  distance, simulation distance and loaded/ticking chunks.
- Missing-terrain generation versus caching existing terrain, including CPU load,
  normal world-file growth and the effect of concurrency.
- Bandwidth limits versus compressed wire traffic, client column-rate limits versus
  bytes, and the fill-speed tradeoff of pacing/adaptive controls.
- RAM timestamp metadata versus the persistent prepared-column store, including
  what can be reused across players/restarts and what still incurs disk access.
- Store disk growth depends on terrain and coverage; a fully warmed store can be
  comparable in size to region data, not a guaranteed multiplier or speedup.
  Disabling it does not delete its existing files. Size limits reduce reuse through
  eviction and do not represent a hard total-filesystem quota.
- Backfill prepares existing terrain ahead of requests and costs background CPU/IO;
  it does not generate new chunks. Periodic resweep serves a different freshness
  role, particularly for Paper edits without a matching event, and depends on saves.
- Client freshness-cache identity versus consumer storage; Xaero's persistent map
  writes, resource costs and the fact that disabling writes does not erase the map.
- Far-player rendering/animation costs and the independence of local visibility,
  position-sharing consent and normal Minecraft entity tracking.

Keep shared descriptions in schema/group metadata with platform-specific additions
for Paper freshness and backfill availability. Defaults, migrated files and reference
documentation should agree; preserve the user's own comments on later explicit
menu saves. Explain product behavior in plain language rather than code/class names.
Check generated comment coverage alongside key/default coverage, including comment
retention after migration and a subsequent menu edit. Avoid benchmark-specific
performance promises in generated settings files.

Naming and units:

- Distances: `_chunks` for terrain radii, `_blocks` for entity radii and world Y.
  One chunk is 16 blocks; do not reinterpret existing values during renaming.
- Memory/disk: `_mib` (1,048,576 bytes). Bandwidth: `_mib_per_second`, still charged
  against uncompressed payload work, **not** actual compressed network throughput.
- Wall-clock durations: `_seconds`. Tick-driven far-player cadence, dirty-broadcast
  cadence and generation timeout use `_ticks`; calling ten ticks “0.5 seconds” would
  be false on a lagging server. The old `generationTimeoutSeconds` and
  `dirtyBroadcastIntervalSeconds` count ticks after multiplying by 20: use
  `generation.timeout_ticks: 1200` (range 20–12000) and
  `updates.dirty_broadcast_interval_ticks: 200` (0 disables pushes, otherwise 20–6000).
  Preserve their 60-second/10-second-at-20-TPS defaults without changing the clock.
- Throughput: `_columns_per_second`. Counts use descriptive count/limit names.
- Booleans are `true`/`false`; string enums are quoted. New mode spelling is
  `"opt_in"`, mapped explicitly to the current far-player runtime policy.
- Keep established numeric zero meanings and document each inline: automatic,
  inherited, unlimited or disabled depending on the setting. Do not invent a
  general string-or-number parser for `auto`/`unlimited`.
- Unset known keys use schema defaults. An explicitly supplied collection replaces
  its default collection; `{}`/`[]` mean empty, not “restore defaults.” In particular,
  `lod.distance.by_dimension: {}` makes every dimension use `default_chunks`.

### 3.1 Validation and readable errors

Use a bounded YAML parser and explicit typed mapping, never JavaBean construction.
Reject duplicate keys, aliases/anchors, merge keys, explicit custom tags, complex
mapping keys, multiple documents, invalid UTF-8, excessive input/depth, null/wrong
types, nonfinite numbers, numeric overflow, invalid enums and unknown schema paths.
Dynamic world/identifier maps permit their documented arbitrary keys; they are not
unknown schema paths. Reject quoted strings where a number/boolean is required.
Reject empty/comment-only files rather than silently enabling defaults.

Keep established clamps for well-typed finite numeric inputs and report every
normalization by YAML path, requested and effective value. Preserve zero semantics
and cross-field relationships (generation limits and far-player rings). Syntax,
type or unknown-key errors reject the **whole** reload. Identifier validation that
needs MC registries occurs on the relevant owner before publication. Unknown
modded block/event identifiers retain their documented skip/fallback treatment
with a path-specific warning; private list contents are not exported.

Do not re-save on startup or reload. A normal read preserves the file byte-for-byte,
including comments. Fresh creation, successful JSON migration and explicit Sodium
draft saves are the only ordinary writes. Keep configured values separate from
normalized active values so a numeric clamp never silently edits the user's file.

### 3.2 YAML library and packaging

Plan to use `org.snakeyaml:snakeyaml-engine:3.1.1`, pinned across all support lines.
Maven Central metadata and the published POM/source were inspected on 2026-09-28:
Java 11 bytecode target, no production dependencies, comment-node parse/dump APIs.
Source artifact SHA-256:
`2896d52c792259f648d0f0ef23cf87ee7bbd1c37837e46ad0d27d12041b6a39c`.
Recheck the exact binary/license before implementation packaging; do not use a
floating dependency or the server's ambient YAML library.

Use its low-level representation tree with comment parsing/emission enabled and
a strict scalar schema. Explicitly validate duplicates, allowed tags, path types,
node counts and nesting: constructor-only safeguards do not necessarily apply to
the compose-only path. Limit input to 1 MiB, nesting to 32 and scalar/container
nodes to 50,000. Enforce these budgets at the event/stream layer **before** building
either the YAML representation tree or legacy JSON tree; then validate composed
paths/types. Reject YAML aliases at the event layer before composition. Test depth
32 accepted / 33 rejected, excessive shallow nodes and deep sub-1-MiB input in both
formats. Keep parsing linear/bounded; catch failure without touching active state.

Shade/relocate the dependency once into an internal namespace in the common
runtime artifact, consumed consistently by Fabric's nested common jar and the
Paper/NeoForge final jars. Do not add an unrelocated module or duplicate it in both
the outer mod and nested common jar. Keep `common:test` MC/loader-free on Java 21,
retain Apache license notices, and extend artifact checks for presence, relocation
and absence of split packages. Inspect actual Jar-in-Jar shapes on every loader.

Source references: [upstream project](https://github.com/snakeyaml/snakeyaml-engine),
[published dependency](https://repo.maven.apache.org/maven2/org/snakeyaml/snakeyaml-engine/3.1.1/),
[comment loading API](https://javadoc.io/static/org.snakeyaml/snakeyaml-engine/2.10/org/snakeyaml/engine/v2/api/LoadSettingsBuilder.html),
[comment emission API](https://javadoc.io/static/org.snakeyaml/snakeyaml-engine/2.10/org/snakeyaml/engine/v2/api/DumpSettingsBuilder.html).
The linked API documentation is the older accessible documentation; the selected
3.1.1 source was inspected separately rather than assuming API compatibility.

## 4. One-time JSON migration

Migration is a tree-to-tree adapter, not a second runtime settings system.
Keep Gson only where still needed elsewhere and for parsing a bounded JSON tree.
Remove JsonConfig, hidden-field annotations, JSON scratch copies and legacy fields
from runtime classes once call sites are ported.

Selection happens once per client/server settings handle:

1. Existing brand-preferred YAML; otherwise other-brand YAML.
2. Only if **neither YAML exists**, brand-preferred JSON; otherwise other-brand JSON.
3. If none exists, create a commented default YAML for the current brand/platform.

An existing invalid YAML is authoritative enough to prevent fallback to stale JSON
or another brand. A selected JSON source produces a YAML with the **source's stem**,
even when adopted by the other brand. Warn when more than one candidate exists;
name the selected path. Continue reading/saving/reloading that exact adopted path
for this process. Preserve current plugin data-folder behavior; don't invent a
new search across unrelated plugin folders.

Migration transaction:

1. Read original bytes; validate one JSON object with no duplicate keys and a bounded
   size. Apply explicit compatibility transforms/defaults, then new-schema validation.
2. Build a fully commented YAML candidate and a concise migration report.
3. Exclusively create and verify a unique original-byte backup (`.json.migrated.bak`, suffix
   on collision) before publishing YAML. Keep the original JSON unchanged, too.
4. Write to a unique same-directory temporary file, flush, parse it with the real
   YAML codec, then recheck original source bytes and both YAML candidate paths.
   Install using a tested create-only primitive (for example, a hard link from the
   completed temp file to the new path, followed by temp cleanup). A check-then-move
   is insufficient: Java atomic move does not promise no replacement. A concurrent
   new target or changed source aborts migration; reselect/retry at next startup.
   Where atomic create-only installation is unsupported, fail with an actionable
   error rather than truncate a target. Test Linux/WSL and Windows/DrvFS behavior.
5. Record the source, target, normalized paths and ignored-key count in local logs.
   Do not log identifying lists/aliases or echo arbitrary unknown JSON key names.
   Once YAML exists, JSON is ignored forever unless the operator deliberately
   removes/moves the YAML outside the running process.

Malformed input or IO failure must preserve all source bytes and must not create a
partial authoritative YAML. Minecraft itself need not crash: leave the affected
LSS side inactive with a visible configuration error. Register the command/status
surface even when startup configuration fails; the message identifies whether
repair requires restart. Do not quietly activate sharing/generation defaults after
a failed migration. With valid YAML but an uncreatable fresh file, report failure
and avoid claiming settings were saved.

Compatibility rules that must survive:

- Existing JSON without `lodStore` => explicit YAML `storage.lod_store.enabled: false`;
  fresh YAML => true. `on`/legacy `full` => true; old `off`/`memory`/invalid => false.
- Newer legacy MiB keys take precedence when nonnegative; otherwise adopt the old
  byte keys divided by 1,048,576; otherwise defaults 25/75. Normalize the same
  bounds. No byte aliases or missing-key sentinels survive in new runtime objects.
- Normalize legacy `generationTimeoutSeconds` using its old default/clamp (1–600),
  then multiply by 20 into `generation.timeout_ticks`. Missing/null becomes 1200;
  test 1, 60 and 600 seconds as 20, 1200 and 12000 ticks. Preserve the existing
  timeout boundary and ownership rules; this is a unit correction, not a new clock.
- Likewise convert legacy `dirtyBroadcastIntervalSeconds` after its existing clamp
  to ticks × 20, preserving zero as disabled pushes and the separate 200-tick
  invalidation-drain cadence. Missing/null becomes 200; test 0, 1, 10 and 300 seconds.
- Migrate every current persisted setting from the mapping tables, including hidden
  expert overrides. Unknown/retired JSON keys are ignored generically. No literal
  reader, encoded workaround or fixture for the removed coexistence override.
- Preserve explicit empty collections, client sharing opt-outs, aliases and fallback
  mappings. Adopt known historical aliases in this adapter only; inventory prior
  serializer history before implementation and test the supported legacy corpus.
- Existing JSON distance scalar becomes `default_chunks`. Explicitly generate
  all three vanilla dimension values from that legacy scalar, then overlay old
  per-world values. Thus `{lodDistanceChunks: 128}` stays 128 everywhere; an empty
  old JSON object retains the old effective 512 in each normal dimension. The new
  512/64/512 defaults are for fresh YAML, not an unannounced migration policy change.
- On Fabric/NeoForge legacy override keys are exact dimension ID strings. First
  apply the old trim/drop/distance-clamp rules. Only retain already fully qualified,
  canonical ID strings in the new dimension map: do not prefix `minecraft:` or
  otherwise change spelling. Previously inert unqualified/invalid keys stay inert
  (omit them with a count/index warning and retain original bytes in the backup).
  On Paper, the old map
  used a shared namespace (world-name first, then dimension ID). The migration must
  preserve that ambiguity: copy every surviving exact key into `by_world` and copy
  already fully qualified, canonical resource-location entries into `by_dimension`
  as well, without changing their spelling. This preserves both
  meanings, including custom worlds named like an identifier, without needing to
  load every world at migration time. Warn that duplicate interpretations were kept.

### 4.1 Explicit legacy normalization matrix

Apply these rules in the tree adapter **before** strict new-schema validation.
Missing values inherit old constructor/platform defaults, not fresh YAML defaults.
Do not retain a second reflective old-config class hierarchy.

| Legacy shape | Migration result |
| --- | --- |
| Null primitive boolean/numeric field | Old compiled/platform default for that field; preserve valid neighboring settings. |
| Far-player mode `opt-in`, `optin`, `opt_in` with whitespace/case variation | Normalize to YAML `"opt_in"`; trimmed on/off accepted. Unknown/null => `"off"`, preserving privacy. |
| Unknown/null x-ray mode | `"auto"`; do not substitute `"on"` or reject an otherwise valid old file. |
| Missing x-ray hidden-block list / null list / empty list | Old default list / old default list / empty list respectively; preserve unknown IDs with the existing skip warning. |
| Null far-player exclusions or world overrides | Empty collection. Invalid/blank/overlong world entries follow old drop/clamp behavior; never broaden matching. |
| Paper missing events / null events / empty events | Default event list / empty list / empty list. Drop null event entries while retaining valid neighbors; unresolved event classes keep the existing registration skip behavior. |
| Null/blank default block fallback | `minecraft:stone`; validate resolution on the client owner, never accept an air fallback effectively. |
| Null block fallback map / null or blank entries | Empty map / drop bad entries and retain good neighbors. |
| Null alias list | Empty list. Preserve original spelling/order in valid alias groups; never normalize canonical address text into a different cache identity. Well-typed but invalid groups remain documented inactive groups; structurally invalid elements follow existing safe group rejection with a count-only warning. |
| Legacy quoted booleans / numbers | Convert case-insensitive `true`/`false` and finite numeric strings explicitly; integer fields must have an exactly representable integer value. Reject other coercion tricks/overflow with an actionable migration error, never silently choose fresh defaults. |
| Legacy null elements in other string lists | Keep meaningful neighboring entries; remove elements the old effective resolver ignored. Document each rule in the migration table and corpus. |

Use the real existing load/clamp/alias fixtures as the corpus, including harmless
unknown keys and nulls. Already-corrupt JSON, non-object input and unsupported Gson
leniencies (for example comments, unquoted names or nonsensical boolean strings)
produce a repair message and preserve bytes. Acceptance of these non-JSON quirks
is deliberately not a promise of the new migrator. New YAML remains strict.

## 5. Typed settings and reload ownership

Introduce small immutable records grouped like the YAML (ServerSettings,
ClientSettings and their nested groups) in `common`; MC-specific identifier
resolution remains in platform adapters. Deep-copy maps/lists, including nested
alias groups. Keep mutable YAML nodes and UI drafts out of the active records.

Each side has a stable settings handle with a single immutable state publication:
accepted configured snapshot, effective runtime snapshot, boot/session values,
revision and pending paths. A server service retains the handle/supplier, not a
stale config object. Readers capture one effective snapshot per logical operation;
related generation limits, distance maps and player rings never come from different
revisions. Pending restart values must never leak into handshake, gate, store,
wire generation flags, diagnostics “effective” fields or later unrelated reloads.

Separate schema metadata (path, type, default, units, limits, platform, sensitivity,
timing and translated label) from UI widgets. One authoritative descriptor model
drives defaults, YAML comments, docs and mapping coverage; menu widgets retain
their explicit typed bindings. Generated metadata tools must understand nested
paths, not scrape public flat fields or the deleted runtime command registry.

Reload sequence:

1. Authorize command on the caller's platform (same server-admin permissions as
   today; local client reload needs no server op status).
2. Serialize requests per settings handle; report busy rather than allow overlapping
   parses/commits. Read/parse on a bounded IO worker. Preserve owner/session identity,
   source hash and revision; enforce a completion deadline and cancel on teardown.
3. Build and validate a detached candidate. Diff against effective/configured/boot
   state, partition complete dependency groups into reloadable and deferred groups.
4. Validate the **merged effective** snapshot as well as the configured candidate.
   A hot change cannot become valid only because a correlated restart-only value
   changed. If a safe merged snapshot cannot be built, defer that whole group with
   an explicit reason rather than partially applying or silently changing a partner.
5. Recheck lifecycle/revision and file identity, then publish once on the owner:
   Fabric/Neo server thread, Paper service control mailbox/pump (with a disabled-
   service control path), client game thread. Folia uses the established lifecycle
   mailbox/global scheduler and region-owned sends; never mutate region state from
   the IO worker or generic command thread.
6. Reconcile changed subsystems once, then report applied, unchanged, normalized,
   restart/reconnect pending, inactive-on-this-platform and any reconciliation
   failures. A parsing/validation failure performs no publication or side effects.
   Late network failures after publication are reported and retried/contained by
   the owner; never falsely claim a whole reload rolled back after packets escaped.

An immutable settings publication does not itself acknowledge adoption by the
processing thread, store batcher or backfill worker. Send revisioned policy updates
through their existing owners; record the last adopted revision and outstanding
reconciliation in the handle. A successful “applied” result requires the relevant
owners to accept the policy, not completion of old work or storage reclamation.
Prepare/validate updates before publication; never wait synchronously on a tick or
Folia region thread for another owner. A stopped/unhealthy owner gets an explicit
inactive/failed/cancelled outcome, not a fabricated success or indefinite wait.
Late acknowledgements cannot act on a newer revision or replacement lifecycle.
If the reporting deadline expires after publication, complete the command with
“accepted; adoption pending” and the affected paths, not “applied” or “rolled back.”
The still-current revision may finish adoption later; only supersession/teardown
invalidates that work. Distinguish this from pre-publication timeout (no changes).

Successful reloads never write settings. Re-running an unchanged file is a no-op,
including no repeated session-config pushes or roster floods. File edits after the
read are either detected before commit or reported as a newer disk revision; a
reload must not overwrite them. Rapid reloads, disconnect, server stop and world
replacement cannot let a stale completion act on a new lifecycle.

Completion is owned by the settings handle, including timeout/cancellation when
the Paper service mailbox stops accepting work. Do not inherit the current runtime
task queue's permitted silent drop during shutdown. A sender must get a terminal
outcome even when the service is absent or stops between parse and publication.

Example feedback:

```text
Reloaded lss-server-config.yaml: 4 settings applied.
Restart required: storage.disk.reader_threads, storage.lod_store.enabled.
2 legacy clients need to reconnect for the new distance.
```

Server controls remain registered when LSS service is disabled. A service-enable,
store-enable or other restart-only change is acknowledged as pending rather than
attempting to construct a second running service. The server's own `/reload` is
not an LSS settings entry point.

## 6. Reload timing and complete field mapping

`H` = applies through explicit reload on the owner. `S` = accepted by reload, takes
effect at the next client session/reconnect. `R` = accepted, requires process/server
restart. A disk edit alone has no effect. Startup reads everything normally.

The classifications below permit policy changes inside existing services while
keeping resource topology, storage identity and negotiated-format changes conservative.
Implement H paths with real lifecycle tests; if any advertised H path proves unsafe,
move it to R/S in the schema, examples and approval notes before claiming completion.

### 6.1 Server

| Current field | YAML path | Timing / detail |
| --- | --- | --- |
| enabled | service.enabled | R |
| requireServicePermission | service.require_permission | H; reconcile gates, preserve fail-open backend policy and legacy rejoin limits |
| lodDistanceChunks | lod.distance.default_chunks | H; re-push current sessions, recompute AUTO cache budget from effective maximum radius |
| lodDistanceChunksByWorld | lod.distance.by_dimension / by_world | H; full replacement, world > dimension > default |
| mbPerSecondLimitPerPlayer | network.bandwidth.per_player_mib_per_second | H; raw payload bytes |
| mbPerSecondLimitGlobal | network.bandwidth.global_mib_per_second | H; raw payload bytes |
| bytesPerSecondLimitPerPlayer / bytesPerSecondLimitGlobal | migration only | No runtime fields |
| sendQueueLimitPerPlayer | network.send_queue_limit_per_player | H; admission ceiling, existing queue and in-flight completions drain |
| enablePingBackstop | network.ping_backstop | H; disabling clears current reductions |
| enableSendPacing | network.send_pacing | H |
| lodYieldsToVanillaTransport | network.yield_to_vanilla | H; next flush uses new policy, preserve queue and starvation-floor accounting |
| enableChunkGeneration | generation.enabled | H; gate new work, drain admitted jobs, refresh current sessions; legacy clients may need reconnect |
| generationConcurrencyLimitGlobal | generation.concurrency.global | H; publish pair together |
| generationConcurrencyLimitPerPlayer | generation.concurrency.per_player | H; bounded by configured/effective global |
| generationTimeoutSeconds | generation.timeout_ticks | H; old seconds × 20; new jobs capture timeout, existing jobs keep theirs |
| dirtyBroadcastIntervalSeconds | updates.dirty_broadcast_interval_ticks | H; old seconds × 20; zero disables pushes, not invalidation drain |
| enableRegionSummaries | updates.region_summaries | H; stop new requests, finish already accepted work; enabling serves future requests |
| diskReaderThreads | storage.disk.reader_threads | R; zero auto |
| maxConcurrentDiskReads | storage.disk.max_concurrent_reads | H; use active pool/store state; in-flight reads finish |
| useBackgroundReadPriority | storage.disk.background_priority | R |
| useBackgroundReadSplit | storage.disk.split_background_reads | R |
| perDimensionTimestampCacheSizeMB | storage.timestamp_cache_mib_per_dimension | H; zero auto, bounded trimming on processing owner |
| missMemoTtlSeconds | storage.miss_memo_ttl_seconds | H; clear old miss memos when changed; zero disables |
| lodStore | storage.lod_store.enabled | R; legacy mode converted to boolean |
| lodStoreMaxMB | storage.lod_store.max_size_mib | H; zero unlimited, existing batcher gradually enforces logical-size cap |
| lodStoreResweepSeconds | storage.lod_store.resweep_interval_seconds | H; reschedule next periodic sweep; Fabric/Neo 0, Paper/Folia 300 |
| lodStoreBackfill | storage.lod_store.backfill.enabled | H; stop at column boundary / resume saved progress; inactive on Paper/Folia |
| lodStoreBackfillColumnsPerSecond | storage.lod_store.backfill.columns_per_second | H; new ceiling within running rate window; inactive on Paper/Folia |
| useSelectiveNbtParse | serialization.selective_nbt_parse | H; snapshot per read; inactive on paths/platforms without raw split parsing |
| useNbtTranscode | serialization.nbt_transcode | H; snapshot per read, wire/mask semantics unchanged |
| useCompressedColumns | serialization.compressed_columns | R |
| enableV16Compat / enableV18Compat / enableV19Compat | compatibility.protocols.v16 / v18 / v19 | R; no live dialect switch |
| enableViaMismatchGuard | compatibility.via_mismatch_guard | R |
| xrayObfuscation | privacy.xray.mode | R; changing cache/masking identity hot is out of scope |
| xrayHiddenBlocks | privacy.xray.hidden_blocks | R; explicit empty remains empty |
| xrayMaxBlockHeight | privacy.xray.max_y_blocks | R; exclusive cutoff as today |
| farPlayers | far_players.mode | H; off clears rosters; sharing/vanish filters remain |
| farPlayersUpdateIntervalTicks | far_players.update_interval_ticks | H; group snapshot read at broadcast |
| farPlayersMaxDistanceBlocks | far_players.distance.max_blocks | H; validate ring together |
| farPlayersMinDistanceBlocks | far_players.distance.min_blocks | H; validate ring together |
| farPlayersSendSpectators | far_players.send_spectators | H |
| farPlayersExclude | far_players.excluded_players | H; private list, no exported values |
| updateEvents (Paper) | paper.update_events | R; no dynamic event re-registration |

The far-player server group is rebuilt for every broadcast already; new immutable
publication makes the whole policy coherent. On a privacy-tightening reload,
reconcile retained rosters on the owner promptly, including exclusion/spectator
changes; do not wait for a player to leave/rejoin. Preserve cadence semantics.

### 6.1.1 Performance reload implementation contracts

Source pass against the proposal base found thirteen additional server fields that
can move from R to H with contained changes. These are planned capabilities, not
claims that the released mutable config already reloads them correctly.

**Generation admission and timeout.** `RequestProcessingService` and its Paper twin
currently construct generation only when enabled; `OffThreadProcessor` and
`IncomingRequestRouter` capture final availability booleans. Replace these with a
coherent revisioned admission policy. Construct a dormant generation controller at
service startup even when generation is disabled; its constructor must not start
loads, acquire tickets or create worker activity. Keep that same controller until
shutdown so disabling never destroys live ticket ownership or deferred releases.
Controller existence must no longer imply permission to generate: replace every
null-based availability decision with the effective admission policy.

- On disable, close admission on the processing owner and recheck the policy at
  the platform's actual ticket/async-load submission boundary. Requests already
  queued but not started must release tracking/slots exactly once through the
  normal completion path; a policy-raced rejection is transient. Jobs whose world
  generation has actually started finish or time out and release normally. Retain
  drain/tick/cleanup paths while disabled. Minecraft's work cannot be forcibly
  cancelled just because the setting changed; report outstanding work as draining.
- On enable, make controller limits, router availability and handshake flags agree
  before announcing the new policy. Current-protocol clients get one session-config
  refresh so previously permanent NOT_GENERATED positions can be requested again.
  Fence/drain or invalidate queued old-policy terminal responses before this refresh;
  late old callbacks must not mark a new request session permanently unavailable.
  Audit server done/pending state too, not just the client's scanner reset. Preserve
  connection-frozen client settings across this refresh. Legacy clients keep their
  supported session behavior and are counted as needing reconnect where necessary;
  server-side generation-off enforcement still applies to every dialect.
- Capture `timeout_ticks` on the first admission of a world/column job. Piggybacked
  listeners inherit that job's deadline; edits do not restart its elapsed ticks,
  time out a running batch all at once or extend existing deadlines. Concurrency
  reductions block new admissions until occupancy falls; do not cancel excess jobs.
  On Folia, serialize control intent through the service mailbox and retain the
  existing region-owned load/callback and generation-token checks.
- Tests: disabled-at-boot → enabled; enable → disable with disk results, queued
  tickets and active/piggybacked jobs; rapid off/on; timeout old/new jobs; cap shrink;
  no leaked tickets/slots; stale NOT_GENERATED after re-enable; CURRENT client retry
  without reconnect and honest legacy reconnect reporting. Extend the existing
  generation ownership/service tests and a real generation lifecycle gametest.

**Backfill enable/rate.** `StoreBackfill` already has stop-at-column-boundary and
resumable region marks, but captures a final rate. Read an immutable current policy
at each column/rate-window boundary. Lowering the rate keeps the work already
charged in that window; raising it does not reset the window and mint an extra
burst. Keep visited/skip columns charged, and retain tick-health, reader-headroom,
store-health, size-cap and free-space guards. A reload must not join the worker on
the server thread or interrupt an active world read just to change its rate.

Serialize start/stop intent so off → on while the old worker exits starts exactly
one successor after exit; never clear a stop request aimed at the old run. Preserve
deposit-before-done-mark ordering, reader connection cleanup and lifecycle guards.
Report stop requested/draining until the current column finishes. Enabling resumes
unfinished progress; unchanged reloads do not restart completed or manually stopped
jobs. Raising/removing a blocking store cap may resume a job stopped for that reason
only, after the batcher adopts the cap, without an automatic restart loop.

Clarify the retained operational commands: `store backfill start/stop` start or
pause a job without editing settings. `start` requires the active backfill enable
flag and an active healthy store; when disabled, direct the operator to edit YAML
and reload. `stop` leaves it manually paused; a rate-only/no-op reload does not undo
that pause. A real false → true reload or an explicit permitted start resumes it.
This makes the YAML flag a master enable gate instead of its old startup-only
meaning. Test rate changes mid-window, manual pause, rapid toggle, capped/finished
jobs, store-disabled pending enable, shutdown and failed worker startup. Paper/Folia
keeps this recognized group inactive and creates no backfill worker.

**Store cap and sweep cadence.** Move `maxDbBytes` and `resweepSeconds` out of the
fixed policy in `SqliteLodStore.Environment` into a coherent policy adopted by the
existing batcher's control queue. Keep store path, identity, connections, schema,
startup sweep and masking policy fixed. Do not reopen SQLite to change these knobs.
After adoption, `sizeCapBytes()` and backfill's cap guard see the same policy.
Replace backfill's current run-start cap capture with checks of the adopted cap at
column boundaries, so a running job respects a lowered limit too.

Lower caps use the existing bounded eviction/vacuum maintenance, preserving live
database accounting; never perform synchronous mass deletion or claim immediate
filesystem reclamation. Raising/zeroing the cap releases only the cap constraint.
For resweeps, zero removes the next periodic deadline, positive values schedule
from adoption time, and unchanged values retain their schedule. An active sweep
can finish before adoption; report it pending while busy. Do not rerun or skip the
startup sweep, reset freshness stamps, or accidentally schedule a zero-delay loop.
Test bounded cap shrink, capped → uncapped, interval shortening/lengthening/zero,
reload during a sweep/transaction and degraded/closed-store outcomes. Changes while
the store is inactive are reported inactive; a pending store-enable cannot open it.

**Timestamp RAM budget and miss TTL.** `ColumnTimestampCache` holds dynamic tile
maps, not a fixed preallocation, so neither setting requires replacing the cache.
Adopt budget/derived miss-memo cap/TTL together on the processing thread. A budget
increase permits growth without allocating it eagerly; a decrease schedules
bounded trimming using the existing freshness-safe eviction rules. Do not sort or
delete an entire large cache on a tick thread or make reload wait for full trimming;
preserve remaining stamps, live counters and safe save snapshots. Avoid whole-cache
reconstruction and keep processing work responsive under shrink pressure.

When TTL changes, discard existing miss memos (their old deadlines cannot represent
the new policy correctly), retaining real timestamps. Zero both clears and disables
the memo; a positive value governs future misses. Recompute AUTO budget from the
merged effective maximum radius when a distance override/default changes. Explicit
cache budgets stay explicit; pending unrelated boot values never influence AUTO.
Tests cover grow/shrink under queued reads/save snapshots, maximum override removal,
AUTO ↔ explicit, TTL shorten/lengthen/zero and no stale misses after generation/save.

**Queue limits, transport yield and summaries.** Queue size is already passed in
`TickSnapshot` to the router's admission check, and yield is consulted per flush.
Expose those paths through the effective settings snapshot. Lower queue limits
block further admission while existing sends and in-flight completions drain;
the configured ceiling is not a promise that occupancy instantly fits underneath.
Changing yield retains queued data and existing starvation-floor accounting: do
not erase the counter on every non-yield tick (that reintroduces a pinned starvation
bug). Test both switches with an unwritable channel, exhausted bandwidth and queued
payloads, then prove normal sending resumes without dropped/duplicate columns.

Region-summary services already exist when their flag is off and start their
sweeper lazily. Gate new request admission on the hot policy; finish bounded work
accepted before disable and retain normal frame retry/expiry and session ordering.
Do not stop the stamp table or dirty invalidation; they serve other correctness
paths. Re-enabling handles future requests without synthesizing a full-client
refresh. Current clients request on dimension entry/rejoin, so do not promise an
immediate rescan for an already connected client; ordinary column scanning remains
available. Test disable during assembly/send retry, subsequent enable and no stale
frame replay or permanently stalled ordinary acquisition.

**NBT processing switches.** `ChunkDiskReader` and `PaperChunkDiskReader` currently
capture final `useNbtTranscode` (and xplat selective-parse) booleans, but these choose
byte-equivalent per-read implementations rather than a different cache identity or
wire dialect. Capture one immutable serialization policy at read submission and
use it throughout fetch/decode; backfill captures one per column too. Existing work
finishes under its old policy. Keep mask identity, compatibility latches, reader
topology and compressed frame negotiation unchanged. Do not invalidate stored rows
just to change an equivalent reader. Selective parsing has no effect on
already-parsed NBT paths; report that applicability accurately. Extend
the real disk-reader, golden byte-parity and masking tests with toggles while reads
are queued; prove both old and new policies produce the same on-wire columns.

### 6.1.2 Deliberately retained restart requirements

| Setting/group | Why it remains restart-only / practical hot alternative |
| --- | --- |
| Whole `service.enabled` | Creates/retires the complete network/processing/store lifecycle. Use generation/backfill gates and traffic/read limits to reduce work live. |
| `storage.disk.reader_threads` | Pool size is coupled to bounded work/park queues and adaptive throttle sizing. `max_concurrent_reads` gives live disk-pressure control inside the active pool. |
| `storage.disk.background_priority`, `split_background_reads` | Select executor/read ladders, AUTO pool sizing and compatibility fallback behavior. Keep their established latches/topology fixed; tune active read concurrency instead. |
| `storage.lod_store.enabled` | Attaches readers, codecs, database lifetime and startup freshness barriers. Cap, cadence and backfill can change inside an already-open store. |
| `serialization.compressed_columns` | Coupled to store-frame reuse, codec initialization and session format negotiation; raw bandwidth can already be reduced live. |
| Server protocol/Via settings and anti-xray group | Change accepted sessions or masking/cache identity; preserve those correctness boundaries. |
| `paper.update_events` | Requires listener re-registration with platform lifecycle ownership; cadence controls are reloadable without adding that lifecycle change. |

The client reconnect classifications remain unchanged, especially Xaero enablement
and cache/address identity. This expansion targets server performance without
undoing the reviewed session-identity constraints.

### 6.2 Client

| Current field | YAML path | Timing / detail |
| --- | --- | --- |
| receiveServerLods | lod.receive | H; existing orderly receive-off retirement |
| lodDistanceChunks | lod.distance_chunks | H; zero server default, never exceeds server cap |
| lodColumnsPerSecondLimit | lod.download.max_columns_per_second | H; zero unlimited, preserve curved UI slider |
| enableAdaptiveTransferRate | lod.download.adaptive_rate | H; reconcile governor without duplicate acquisition |
| enableJoinSlowStart | lod.download.slow_start_on_join | H; governs next new-session ramp, does not restart current ramp |
| enableIngestBackpressure | lod.download.ingest_backpressure | H |
| enableRegionScan | scan.region_order | S; scanner implementation selected at session construction |
| enableAdaptiveScanCadence | scan.adaptive_cadence | S; conservative session policy group |
| enableScanPrefixRetention | scan.retain_completed_prefix | S; no mid-walk policy replacement |
| enableQuadtreeScan | scan.quadtree | S |
| enableRegionSummarySync | scan.region_summaries | S; avoid half-completed summary exchange |
| useWorldSubBuckets | cache.split_by_world | S; never switch active cache identity |
| cacheAddressAliases | cache.address_aliases | S; retain consumer-corroboration and Xaero constraints |
| unknownBlockFallback | compatibility.block_fallbacks.default | S; local registry validation, never air |
| crossVersionBlockFallbacks | compatibility.block_fallbacks.overrides | S |
| enableV16ServerCompat / enableV19ServerCompat | compatibility.protocols.v16 / v19 | S; next discovery ladder |
| enableV16Generation | compatibility.v16_generation | S |
| enableXaeroMapBridge | integrations.xaero_map.enabled | S; keep consumer selection coherent with frozen cache alias identity |
| enableXaeroMapBackpressure | integrations.xaero_map.backpressure | H |
| farPlayersEnabled | far_players.enabled | H; manual toggle only; no mod-presence test |
| farPlayersMaxDistanceBlocks | far_players.distance.max_blocks | H; zero server maximum |
| farPlayersMinDistanceBlocks | far_players.distance.min_blocks | H; zero server inner ring |
| farPlayersNameTags | far_players.name_tags | H |
| farPlayersFullBright | far_players.full_bright | H |
| farPlayersShareSelf | far_players.sharing.enabled | H; explicit privacy preference, independent of local rendering |
| farPlayersShareDistanceBlocks | far_players.sharing.max_distance_blocks | H; zero no additional cap |
| farPlayersMaxRenderDistanceBlocks | far_players.render_distance_blocks | H; zero effective ring |
| farPlayersMaxAnimationDistanceBlocks | far_players.animation_distance_blocks | H; zero no animation |
| Removed automatic-coexistence override | omitted | Generic unknown/retired JSON-field handling only |

Reconnect consumes the **last accepted** pending S snapshot; it must not re-read
new disk edits unless reload was requested. Dimension change, reset and server
session-config re-push are not permission to activate pending cache/decoder policy.
Freeze the session group until the connection lifecycle ends. Reload without a
connection can accept S settings immediately for the next session.
Use the actual underlying connection identity, not the current play packet
listener or world object: a play-to-configuration-to-play transition can replace
those without a disconnect. That new play JOIN must recompute necessary runtime
observations using the same frozen S settings, not adopt pending edits.

Client reconciliation must attempt changed sharing preferences on an existing valid
session **before** retiring acquisition when the same reload disables reception. Otherwise
“hide me and stop downloads” can strand the old sharing opt-in on the server.
No channel/session means privacy delivery cannot be claimed; report the existing
protocol limitation. Renderer-only changes must not force a handshake or roster
reset. Xaero enable/disable waits for reconnect together with cache policy: enabling
it under an already aliased stamp cache would make cache identity coarser than the
map consumer and risk permanent holes. At reconnect, establish consumer capability
and alias policy together through the existing gate; reject stale callbacks/receipts.
Receive-off remains hot and preserves committed native map rebuilds.

Replace the current void/debug-only preference sender with a typed enqueue outcome
and one connection-owned pending-reconciliation marker. A thrown send keeps the
accepted local setting and reports “privacy preference not sent”; bounded client-
owner retries continue while that same valid connection exists, even with reception
off. They must not restart acquisition. Disconnect clears pending work; no retry
may reach another server. A successful enqueue means sent, not server-confirmed
(there is no wire acknowledgement). An unchanged reload remains a settings no-op
without deleting or suppressing already-pending delivery. Test throw-then-success
during combined receive-off/share-off and connection replacement. No protocol change.

## 7. World distance behavior

Fresh `by_dimension` contains the three requested vanilla defaults. Only terrain
distance has per-world/per-dimension overrides in this scope; don't create generic
whole-config inheritance.

Paper lookup order: exact Bukkit world name, actual world/dimension key, then
`default_chunks`. Preserve current `ServerWorldLod` / Paper resolver API adaptation
per support line; never derive policy by suffix matching (`*_nether`) alone.
Fabric/Neo lookup uses the actual dimension resource ID. `by_world` is recognized
but inactive there. Keep custom dimension IDs and Paper world names containing
spaces, colons, non-ASCII text or unusual prefixes.

All distance consumers use this resolver: handshake, dimension transition,
request admission, generation, dirty broadcast and diagnostics. Global storage/
scan/cache sizing continues to use the maximum of the default and every override;
no global bound may undersize a larger custom-world radius. Clearing/removing
an override follows the next fallback, not a resurrected stale value. Current
protocol session repush uses each player's world; legacy clients require rejoin.

## 8. Sodium, draft persistence and localization

Modern Sodium currently closes over `LSSClientConfig.CONFIG` at registration; the
legacy renderer stores the same mutable instance. Simply replacing that static
field is insufficient. Both generations must bind to a stable draft-storage
adapter, never the active snapshot or a once-captured obsolete object.

- Open/reopen reads the selected YAML document into a draft and records a base hash.
  Catalog defaults/ranges stay shared. Stage typed edits locally; Cancel is a no-op.
- One Apply writes all changed paths as one transaction (one storage handler),
  preserving existing inline/block comments, order, untouched values and private
  alias/exclude entries. Preserve Unicode comments; formatting may normalize only
  on an explicit save. No full regeneration from the active snapshot.
- Before writing, compare the disk base hash. If the file changed externally, refuse
  the stale save and retain the draft with a translated reopen/resolve message.
  A bad or newer-version document is
  never replaced by defaults from the menu.
- Use a unique same-directory temp and verified atomic replacement for updates.
  After the complete temp has been validated, recheck the base hash immediately
  before replacement. Serialize all in-process creators/writers through the handle.
  Keep this replace-existing primitive separate from migration's create-only one;
  a missing/replaced target requires re-read instead of accidental recreation.
  A portable hash check plus atomic rename cannot exclude an uncooperative editor
  writing in the last race window; document that limit rather than promise a
  filesystem compare-and-swap. Test edits at every controllable boundary without
  introducing a new cross-process locking protocol.
  A failed save keeps live settings unchanged and pending edits visible; it cannot
  say “applied but unsaved,” because nothing was activated.
- Sodium's real legacy and modern Apply loops mark options clean **before** invoking
  storage save. Therefore own a `ClientSettingsEditSession` with persisted base,
  changed paths and outcome independently of Sodium's dirty flag. Retain a failed
  or conflicted draft across screen transitions. Provide a small translated
  save-error dialog with Retry/Keep draft/Discard; a conflict requires re-reading
  disk and explicitly rebasing the selected edits or discarding them, never a blind
  retry that overwrites the new file. UI adapters can restore dirty indicators
  where supported, but retained drafts and usable retry cannot depend on that.
  Contain storage-handler exceptions instead of throwing through Sodium's Apply loop.
  Pin real baseline-before-save ordering in stubs and validate new open/refresh hooks
  against each supported Sodium API. Prove failure → visible unsaved edits → retry
  succeeds without re-entering values on both generations.
- Reload is the only publisher. If it occurs while a menu is open, refresh clean
  bindings and preserve dirty drafts with conflict reporting. Reopening modern and
  legacy screens must display the saved/configured value and its active/pending
  state, not revert to registration-time values.
- Remove live `SaveHook` prefs/reconciliation variants, preset UI hooks, status
  receive toggle and obsolete ExternalOptionRefresh behavior if unused. Keep status,
  export, pagination and navigation. The menu may display command instructions,
  but it does not execute reload automatically.
- Preserve renderer availability gating on each line/loader. Remove only the
  automatic coexistence visibility/tooltip branch, not legitimate loader capability
  or missing-Xaero conditions.
- Keep locale JSON files (Minecraft's required resource format); the YAML request
  concerns **settings**, not translation assets/manifests/profiles/diagnostic JSON.
- Translate every existing/new catalog key and conditional path in all three locales;
  validate placeholders and rendered units. Add translations for disk-save failures,
  pending reload/reconnect/restart and privacy status. Verify both Sodium generations,
  Fabric and native NeoForge, and both LSS/VSS display branding.
  Distinguish unsaved/conflicted, saved-but-not-reloaded and accepted-but-reconnect-
  pending states; show active sharing separately from draft sharing. Save only changed
  YAML paths, so saving an unrelated option never snaps a hand-authored download rate
  above the curved slider maximum down to the largest UI stop.

## 9. Reference cleanup and tooling conversion

Remove the mod-presence probe, override field, descriptor, translation keys, enum
variants, truth-table tests and release-check branding expectations. Rewrite source
comments and current/historical docs describing the mechanics without references to
the other project. Keep the behavioral explanations, provenance dates and useful
invariants. Add one README inspiration acknowledgement naming the originating project.

Audit all tracked text and filenames, generated docs, fixtures, packaging resources
and submodule/vendor pointers on every support line. No imported source/vendor tree
for that project is tracked in the inspected main snapshot. Do not rewrite Git
history or delete unrelated untracked research. If a legally required third-party
notice is found in retained copied code, preserve it and resolve the scope explicitly;
do not erase required attribution by renaming it. The acceptance scan allows only
the one acknowledgement; use a generic restricted-reference scanner with external
test input rather than embedding the prohibited name in another source/test fixture.

Convert active setup/run tools to the new YAML schema. Keep JSON only for explicit
migration tests or genuine JSON formats (runtime manifests, catalog/profile files,
diagnostic output, language resources, golden data). In particular audit:

- `scripts/soak.sh`, `scripts/soak-scenarios/*-config.json`, scenario command drivers,
  `scripts/backfill_profile.sh`, benchmark staging and `test-server.sh`.
- `tools/rig/prepare_prism.py`, `prepare_xaero_map.py`, `prepare_seated_targets.py`,
  `prepare_concurrent_server.py`, correctness/benchmark preparers and their tests.
- Tier 2 command gametests, soak fixture mods, command-help tests and Python source
  scanners that assume `set`, `preset apply`, JsonConfig or flat field declarations.
- `tools/settings`, settings reference, README/configuration instructions, performance
  docs and current plan links; replace preset tuning advice with commented YAML edits
  followed by reload (restart when applicable).
- Diagnostics distinguish saved/configured, active and pending without exporting
  raw YAML, player identifiers, addresses or local paths. Preserve stable outcome
  metrics; version changed diagnostic schemas deliberately and update consumers.
- Runtime settings identity already recognizes `.yaml`/`.yml`; test that staged/generated
  YAML bytes and meaningful settings changes remain bound to evidence identity.

Add a single maintained settings-file helper for harness edits using the same schema
and migration rules (ideally a common Java CLI), rather than five new Python YAML
rewriters or regex substitutions. Every scenario needing a hot change writes a full
valid YAML then runs reload and waits for its receipt/result. Record actual active
settings, especially where automatic migration would preserve a legacy baseline.
Do not rewrite archived measurement inputs/results and claim their hashes still match.

## 10. Implementation sequence after approval

| Step | Deliverable | Completion gate |
| --- | --- | --- |
| 1 | Pin approved schema/examples and mapping inventory; add setting timing/sensitivity metadata and documented changed policies. | Every legacy current field accounted for; defaults/clamps/unit coverage; both Chinese locales enumerated. |
| 2 | Add relocated YAML codec/document layer, safe IO and typed nested records. | Real parser round-trip with comments/Unicode; malformed/duplicate/alias/large-input failures; artifact dependency checks. |
| 3 | Implement JSON migrator and brand/path selection. | Old file corpus, fresh/upgrade defaults, dual-brand ambiguity, backup/collision/crash/IO tests; JSON no longer a runtime config format. |
| 4 | Add settings handles and reload planning/publication; move all readers to effective/session snapshots. | Atomic correlated reads, pending settings isolation, no stale captured configs; MC-free tests on Java 21. |
| 5 | Implement server command/owner adapters, the performance policies in section 6.1.1, and replace server mutators. | Revision acknowledgements, generation drain/re-enable, backfill rate/stop/resume, store/cache policy changes, current/legacy session effects, disabled service, Paper/Folia ownership and no-op/error reloads. |
| 6 | Implement client reload, lifecycle reconciliation, draft-only Sodium storage and status changes. | Receive/prefs/consumer/epoch tests; both menu generations save without activation and reload once. |
| 7 | Complete reference cleanup, localization, docs and all harness/config fixture conversions. | All catalog/locale/branding checks; no obsolete live controls or disallowed references; helpers exercise YAML natively. |
| 8 | Port the coherent change to all four other support lines in isolated worktrees. | Schema/default/migration parity, line API seams and exact supported loader matrix verified. |
| 9 | Run targeted correctness checks, line full verification and bounded private live scenarios. | Evidence matrix below complete; no unsupported capabilities inferred from a stub/server pass. |

No extra performance campaign, public release, merge, launcher deployment or normal
test-server mutation is implied. This change needs targeted IO/owner responsiveness
checks and functional validation, not another multi-hour idle-PC measurement.

## 11. Support lines and validation

Current authoritative catalogs, not the old checkout banner, define the matrix:

| Line | Java | Fabric client gametests | Paper family | NeoForge shipped / far-player renderer |
| --- | --- | --- | --- | --- |
| 1.21.1 | 21 | unavailable | Paper/Purpur | yes / available |
| 1.21.10 | 21 | available | Paper/Purpur | no / unavailable |
| 1.21.11 | 21 | available | Paper/Purpur/Folia | no / unavailable |
| 26.1 (targets 26.1.2) | 25 | available | Paper/Purpur/Folia | yes / unavailable |
| 26.2 | 25 | available | Paper/Purpur/Folia | yes / unavailable |

Common emits/runs Java 21 everywhere. Preserve line-specific identifier, command,
dimension-key accessor, render/input, permissions and Sodium API adaptations.
The 1.21.1 modern and legacy Sodium pair is a priority because both ship supported
options; do not request nonexistent 1.21.1 Tier 3 or Folia tasks. Do not resurrect
retired 1.20.1/1.21.8 branches merely because they remain on GitHub.

Required tests:

1. Codec/default/migration corpus: every key, absent versus zero/empty, mixed legacy
   bandwidth keys, store mode history, old global distances, Paper ambiguous names,
   generation/broadcast seconds-to-ticks conversion (missing/null/zero/clamped values),
   corrupt source preservation, unknown JSON keys, duplicate YAML/JSON keys, source
   and target IO failures, same-directory atomic replacement and repeated startup.
2. Reload transactions: malformed candidate no-op, bounded parse failure, changed
   file/lifecycle conflict, overlapping requests, atomic generation/ring/distance
   groups, restart-only values never leak, pending values cleared by reverting disk,
   disabled-service command handling, unchanged reload has no effects; cross-owner
   adoption acknowledges the correct revision and cannot hang during shutdown.
3. Real side effects: bandwidth/governor/disk gate and in-flight work; distance raise,
   shrink, override deletion and per-world config re-push; permission removal/grant;
   dirty-push zero retains drain; far-player privacy tightening clears stale rosters.
   Include section 6.1.1's generation/backfill/store/cache/serialization transition
   tests. Exercise both directions during real in-flight work, not just config-field
   reads; prove bounded owner work and accurate draining/pending/inactive reports.
4. Client lifecycle: disk/menu edits remain inert; explicit reload receive off/on;
   combined reception-off/share-off; late decode/summary/Xaero callbacks; committed
   native map rebuild ownership; renderer-only reload no session rebuild; S changes
   remain frozen across world switches/re-push/reset until reconnect.
5. Menus: modern registration captured-binding regression, both legacy/modern draft
   apply/cancel/reopen, external-file conflict, save failure, pending privacy text,
   locale coverage/placeholder tests, hidden unsupported renderer rows, LSS/VSS jars.
6. Cleanup/tooling: current command trees and help expose reload and no set/preset
   mutation; native YAML rigs/soaks; migration-only JSON allowlist; reference and
   schema generation checks; restricted-reference audit excluding Git history.
7. Per-line builds/tests: `tools/verify/verify.py full --run`, explicit client gametests
   where registered, Paper tests, NeoForge builds/tests/smoke as line capabilities
   allow, `vssJars`, release/artifact checks and compatibility catalog render/validate.
   Preserve meaningful existing tests rather than replacing them with source-string
   assertions. Run heavy tasks sequentially; retain failure evidence and owned PIDs.
8. Bounded real rigs: main Fabric/Paper and Folia reload while clients span regions;
   1.21.1 Fabric + native NeoForge with modern/legacy menus and Chinese language;
   representative reload boot/migration smokes on remaining lines. Verify real
   server/client no-op/error/restart-pending reports, generation off/on without
   stranded tickets, and backfill throttle/stop/resume while normal serving continues.
   Use private authenticated WSL
   displays, fresh instances/worlds and Windows `[::1]:port` only if user joins.
   Never operate the Windows foreground. Cleanup only task-owned processes.

Completion means all approved files/schema/commands are shipped consistently in
local candidates, all alternate settings publishers removed, migration proven,
locale coverage complete, line capability gates green and review findings resolved.
The user approves this proposal before step 1 begins.

## 12. Plan review record

Two independent **gpt-6-astra** agents reviewed the first draft against the source
and relevant tests/dependency surfaces. Both reviews are complete:

- [Schema/migration/persistence review](2026-09-28-yaml-settings-review-schema.md)
- [Runtime/UI/support-line review](2026-09-28-yaml-settings-review-runtime.md)

| Finding | Disposition in this revision |
| --- | --- |
| S1: legacy world identifiers could gain a new meaning | Section 4 now preserves exact fully qualified spelling, never prefixes namespaces, and keeps inert legacy names inert. |
| S2: strict YAML could reject historically accepted JSON values | Section 4.1 explicitly distinguishes missing/null/empty and legacy enum/primitive normalization; old fixtures join the migration corpus. |
| S3: tree traversal checks might be too late | Section 3.2 requires byte/depth/node budgets before either JSON or YAML tree materialization, with boundary tests. |
| S4: atomic move is not a no-clobber or filesystem-CAS guarantee | Sections 4/8 separate create-only and replace-existing IO, verify source/base hashes immediately before publication, and state external-editor race limits. |
| S5 / R4: inactive groups in fresh examples | Mod-loader example omits Paper world names; Paper example omits backfill. Copied files still retain recognized inactive paths. |
| R1: hot Xaero switch conflicts with frozen alias identity | Xaero enable/disable is reconnect-only alongside cache identity; backpressure remains reloadable. |
| R2: Sodium cleans options before save can fail | Section 8 owns the draft/outcome independently, with retained edits and explicit retry/conflict/discard UI. |
| R3: privacy send helper hides enqueue failures | Section 6.2 requires typed send outcome, connection-scoped retry even with reception off, and no false remote-confirmation claim. |
| Additional runtime notes | Shutdown cancellation, physical-connection identity across play/configuration transitions, untouched high slider values and all locale outcome states are explicit. |
| Bandwidth comment clarity | Both server examples say zero clamps to the minimum, not unlimited. |

Plan-only checks: all 45 shared server, 30 client and one Paper legacy field are
accounted for (including the three intentionally retired fields); the two common
config definitions are identical across the five fetched active support refs.
All three example files parse with duplicate-key rejection; vanilla dimensions,
store/resweep defaults, anti-xray entries and Paper event list were checked against
current source. These are document/schema checks, **not** implementation test passes.
No runtime files, production code or launcher instances were modified.

User follow-up on 2026-09-28: expanded feature descriptions and resource tradeoffs
in all three examples, especially the LOD store, and made their generation and
preservation an explicit requirement in section 3. YAML parsing confirmed that
this comment-only expansion did not alter any setting value.

Second user follow-up on 2026-09-28: a source-level performance reload pass promoted
thirteen server fields from restart-only to reloadable in section 6.1, with owner,
in-flight, failure and test contracts in section 6.1.1. Section 6.1.2 explains the
remaining restart boundaries. Both server examples now show that timing; generation
timeout and dirty-broadcast cadence use ticks to match their existing clocks, with
explicit legacy conversions. This follow-up is the primary agent's source review;
the two linked Astra reports predate this expansion and are not represented as
having reviewed these added contracts. Product implementation remains unstarted.

The implementation remains held for the user's approval of these examples and
of the save-to-disk / explicit-reload behavior.
