# Structured YAML settings implementation

User approval: 2026-09-28. Scope: the [reviewed implementation plan](../planning/2026-09-28-yaml-settings-implementation-plan.md), all five maintained lines, followed by an independent Astra implementation review. No release, push, merge, personal launcher deployment or normal test-server change is included.

**Complete — 2026-09-28.** The implementation, three independent Astra reviews,
validated finding fixes, all five full build gates and the required private native
checks have passed. All 30 final LSS/VSS jars match the frozen candidates used for
validation. Owned test processes are stopped and the shared harness lock is free.
The final matrix and evidence links are at the end of this ledger. Earlier sections
retain the chronology, including failed and interrupted attempts; their pending
language describes those checkpoints, not the current state.

## Work and evidence

| Area | Current state |
| --- | --- |
| Schema, annotated YAML, typed records | Implemented across all five support lines. Shared schema drives defaults, validation, references and the Java settings-file helper. |
| Legacy migration and persistence | Implemented. Retains original JSON and exact backup; preserves old distance/store defaults and tick-based timing. Strict YAML, comment-preserving edits, create-only startup and conflict-aware replacement covered by tests. |
| Reload transactions | Implemented with bounded IO, owner publication, immutable effective/configured/boot/session state, pending boundaries and tracked subsystem receipts. |
| Server performance controls | Generation, backfill, store/cache, serialization and queue policies implemented on mod and Paper services. Owner-adoption tests and final native Fabric/Paper/Folia reload scenarios passed. |
| Client and menus | Explicit reload, physical-connection settings freeze, draft editing and automatic reload on Sodium Apply, retained save/conflict handling, privacy-send retries, modern/legacy adapters and three locales implemented. All four native menu configurations and both loader lifecycle scenarios passed. |
| Commands and cleanup | Old set/preset/status-toggle publishers removed. Source references to automatic coexistence suppression removed; the README retains the inspiration acknowledgement. |
| Tooling and documentation | Native YAML fixtures and operational guides converted. Rig identity/UI review followups passed. Historic measurement results are not rewritten. |
| Support-line ports | All production, migration/retry and tooling changes are ported to five isolated branches. Final compilation, JUnit, supported game tests and all six artifact checks pass per line. Required native gates passed. |
| Independent Astra implementation reviews | Three independent Astra reviews completed: schema, runtime and client/ports. Validated findings are fixed and regression tested; followup source, fixture and compatibility reviews report no remaining blocker. Required live acceptance passed separately. |

Initial validation on 26.2:

- Shared parser/migration/store/handle/CLI focused standalone tests passed; the suite grew as integrated tests found additional cases. Actual C: DrvFS testing passed 30 store/handle/CLI cases in a task-owned temporary directory, which was removed. This is WSL Java accessing DrvFS, not native Windows JVM certification.
- The first full common Gradle suite passed **870 tests, zero failures/errors/skips**. A packaged-boundary test first exposed an engine-type return signature altered by shading; the parser type no longer escapes that boundary.
- Production Fabric, Paper and NeoForge compilation passed. Fabric and NeoForge test sources compiled. Subsequent test and game-test fixture migrations are under validation.
- First full Fabric run: 1,653 tests, eight failures, four skips. Findings included two historical primitive-to-string migration cases and stale source-contract patterns. The migration cases were fixed rather than weakening the old accepted-input semantics.
- First Paper run found an adoption-test fixture accidentally booted with generation enabled and a source-order assertion matching a newly added reconciliation method. Both were corrected while retaining the behavior/ownership assertions.
- Client draft tests found an emitter failure expanding an inline-commented empty collection. Added a regression for expansion and returning to an empty collection; the client draft/privacy standalone suite passes eight tests.
- Common shaded JAR inspection passed for the relocated parser and license. Platform/brand artifact checks are implemented but still require final JARs.

Local detailed logs are under `/tmp/lss-yaml-validation/` and `/tmp/lss-yaml-src/`.
Final per-line results, live attempts and review dispositions are recorded below.
Compilation and simulated rig receipts do not establish live UI, Folia multi-region
or performance acceptance; the required native checks have their own evidence.

## Independent implementation review

The three reports reviewed the initial core implementation and ongoing support ports:

- [Schema, migration and persistence](2026-09-28-yaml-settings-astra-schema-review.md)
- [Runtime ownership and reconciliation](2026-09-28-yaml-settings-astra-runtime-review.md)
- [Client menus, diagnostics and ports](2026-09-28-yaml-settings-astra-client-ports-review.md)

| Finding | Fix and evidence |
| --- | --- |
| S1, dotted schema keys can shadow nested values | Reject dotted schema segments while permitting dots inside dynamic map keys. Parse/edit regression added. |
| S2, failed reconciliation lost on identical reload | Retain outstanding adoption paths/target and last acknowledged revision. Same-file retry, changed candidate/revert, stale acknowledgement and late acknowledgement regressions pass. |
| S3, legacy string-list primitive coercion | Migration preserves Gson's string primitive behavior, including numeric lexemes; structural values still reject. |
| S4, VSS templates contain LSS commands | Runtime brand-aware generated comments; fresh and cross-brand migration regressions. Existing YAML is not rewritten. |
| S5, discarded startup diagnostics and count-only normalization | Server reports source/backup, inactive paths and sanitized requested/effective values; private values remain redacted. |
| S smaller findings | Reject fractional integer-map tokens; migrate all three explicit vanilla radii from the old global value. |
| R1, failed backfill enable consumes successor intent | Retain retry intent and acknowledge only actual successor start; focused real-store regressions pass. |
| R2, partial owner adoption confused with publication | Compare actual store policy and separately adopted session/service/roster state. Focused Paper retry/revert regressions pass. |
| C1, missing native settings helper identity | Canonical tool snapshot includes the helper and source closure; manifests also hash actual class/resource/JAR bytes of the frozen Java codec. Focused identity and negative controls pass. |
| C2, notices overlap Sodium controls | Reserved wrapped notice area with dedicated status/recovery action. Legacy layout changes apply only to LSS-owned page identities; unrelated fixed-row tabs retain their viewport. Native inspection passed all four loader/menu configurations, including the corrected Fabric modern feedback repeat after the user lifted the hold. |
| C3, Escape closes two screens | Recovery dialog consumes the complete Escape pair using each Minecraft line's input API. |
| C4/C5, untranslated status and incomplete diagnostic state | Localized status/reload presentation and schema 2 export with saved/configured/effective scalars, pending paths and adopted/published revision; privacy values outside the allowlist stay private. |

Additional validation: 122 focused codec/migration/persistence/diagnostic cases passed
against **both Gson 2.10.1 and 2.13.2**. The 1.21.1 build exposed the newer Strictness
API dependency; the migrator now uses the older bounded reader surface with strict
lexical validation. The new reload/handle suite passed 20 tests. Runtime retry suites
passed 6 common store and 63 Paper service tests. These are targeted results, not
substitutes for the final full builds and native scenarios.


Client startup-failure recovery decision (2026-09-28 implementation review): an
invalid, unreadable or failed-to-migrate client settings file leaves settings
inactive for that process. Repairing the file requires a client restart, as allowed
by the approved plan section 4; `/lss reload` reports that requirement without
reading, creating, migrating or publishing a replacement handle. This removes the
separate recovery publisher and its read-then-initialize race. Normal reloads
after a valid startup retain their hot/session behavior and tracked adoption retry.
Client diagnostic schema 2 distinguishes observed saved, accepted, effective and
pending subsystem adoption, including published/adopted revisions without raw
errors or private values. A failed/pending reload displays its localized outcome
without a subsequent success notice.


Full main verification before the final client feedback/layout and numeric-writer
followups passed in 4m48s: common 889/0, Fabric 1,653/0 (four expected skips), Paper
536/0, NeoForge 23/0; all 79 Fabric server game tests, eight NeoForge server smoke
tests, and client game tests passed. The exact final candidate is being revalidated.

The broad rig suite found one real numeric transport defect, not an environment
failure: the legacy-compatible JSON number wrapper could cause the YAML writer to
emit an explicit type tag. CLI numbers now pass through exact numeric conversion,
and the writer emits schema-validated requested types without substituting clamped
values. New full-schema create/read/edit roundtrips and decimal/scientific/zero cases
pass; the previously failing conservative-native controls pass 11/11. External
explicit YAML tags remain rejected.

## Live-check hold requested by the user

On 2026-09-28 at approximately 15:52 EDT the user requested a pause before live
checks while using this computer for games. All game/server runs, including Gradle
game tests, are deferred until an explicit go-ahead. Source review, compilation,
JUnit, static checks and artifact inspection may continue. No completion of the
remaining live gates is inferred from this hold.

Four private Minecraft 1.21.1 menu configurations passed their seven core checks:
Fabric modern and legacy in Simplified Chinese, and NeoForge modern and legacy in
Traditional Chinese. The original Fabric modern attempt exposed English export,
recovery and reload-error feedback; those were fixed and inspected in the other
three configurations. The corrected Fabric modern repeat had just started when the
hold arrived and was stopped immediately; it has no acceptance result. Exact
run identities, hashes and supplementary limits are in the
[client review](2026-09-28-yaml-settings-astra-client-ports-review.md).

Owned-process cleanup completed for all five attempts. The private
`PAUSE-CLEANUP.json` records 14 dead process identities per attempt, terminal cleanup
journals, zero remaining children and no live process carrying those run markers.
No Windows foreground automation was used.

A final diagnostics review found that server exports still described generation as
configured for restart and read the desired flag instead of the actual owner's
admission gate. The fix reads that gate on its owner, includes the whole-service
enabled condition, and preserves the schema-1 JSON field name for compatibility.
Human-readable output and operator documentation now describe explicit reload.
Focused common/Paper tests and all nine server-control checker tests passed.

The user lifted this hold shortly afterward (2026-09-28, approximately 16:07 EDT), explicitly authorizing live tests while the box is free. The interrupted attempt remains interrupted; validation resumes with new owned attempts and current artifact bindings.

## Final non-live verification

The final per-line compilation, complete JUnit suites, game-test source compilation,
LSS/VSS jar builds, release artifact checks and fixture-exclusion checks passed.
All rows have zero failures/errors. Fabric skip counts are shown explicitly.

| MC line | Common | Fabric | Paper | NeoForge | Artifact checks |
| --- | ---: | ---: | ---: | ---: | --- |
| 26.2 | 897 | 1656 (4 skipped) | 537 | 23 | All six passed |
| 1.21.1 | 897 | 1649 (4 skipped) | 535 | 23 | All six passed |
| 1.21.10 | 896 | 1645 (6 skipped) | 535 | 19 | All six passed |
| 1.21.11 | 896 | 1650 (4 skipped) | 535 | 23 | All six passed |
| 26.1 | 896 | 1657 (4 skipped) | 535 | 23 | All six passed |

These are 15,527 reported test executions across the five lines, including 22 skips;
shared tests repeat per line. `/tmp/lss-yaml-validation/all-lines-nonlive-results.json`
retains source commits, counts and all 30 exact artifact hashes.

The main 26.2 full gate subsequently passed: 79 Fabric server game tests, eight
NeoForge smoke tests and the Fabric client game test. The final log/results are
`main-final-complete.log` and `main-final-results.json` in the same evidence directory.
The remaining four final live/game-test gates are not inferred from this result.

The broad rig unit suite passed 648 tests with no failures or skips. Both WI5/WI6
fixtures built as Fabric-remapped and NeoForge-named jars against the final 1.21.1
candidate; WI5 manifests and classes contain the new SettingsReloadMixin. This is
compilation/packaging evidence; actual lifecycle mixin application awaits its native
run. Final Fabric/Paper settings observers also compiled and bind exact target class
and jar hashes. All five schema inventories and settings references match their
built Java schema. The restricted-reference scan finds only the README credit.

Final recipe preparation found that the new YAML reload checker was omitted from
the compatibility fingerprint registry. It is now registered with its driver; 14
closure tests verify both direct and transitive changes remain bound. The fix is
ported, independently reviewed, and exact-ref/default compatibility checks pass on
all five lines. No product jar changed for this harness registration correction.

The corrected Fabric-modern Simplified Chinese repeat subsequently passed all seven
core checks on final candidate `d0fd05cc272491acc82f134fa17f3a67d8c24c7cefe15ae7fae28424f5562ec5`
(run `20260928T201624Z-1358685bed4e`). Corrected export, permission/save failure, external
conflict and malformed-reload messages were inspected in native screenshots. A final
export confirms restored saved/configured/effective values, protocol 20, revision 4/4
and no pending paths. Collection completed and all 14 owned process identities were
dead, with no remaining children or run markers. This closes the interrupted UI
followup. Exact receipts and screenshot bindings are in the client review.

Recursive comparison of the earlier corrected Fabric/NeoForge jars with the final
candidates found changes only in five server/configuration diagnostic class entries;
all client classes and translations are byte-identical. The three earlier corrected
UI passes therefore retain this explicit source/artifact lineage; their hashes are
not represented as the final jar hashes.

The first main Fabric reload attempt (`20260928T202442Z-6a436646d00e`) stopped
before Minecraft readiness: Fabric's classpath verifier found duplicate ASM
`ClassReader` entries in the explicit server library and the external observer agent.
The product was not exercised in this attempt. Its failure evidence was retained;
the owner exited and cleanup reported zero remaining children. The fixture packaging
was corrected in `d96be18d` and ported: Fabric uses its explicit startup ASM, while
Paper/Folia retain bundled ASM for Paperclip premain. Actual Java 21 premain checks
passed for both shapes with one ClassReader provider, successful ASM parsing and
clean observer-writer shutdown. Paper/Folia bootstrap jars were checked for absent
ASM. No product jar changed. Fresh native acceptance is still required.

Before retry, the maintained recipe generator was corrected to explicitly set
`gamemode=creative` and `force-gamemode=true` once each. The removed historical
workload plugin had supplied that setup, and the reload driver's high teleports
would otherwise kill survival-mode subjects. Existing client identity, the two
subjects' placement, independent consumer registration and retained Folia region
observation were audited; no other historical workload setup was required. All
three regenerated property files and ready plans were checked. This fixture-only
change (`45dc975b`) is ported and independently reviewed; exact/default compatibility
checks pass for all five lines.

The second Fabric attempt (`20260928T203304Z-bee91e1ee92a`) reached service
initialization and the first tick, then Fabric's game classloader refused access to
the external observer recorder. This was a fixture-loading failure before reload
acceptance. Both failed attempts retain their original evidence; the supervisor
cleanup receipt and collection confirm zero remaining children (the immutable
attempt manifest may still say `supervisor-pending`).

A real cached Fabric Knot probe reproduced the denial and verified the narrow
correction: add only the two owned observer jars to `fabric.systemLibraries`,
preserving existing entries. Knot then resolves the identical bootstrap recorder,
and the injected policy callback writes to the same run-bound evidence sink. The
positive probe closed without overflow; Paper/Folia configuration is unchanged.
This preparer correction (`153daf44`) is ported to all five lines. Product jars are
unchanged; the new native attempt must still establish the behavioral assertions.

Fabric attempts `20260928T204413Z-a90d9a106f54` and
`20260928T204843Z-93b27903aa0c` both reached real owner observations and two protocol-20
client handshakes. Neither satisfies the required in-flight-disable premise: the
flat-world jobs completed before the disable policy reached the owner. The first
attempt exposed a stale observation/setup gap; the reviewed driver correction
(`a173d929`) stages the file before placement and requires a fresh positive sample
before issuing reload. In the second attempt, even the remaining approximately
159 ms asynchronous command/adoption interval outlasted that job. The strict
active-at-disable and drain checks remain unchanged. Both attempts were collected
with complete cleanup and zero children; neither is represented as native reload
acceptance. Investigation of a longer real cold-generation workload continues while
the independent support-line migration smokes run.

## Support-line native migration and reload smokes

The final Paper candidates for 1.21.10, 1.21.11 and 26.1 each passed their first
three-boot smoke: nine boots and 51 command receipts in total. Actual startup
migration preserved the JSON source and exact backup, explicitly retained the old
23-chunk distance in all three vanilla dimensions, and converted 17/9 seconds to
340/180 ticks. Each line established save-only inactivity, hot generation reload,
no-op, malformed-file rejection without owner mutation, repaired retry,
restart-pending retention and reversion. A disabled-service boot kept the service
available for diagnostics but disabled actual admission; accepting enable remained
restart-pending until the next boot, which admitted generation.

The private `boot-smokes/summary.json` and each `*-final-01/result.json` under
`/home/vox/.local/state/lss-yaml-native-20260928/` bind candidate/codec/dependency
hashes, commands, migration bytes and cleanup. All nine recorded processes exited,
every result reports zero owned processes alive, and the shared lock was released.
These passes do not substitute for the remaining per-line game-test suites.

## Main native reload validation

Fabric run `20260928T205549Z-1f31c617e04d` passed all native groups and collected
with no errors, complete cleanup and zero children. Its private recipe changed only
the terrain generator from flat to normal (removing flat-only generator settings),
with a hash-bound provenance record. Product jars, observer, driver, checker and
workload limits were unchanged. Real cold generation supplied the required window:
disable revision 1 observed two active jobs and two native generation tickets;
submitted remained four through drain and quiet, completed reached four, and both
tickets were removed without timeouts. Re-enable completed new generation and
normal serving continued.

The same live backfill worker adopted 10 then 40 columns/second with checked actual
deposit counts. Disable stopped it, the quiet interval remained quiescent, and
re-enable created a new worker with real progress. Reader-thread edits remained
restart-pending through another hot reload and cleared when reverted. The native
invalid/retry and no-op controls also passed. The observer closed without overflow.

The separate receive-lifecycle attempt `20260928T205345Z-d0be9b38cba1` reached its
real queued/committed Xaero premises, but failed a test-observer assertion before
asynchronous reload publication. Its mixin observed a method also called by every
end-of-tick callback, so it incorrectly treated that intervening tick as completed
reload. The stack and independent review identify a fixture defect; no lifecycle
acceptance is claimed for this attempt. The proposed correction observes the
private reload-only reconciliation boundary and retains the strict native-debt
checks. Failure evidence is preserved and owned cleanup completed with zero children.

The reviewed WI5 correction (`585a1d71`, ported to all lines) now targets the private
instance `LSSClientConfig.reconcile()V`. Fresh Fabric-remapped and NeoForge-named
fixtures built successfully; actual classfile inspection verifies both nonstatic
callbacks, exact descriptors, `require=1` and both mixin manifests. The strict
positive-debt assertion stays at the actual adoption boundary. Fresh native recipes
for both loaders are prepared; compilation is not substituted for their live result.

The first Paper reload attempt (`20260928T205853Z-5bcb147df7a4`) reached server
readiness but its observer rejected classes rewritten by Bukkit before definition.
It was collected with complete cleanup and zero children, without reload acceptance.
The corrected observer (`88028b3a`, ported) checks the source class in the exact plugin
code-source jar, obtains that loader's real plugin description, reproduces the actual
`UnsafeValues.processClass` transformation, and requires byte-for-byte equality with
incoming class bytes. Source and transformed digests are recorded separately. Real
target-class parsing and altered-source/altered-transformed negative controls passed;
no arbitrary class digest is allowed. The same change persists the proven normal
terrain premise in the maintained preparer.

The final 1.21.1 full gate passed: 78 Fabric server game tests, eight NeoForge smoke
tests, all unit suites, and all six LSS/VSS release/artifact checks. Its six jars are
byte-identical to the frozen non-live candidates. Evidence is
`1211-final-complete.log` and `1211-final-results.json` in `/tmp/lss-yaml-validation/`.
The final 1.21.10, 1.21.11 and 26.1 game-test gates remain pending at this checkpoint.

Paper's corrected native reload run `20260928T210854Z-f7bba9dfbd7d` passed all three
groups and collected with no errors, complete cleanup and zero children. At disable,
one generation job remained active (three submitted, two completed); drain reached
three completed without further admission, and re-enable produced new progress.
Restart-pending retention/reversion and invalid/retry/no-op controls passed. Both
target classes passed the source and actual Bukkit-transformation equality checks.

Both final receive-lifecycle runs passed all five semantic assertions:

| Loader | Accepted run |
| --- | --- |
| Fabric modern | `20260928T211313Z-88c90131dee4` |
| Native NeoForge legacy | `20260928T211428Z-2107adb8269e` |

The updated fixture observed the real reload-only boundary. Disk/menu saves remained
inert; OFF retired acquisition while preserving committed native rebuilds, which
then drained; ON began fresh negotiation and received new bodies on the same native
world/connection. An actual transport close and replacement-server join retired the
old native world and rejected the held old callback. Each checker reported five
passes, no failures, and complete cleanup with zero children. These runs close the
WI5/WI6 native mixin and lifecycle gate on both loaders.

Folia run `20260928T211608Z-dfd94d889df1` passed all four groups, including actual
distinct owning-region overlap during the disable/drain/serving interval. Two jobs
were active at disable (four submitted, two completed), drain completed all four
without new admission, and re-enable progressed. Exactly two clients joined. The
first Folia attempt had rejected both during login: its two-player capacity was
already reserved when Folia checked those connections again during configuration.
The successful private recipe allowed four slots while retaining exactly two
launched/checked clients; that same Folia-only headroom is now in the maintained
preparer. No product admission logic or native assertions changed.

All three main native scenarios were independently rechecked, including 205 real
owning-region samples in Folia's reload window and a positive overlap. The
[native validation report](2026-09-28-yaml-settings-native-validation.md) binds exact
artifacts, observer lineage, accepted phases, all failed attempts and cleanup.
Its final audit found no live process among 61 recorded identities across nine
attempts. Folia's public experimental designation remains unchanged.

## Final game-test diagnosis and correction

The first final 1.21.10 and 1.21.11 full gates each failed the existing
`RegionFaultGameTests` containment test (79 of 80 tests passed). Both real reads hit
the ten-second background-IO deadline, including the valid comparison chunk; this
is distinct from the documented corrupt-result-label tolerance. Preserved region
bytes confirm that the comparison chunk inflates as valid full chunk NBT and the
deliberately corrupt chunk fails decompression. The test shared a default batch with
generation-heavy tests. Failure logs, reports, source/configuration and region files
are preserved under `/tmp/lss-yaml-validation/1211{0,1}-full-attempt1-failure/`.

A supported, filtered run of the unchanged containment test passed alone in 1.260 s
on 1.21.10. A separate fresh-world control passed in 5.006 s with byte-identical
compiled test and settings. These controls establish a working containment path
without the concurrent unrelated batch, while not uniquely identifying a scheduler
event. The test now has its own environment on modern lines and its own supported
batch on 1.21.1. Method bodies, success/error assertions, discovery and the product
timeout remain unchanged. An independent Astra review found no blocking issue.
The [diagnosis report](2026-09-28-yaml-settings-region-fault-validation.md) preserves
both failures, the controls, exact source commits and the scope of the conclusion.

All five complete Fabric server suites subsequently passed with the containment
test visibly executing in its own one-test environment/batch and the original total
test counts retained. Main and 1.21.1 reran their full server suites after this
test-only change; their earlier full gates already covered unchanged production,
NeoForge and supported client artifacts. The other three lines ran the complete
full gate after the correction. Neither original failed attempt is relabeled.

## Final acceptance and cleanup

| MC line | JUnit reported / skipped | Fabric server tests | NeoForge server tests | Fabric client game test | LSS/VSS artifact checks |
| --- | ---: | ---: | ---: | --- | --- |
| 26.2 | 3,113 / 4 | 79 passed | 8 passed | Passed | Six passed |
| 1.21.1 | 3,104 / 4 | 78 passed | 8 passed | Unavailable by line capability | Six passed |
| 1.21.10 | 3,095 / 6 | 80 passed | 8 passed | Passed | Six passed |
| 1.21.11 | 3,104 / 4 | 80 passed | 8 passed | Passed | Six passed |
| 26.1 | 3,111 / 4 | 79 passed | 8 passed | Passed | Six passed |

Every row has zero JUnit failures/errors: 15,527 reported executions, including 22
skips, with shared tests repeated per line. The final server totals are 396 Fabric
and 40 NeoForge tests, plus the four supported Fabric client game-test tasks. The
separate broad rig suite passed 648 tests with no skips. All 30 artifact SHA-256
values match both the earlier frozen candidates and the files currently on disk.
This is validation of local candidates, not a publication receipt.

`/tmp/lss-yaml-validation/all-lines-final-results.json` aggregates exact commits,
counts, logs and hashes. Each `*-final-results.json` and `*-final-complete.log`
retains the complete per-line gate. The final two affected server-suite followups
are `main-t2-isolated-batch-final.log` and `1211-t2-isolated-batch-final.log`.

The required native matrix is complete:

- Main Fabric, Paper and Folia passed real in-flight generation disable/drain/
  re-enable and continued serving, no-op/error/retry and restart-pending checks.
  Fabric additionally proved actual native ticket removal and live backfill
  throttle/stop/resume. Folia supplied actual separate owning-region overlap.
- All four 1.21.1 loader/menu combinations passed native Chinese UI checks; final
  modern Fabric repeated the corrected localized feedback. Fabric and native
  NeoForge also passed the receive OFF/ON and transport-replacement lifecycle.
- The remaining three lines passed nine Paper boots with 51 command receipts,
  covering exact legacy migration, hot reload, errors, pending restart and actual
  enable after restart.

Detailed limits, hashes and accepted/failed run identities remain in the
[native server report](2026-09-28-yaml-settings-native-validation.md),
[client review](2026-09-28-yaml-settings-astra-client-ports-review.md) and migration
section above. No additional performance campaign or native Windows JVM
certification is claimed. Folia's experimental designation remains unchanged.

Final cleanup found no Java game/build process, private display or launcher from
this task; only an unrelated pre-existing Gradle daemon remained and was left
alone. All native supervisor cleanup receipts are complete, migration boot PIDs
have exited, and the shared harness lock can be acquired. The final disk check
reported approximately 398 GiB available in WSL and 100 GiB on C:, above the 50 GiB
host reserve. The private `final-task-cleanup-check.json` records the check without
authentication data or raw process arguments.

## 2026-09-28 user-requested Sodium menu cleanup

The user requested removal of the persistent Apply/reload/privacy footer and the
LOD status button. This supersedes the earlier footer/status-entry acceptance
requirements. All five maintained YAML branches now leave Sodium's layout intact:
no LSS banner, extra button, shaded strip or reserved footer height. Both loader
mixins retain only the invisible draft-refresh lifecycle hook. Ordinary options,
explicit reload, automatic failed-save recovery and standalone `/lss status`
remain available. English, Simplified Chinese and Traditional Chinese no longer
ship the unused footer strings.

Focused diagnostics/config/menu tests and NeoForge contracts reported 661 cases,
with zero failures/errors and two expected modern-Sodium skips on the legacy-only
1.21.10 line (659 passed). All five lines compiled and assembled both client
loaders plus Paper and both brands. Release/artifact checks passed for all 30 jars;
recursive inspection confirmed the deleted UI classes and strings are absent.
Existing draft tests continue to cover saved-only Apply and retained edits. The
new draft hook is identical across all ten loader/line combinations; the locale
and manifest diff review preserved existing version-specific content.

Evidence: `/tmp/lss-sodium-menu-cleanup-20260928/results.json`, per-line build logs
and `source-review.json`. These are focused non-live checks; this change does not
claim a new native UI run. The user will inspect the updated Windows Prism client.

## 2026-09-28 Sodium Apply activates client settings

Following the user's Xaero test, Sodium Apply now saves the draft and automatically
requests the same validated reload used by the client command. Successful Retry
and Rebase also reload. Staging, failed saves and conflicts never publish settings.
Hot values activate after reload completes; Xaero enabling and other session values
still wait for a new physical connection. Menu requests arriving during a command
or menu reload coalesce into one follow-up that reads the latest saved file.
Existing failure/reconnect feedback goes to chat/logs without adding Sodium widgets.
Direct YAML editing continues to require the reload command. The plan, reference,
generated comments and English/Simplified Chinese/Traditional Chinese tooltips now
reflect this explicitly authorized exception to the original save-only menu policy.

Focused common settings/diagnostics, client config/menu and NeoForge contract suites
reported 1,361 cases: 1,359 passed, two expected modern-Sodium skips on 1.21.10,
zero failures/errors. New disk-backed tests cover automatic hot activation,
Apply/reconnect Xaero adoption, title-screen Apply, failed-save retry, conflict
rebase and queued rapid Apply. Both real-menu adapters' recording tests verify
failed saves stay inactive and successful recovery requests exactly one reload.
All five lines built all platform/brand jars; release and artifact checks passed
for all 30 artifacts. Generated settings inventory/reference checks passed.

The initial 26.2 compile identified its moved chat API; feedback now reuses the
existing per-line status-screen chat adapter. The 26.1 helper-location assertion
was updated to check both delegation and the original exact native descriptor;
its first failed report is retained, and the corrected run passes. Neither was
classified as a flake or waived. Evidence is in `/tmp/lss-sodium-apply-20260928/`,
including per-line logs, `results.json` and `source-review.json`. No new live game
run or native visual assessment is claimed by these focused checks.
