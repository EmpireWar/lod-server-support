# Independent Astra client / support-port implementation review

Reviewed 2026-09-28: main `/home/vox/projects/lss-settings-plan` at `83876c4c` plus the working tree; support ports under `/home/vox/projects/lss-yaml-lines/{1.21.1,1.21.10,1.21.11,26.1}`. No repository edits or heavy builds were run. This is source, real dependency bytecode, and lightweight tool-identity validation, not live menu acceptance.

## Findings

### C1 — P2: Real native YAML settings runs cannot satisfy the new tool-identity verifier

**Paths:** `tools/rig/toolchain.py:10`; `tools/rig/rig.py:238`; `test-fixtures/client-preset-tools/checks.py:45`; `test-fixtures/server-preset-tools/verify.py:24`.

The actual run manifest gets `runtime_tools` from `toolchain.snapshot(REPO)`, which includes only `tools/rig` and `tools/compat`. Both updated settings fixture verifiers require `tools/settings/settings_file.py` in that map. Consequently a correctly prepared real run fails with `required shared settings helper unbound` (client), or `shared settings helper differs from frozen runtime` (server), before its YAML acceptance can pass. The synthetic portability fixtures manually put this entry into their maps, so their passing result does not cover production manifest creation.

**Executed reproduction:** calling `tools.rig.toolchain.snapshot(Path.cwd())` in the main worktree printed:

```
tools/rig/rig_settings.py True
tools/settings/settings_file.py False
settings CLI / schema inputs: []
```

**Fix:** add the maintained settings helper to the canonical snapshot/retain/verify closure and exercise that real closure from the fixture identity tests. Also bind the actual Java codec/CLI used by the helper: it currently loads mutable `common/build` outputs or unrestricted `LSS_SETTINGS_CLASSPATH`, neither represented by the current runtime tool snapshot. This latter observation is a remaining evidence-integrity gap, distinct from the deterministic missing-entry failure.

### C2 — P2: Save/privacy notices occupy Sodium's existing header and are drawn underneath it

**Paths:** `fabric/src/main/java/dev/vox/lss/mixin/SodiumStatusEntryHook.java:58-81`, same NeoForge twin and all four ports.

The new persistent notices are centered at fixed screen y=5 (save outcome), y=16 (active/draft sharing), and y=27 (reconnect), from a HEAD injection into vanilla `Screen` rendering. Sodium then renders its existing widgets over those same coordinates. Modern Sodium's real `VideoSettingsScreen.updateScreenDimensions` uses y=0 when GUI width <=605 or height <=312, and its `rebuild` places the 20-pixel search header at that y. Even in the inset layout the header starts at y=6. `SearchWidget.extractRenderState` fills that region and draws its edit box after the LSS text. Legacy 0.6.13 `rebuildGUIPages` places its tab buttons at y=6 with height 18, likewise crossing the first two notices.

**Reproduction:** open modern Sodium at a normal 854x480 window with GUI scale 2, change sharing, Apply, and inspect the save/privacy notice at the search header. The fixed line lengths also have no wrapping and can extend beyond the available width in English/Chinese. Legacy 1.21.1 tabs cross the same notices. The precise widget positions and draw order were verified with `javap` against the installed real Sodium 26.2 0.9.1-beta.3 and 1.21.1 0.6.13 NeoForge jars; no screenshot/live-render claim is made.

**Impact:** the explicit distinction between saved, active, draft sharing and reconnect-pending is not a usable persistent notice at ordinary supported layouts, defeating a core privacy/UI contract of sections 1 and 8.

**Fix:** reserve a real wrapped notice area outside the Sodium header/content/buttons in each layout; draw it in a deliberate layer after background widgets. Validate the supported modern/legacy native screens in Chinese and a compact GUI size. The existing status-button hit-test layout only protects the button, not these new notices.

### C3 — P3: Escape from the recovery dialog also closes the restored modern Sodium screen

**Path:** `xplat/src/main/java/dev/vox/lss/config/menu/ClientSettingsSaveScreen.java:57-58` (all ports).

Unlike the existing `ClientStatusScreen`, the new dialog does not consume an Escape press/release pair. Vanilla `Screen.keyPressed` invokes `onClose` on Escape press, restoring Sodium; modern Sodium `VideoSettingsScreen.keyReleased` then processes the same Escape release and closes itself (also undoing staged controls if needed). This makes dismissal jump two screens, unlike Keep draft's return to the settings screen. The draft itself remains retained, so this is navigation/UX severity, not data loss.

**Reproduction:** force a draft save failure, open recovery in modern Sodium, then press and release Escape. Existing `ScreenEscapeRelease` handling in `ClientStatusScreen` documents and solves this exact integration hazard. Verified from Minecraft/Sodium bytecode, not a live run.

## Missed approved contracts (separate from the regressions above)

### C4 — P2 scope gap: Chinese status bodies remain English

**Paths:** `common/src/main/java/dev/vox/lss/common/diagnostics/ClientStatusSnapshot.java:48-60`; `xplat/src/main/java/dev/vox/lss/networking/client/ClientStatusScreen.java:43,51`.

The status screen reached from either Sodium generation renders `snapshot.lines()` as literal text. All 12 lines, enum labels, availability conditions and the initial waiting message are English. Locale parity tests only cover the six translated status controls/headings plus catalog/settings keys. Select zh_cn or zh_tw and open LOD status: its body remains English. Sections 1/8 explicitly require every status string alongside options and pending messages. Keep diagnostic data MC-free, but translate its presentation at the screen boundary, including enums and waiting/error conditions.

### C5 — P2 scope gap: exported diagnostics do not distinguish saved/configured, effective and pending settings

**Paths:** `xplat/src/main/java/dev/vox/lss/networking/client/ClientStatus.java:82-113`; `common/src/main/java/dev/vox/lss/common/diagnostics/ClientStatusSnapshot.java:7-13`.

The client diagnostic snapshot still has schema version 1, active reception/rate/integration state only, and no settings configured/pending state. Saving a different rate or accepting an Xaero enable that waits for reconnect cannot be distinguished in an export from having never requested that setting. The status screen adds only a generic reconnect sentence outside the snapshot, so exports still lose it. Section 9 requires saved/configured, active and pending distinctions and deliberate diagnostic schema versioning. Add allowlisted state/pending paths or suitable counts without exporting aliases, addresses, raw YAML or exception paths, and update readers/tests deliberately.

## Checked without finding an additional defect

- `LSSClientConfig` captures `listener.getConnection()`, not the world/play listener; `SettingsHandle.beginSession` identity-checks the transport. Same-connection joins, dimension changes and repushes do not themselves adopt S settings.
- Reload reconciles far-player preference enqueue before receive-off acquisition retirement. Preference retries live outside request-manager lifetime and run even with reception off; connection replacement clears pending delivery. Unchanged preference content does not produce renderer-only reload roster churn.
- Sodium uses a stable `ClientSettingsEditSession`, one shared SAVE handler, changed-path persistence, retained edits, explicit conflict reread/rebase, failure containment, and raw high-rate preservation. Real Sodium baseline-before-storage-save ordering is represented by the stubs. The pure tests do not establish the entire live Screen/Apply loop.
- Both loaders on 1.21.1 retain a true NeoForge far-player renderer capability; 1.21.10/1.21.11/26.1 retain false, as expected. The catalog remains capability-gated and sharing is independent of renderer visibility. The only 1.21.1 catalog difference inspected was the correct explanatory comment.
- Schema specs, typed client settings, draft edit session, preference delivery and Sodium refresh logic are identical across the five lines. Screen access, setScreen and GuiGraphics/GuiGraphicsExtractor adaptations match the line surfaces reviewed.
- All five lines have matching en_us/zh_cn/zh_tw key sets (57 keys) and matching printf placeholders. This proves coverage parity for existing keys only; it does not address C4 or rendered placement.
- Main's updated notice strings use the brand command placeholder. Core pure snapshot and helper behavior does not reveal another lifecycle/privacy bug in this pass.

## Validation limits / completion evidence still needed

No support-line full build, NeoForge live menu run, Chinese screenshot acceptance, authentic YAML rig pass, or Folia multi-region pass is claimed by this review. The parent reported the main real SettingsReloadGameTest passed. Required per-line full verification and bounded live scenarios remain independent completion gates; API-symbol bytecode checks and unit greens are not substitutes. In particular the 1.21.1 modern/legacy Fabric/native-NeoForge menu matrix should exercise saved-but-inert, failed-save retention/retry, conflict rebase/discard, reopen, and privacy pending visibility.


## Implementation follow-up

Implemented in the main worktree, no commit created by this reviewer:

- Canonical tool snapshot/retention now includes the maintained settings helper, Java codec sources/resources and build launcher. Native YAML run manifests separately bind all prepared codec class/resource/JAR bytes; owned `LSS_SETTINGS_CLASSPATH` remains supported. Rig run/review and standalone native fixture verifiers check the byte identity. Qualified helper imports now enter the scenario checker closure too.
- Both Sodium generations reserve a wrapped notice strip beneath their controls. The layout has a minimum control height and physical viewport scissoring. Real Sodium bytecode places clearWidgets before cached dimension calculations. A live inspection is still required, particularly dense layouts/status-button placement.
- Recovery consumes the full Escape press/release pair.
- Translation-ready common status messages cover all connection/integration/reason states and all three locales; Minecraft UI renders translated components.
- Client diagnostic JSON schema 2 has an allowlisted saved/configured/effective settings DTO, pending reload/reconnect paths and no private collection data. Unsaved draft values are excluded. Native UI checkers now reject conflated saved/active states.

Focused verification: 4 standalone Java 21 tests passed (real store/handle save→reload→reconnect export transitions and privacy, exhaustive translated status arguments, bounded geometry). Python: 26 UI/toolchain/scenario tests, 6 fixture portability tests, and 3 active identity tests passed. These are regression controls, not native UI acceptance. Central builds/native inspection are coordinated by the parent.


Final client follow-up: footer action row now guarantees a status/recovery control
separate from notice text; geometry tests cover compact and tiny bounds. Reload
outcome mapping emits exactly one localized status, preserving failure/pending
instead of appending success; normalization notices have sanitized structured
arguments. Export state includes published/adopted revisions and pending adoption
paths. Inactive-startup recovery now requires repair plus client restart (approved
plan §4), removing the alternative publisher and read/initialize race. A Fabric
classfile contract pins constructor-only handle creation/publication. The existing
per-line ClientStatusScreen owns currentScreen access. Focused common suite now
passes 8 tests including a real failed reconciliation future and identical retry.

Remaining native layout risk highlighted to parent: legacy Sodium uses fixed,
non-scrolling rows; LSS's five-row pages fit the usual compact footer, but denser
unrelated Sodium tabs must be inspected or retain their original usable viewport.
No further production mutation is underway while central validation proceeds.


Legacy viewport follow-up is complete: the legacy builder registers weak page
identities; SodiumDraftRefresh checks the selected real currentPage by identity,
without title/locale matching or direct optional Sodium linkage. Only LSS legacy
pages reserve the footer; other tabs restore physical height and retain the prior
status-entry fallback. Modern's scrolling screen keeps the footer. Real 0.6.13
bytecode confirms setPage writes currentPage before rebuildGUI/clearWidgets. New
helper tests cover equal-but-foreign pages, returning to another tab, inherited
screen fields and absent optional surfaces; the real-jar contract pins selection
before rebuild. These final Fabric tests await central compile/run. Production
bytes are frozen for the parent's final builds and native inspection.


## Native 1.21.1 UI acceptance and user-requested pause (2026-09-28)

Four native stacks completed the exact seven-assertion `ui-apply` checker with personally inspected screenshots, current helper/codec identity, private accelerated D3D12 rendering, real Xaero/Voxy consumers, and complete owned-process cleanup. Each used a fresh tiny world and 960×540 window at GUI scale 2. This is specific layout coverage, not a claim about every window size or other Minecraft lines.

| Stack / locale | Run ID | Artifact coverage | Result |
| --- | --- | --- | --- |
| Fabric, Sodium 0.8.13-beta.2, zh_cn | `20260928T191738Z-76818ab78238` | Original Fabric `f6f1290dadd4099946370b6d4ea65bd35fab72afb46acfe182cc2267daf803a3` | Seven UI checks passed; found three untranslated detail messages |
| Fabric, Sodium 0.6.13, zh_cn | `20260928T193205Z-eb635b2f4a27` | Corrected Fabric `588806cf95484600f21a600d23b9d698288ea1c9bfb05fa64d5814e632b4cdb1` | Seven UI checks passed; corrected export, failed-save, conflict and malformed-reload messages inspected |
| Native NeoForge, Sodium 0.8.12-beta.1, zh_tw | `20260928T193737Z-69fa5e9b188d` | Corrected NeoForge `e392ec7d5e7087f3e3fad171bbf0e57e57460a538e9eca235fb70d4bf7886f80` | Seven UI checks passed; Traditional Chinese general/far-player/status/recovery/footer inspected |
| Native NeoForge, Sodium 0.6.13, zh_tw | `20260928T194342Z-7f11e89aa9a8` | Same corrected NeoForge artifact | Seven UI checks passed; Traditional Chinese general/far-player/status/recovery/conflict/footer inspected |

The seven checked flows establish actual saved-but-inert YAML, explicit reload, restoration, filesystem save failure without disk/runtime mutation, retained retry, dirty-draft preservation across status return, and Escape preserving the parent. Every required screenshot was opened and inspected; the native legacy vanilla tab retained its full viewport, while LSS tabs reserved the footer and dedicated status/recovery row. Both modern and legacy Traditional Chinese footers also fit the extra reconnect-pending notice.

The original Fabric-modern run additionally established external-edit conflict/rebase preserving an independent rate=17 disk edit, recovery Escape retaining the draft, malformed YAML preserving configured/effective revision, combined receive/share disable, and Xaero configured=false/effective=true until an actual physical disconnect/rejoin. It then restored the original settings and rejoined protocol 20. Corrected Fabric-legacy also exercised Undo/cancel→close→reopen, conflict discard preserving the independent disk edit, repaired malformed reload and two no-op reloads without revision change. Both corrected NeoForge runs exported combined receive/share disable with the session setting pending on the same negotiated connection. These are UI/state witnesses; they do not claim a server-observed sharing receipt, packet capture, far-player rendering, or performance validation.

Three native-discovered localization gaps—export feedback, raw conflict detail, and raw malformed-YAML detail—were fixed in the root's locale follow-up. Their correction was observed on the corrected legacy Fabric run; Traditional Chinese export/recovery/conflict correction was also observed on NeoForge. Original Fabric-modern evidence remains tied to its original artifact and must not be relabeled as corrected-artifact acceptance.

One supplementary **final** export on NeoForge legacy timed out because operator navigation left chat open. The screenshot records localized successful reload and the YAML bytes were restored, but no fresh typed supplementary final export is claimed. The earlier complete seven-check sequence had already produced its verified final-restored export. An initial collect while the supervisor was still cleaning up correctly reported a nonterminal journal; recollect after supervisor exit passed with cleanup complete (`neo-legacy-collect-final.json`). This is retained as an operator/evidence limitation, not presented as a product regression.

The corrected Fabric-modern retest `20260928T195042Z-2a29e6130e30` had started but was stopped during startup immediately upon the user's request to pause live checks while gaming. It has **no accepted UI/handshake proof**; its missing-proof failure is an interrupted attempt, not completed acceptance. Corrected Fabric-modern export/recovery/conflict/malformed UI verification remains pending and needs a fresh attempt if the user resumes live checks. No further live runs were started.

Private run roots and collection receipts are under `/home/vox/.local/state/lss-yaml-native-20260928/`. `PAUSE-CLEANUP.json` records all five attempts: each supervisor cleanup is complete with zero remaining children, every recorded owned PID/start-time identity is dead, and `/proc` contained no exact game run-ID marker for any attempt. The heavy slot and source freeze were released to the root; no Windows desktop, host clipboard, account contents, or personal worlds were used.


### Resumed final-artifact lineage (2026-09-28)

The user resumed live checks after the pause. Before the corrected Fabric-modern rerun, recursively compared all archive entries (including Fabric’s nested common JAR) between the previously tested corrected client artifacts and the final 1.21.1 non-live-build artifacts. Exactly five class entries changed on both Fabric and NeoForge: `ServerConfigBase`, `YamlServerConfig`, `ServerStatusSnapshot`, server `ChunkGenerationService`, and server `LSSServerCommands`. Every client/UI class and locale entry was byte-identical. The production source diff `08fb5514..6f53febb` independently lists those same five server-side files. This supports retaining the earlier three corrected client UI passes without claiming that their whole-JAR hashes equal the final artifacts.

The detailed recursive comparison receipt—including both input artifact paths/SHA-256 values, unchanged-entry counts and changed-entry SHA-256 pairs—is `/home/vox/.local/state/lss-yaml-native-20260928/final-vs-corrected-jar-entry-diff.json`. Final Fabric SHA-256 is `d0fd05cc272491acc82f134fa17f3a67d8c24c7cefe15ae7fae28424f5562ec5`; final NeoForge SHA-256 is `d47bfbef8ff727fa717a0392215bdebee3254cfdf999dcc93e16707016981a72`. A fresh modern Fabric zh_cn recipe was prepared against the final Fabric artifact and current main settings CLI; no new native acceptance is implied until its actual run completes.


### Final corrected Fabric-modern native gap closed (2026-09-28)

After the user resumed live tests and the root granted the exclusive slot, fresh run `20260928T201624Z-1358685bed4e` completed on final Fabric artifact `d0fd05cc272491acc82f134fa17f3a67d8c24c7cefe15ae7fae28424f5562ec5`, Sodium 0.8.13-beta.2, zh_cn, and the freshly frozen main settings CLI/helper closure. The exact seven-assertion UI checker passed. All six required screenshots were opened and inspected, plus the corrected export-success, failed-save, external-conflict, and malformed-reload screens; each LSS message was visibly Chinese with no raw English detail leak.

After the targeted conflict/malformed checks, the exact original YAML bytes were restored and explicitly reloaded. A fresh final typed export confirmed negotiated protocol 20, saved/configured/effective values equal, reception/sharing/Xaero enabled, published/adopted revision 4/4, and empty pending-reload/reconnect/adoption lists. `evidence/localized-followup-observations.json` binds the inspected targeted screenshots and these observations. No additional client UI rerun remains pending from this review.

Final run hash: `952572303639223d1534991bbdef80e1bbfe4e0d10e6c872d3067a9c3f78e5bb`. Private receipts: `/home/vox/.local/state/lss-yaml-native-20260928/final-modern-collect.json` and `FINAL-MODERN-CLEANUP.json`. Collection reported **passed / cleanup complete**; all 14 recorded process identities were dead, no exact game run-ID marker remained, and the supervisor reported zero remaining children. The exclusive slot and source freeze were explicitly released to the root before the next agent's native work. This closes the interrupted modern retest described above; the interrupted attempt itself remains unaccepted, and the earlier artifact/hash distinctions and supplementary evidence limits remain unchanged.

## Native lifecycle followup

The root's first receive-lifecycle attempt, `20260928T205345Z-d0be9b38cba1`, exposed
a test-observer error. Its `ClientNetGlue.reconcileClientConfig()` mixin also saw
ordinary end-of-tick reconciliation before asynchronous reload publication. An
independent Astra followup verified the stack and both final product classfiles:
the private instance `LSSClientConfig.reconcile()V` is called only by reload adoption,
after publication and before acknowledgement. The fixture was retargeted there
(`585a1d71`, ported) with nonstatic HEAD/RETURN callbacks and `require=1`.
The actual-boundary positive native-debt check was retained. No artificial debt hold
or product hook was added.

Fresh remapped Fabric and named NeoForge fixture jars compiled and their injection
metadata was inspected. The root then executed both complete native scenarios:

- Fabric modern: `20260928T211313Z-88c90131dee4`, run hash
  `b22247bb89eaff72e50f55d53416e5b58b261863f17e5ada8324739e80d17c40`.
- Native NeoForge legacy: `20260928T211428Z-2107adb8269e`, run hash
  `14d0b5a7f4bce419bb750d570ec7e8d92762a234fde251e76f8edd12626d81e4`.

Both passed all five semantic checks: inert saves and explicit receive OFF, preserved
committed rebuilds and their drain, fresh receive-ON bodies, rejection of a real held
old callback after transport replacement, and retirement of the old native world.
Collection reported no errors and complete cleanup; both supervisor receipts record
zero remaining children. Their raw proofs and logs are under
`/home/vox/.local/state/lss-rig/runs/`. The earlier failed attempt remains preserved
and unaccepted. No client UI or lifecycle acceptance work remains pending.
