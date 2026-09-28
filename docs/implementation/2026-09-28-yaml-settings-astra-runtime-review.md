# Independent runtime implementation review

Reviewed commit 83876c4c plus current worktree in `/home/vox/projects/lss-settings-plan`, against plan sections 5 and 6.1.1. No repository edits or Gradle/heavy builds performed. Existing compiled common classes were used for a standalone runtime probe.

## Findings

### R1 — P2: A failed backfill enable consumes the enable transition, so a successful retry leaves the worker stopped

`common/src/main/java/dev/vox/lss/common/store/StoreBackfill.java:125-141`.

`updatePolicy` replaces `desiredPolicy` with enabled=true and clears `successorRequested` before `start()` succeeds. If the store is temporarily unhealthy (including its startup-sweep window), or starting the worker fails, the first receipt reports failure but the next call with the same enabled target observes no false-to-true edge. It completes normally through `adoptPolicyAtBoundary` without starting a worker. This is independently broken even after SettingsReload is fixed to retry unresolved adoption.

Validated using a real SQLite store and the existing compiled StoreBackfill, with reflection temporarily setting its `serving` flag false to reproduce startup-style unavailability:

1. `updatePolicy(false, 1000, 1)` succeeds.
2. While store is not healthy, `updatePolicy(true, 1000, 2)` fails.
3. Restore store health; repeat `updatePolicy(true, 1000, 2)`.
4. Second receipt completes normally, but `isRunning()` is false and `statusLine()` is `idle`.

Observed output:

```
first=java.lang.IllegalStateException: Backfill store is not healthy
retry completed normally, healthy=true, running=false, status=idle
```

Probe: `/tmp/lss-yaml-validation/astra-probes/BackfillRetryProbe.java`; classpath: sibling `runtime-classpath.txt`.

Preserve the unmet start intent until a start actually succeeds. A retry must consume that intent without reopening manually paused/completed jobs or restarting automatically in a loop. Also check the worker-finally successor path: it acknowledges policy before attempting successor startup, and currently neither false return nor thrown startup failure reaches that already-successful receipt. Add focused failed-start/retry coverage alongside rapid off/on/manual-pause tests.

### R2 — P2: Reconciliation uses publication differences instead of owner adoption, leaving changed-candidate retries incomplete

`xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java:679-682,698-705`; Paper twin `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java:1390-1406`.

This is the runtime half of the schema review's confirmed failed-reload/no-op-retry issue. It additionally affects a changed candidate today: start with store cap A; publish cap B, but fail the queued store adoption once; then change only a different hot field and reload. The second commit's previous and next cap are both B, so these branches never call SQLite again. The second reload can report applied while the store remains on cap A. SqliteLodStore explicitly has a recoverable policy-operation failure path (`apply` failure -> failed adoption receipt; later queued work is accepted), so this is not dependent on a permanently dead subsystem.

A retained last-successful settings baseline alone is also insufficient once owners partially adopt: if session distance B was successfully pushed while another owner failed, reverting desired distance to A must push A even though A equals the last wholly-successful settings. Conversely, retrying an unchanged desired B after a store-only failure should not push duplicate session/roster refreshes merely because the whole operation has never succeeded.

Recommended owner implementation:

* Reapply the current desired SQLite cap/cadence whenever reconciling unresolved work (unconditional policy submission is safe: unchanged cadence preserves deadline). Determine cap increase from the actually adopted `sizeCapBytes()` before that adoption, not the prior publication snapshot.
* Track session-adopted distance and generation independently from the overall operation; set the tracking only after the processing fence and session refresh execute. Compare new desired target with these actual owner values, including changed candidate/revert cases.
* Track gate/roster policy adoption separately or execute idempotent reconciliation without flooding. Update the corresponding group tracking only after execution.
* Retain unfulfilled backfill start intent as described in R1.

Current revision gates use `<`, not `<=`, in generation controllers, processor, SQLite and backfill. Equal-revision retries therefore pass; do not accidentally change that behavior. Processor same-target replay is idempotent for generation terminals (epoch reset occurs only on disabled -> enabled) and cache TTL (memo clear only when TTL changes). SQLite same-cadence replay preserves deadline. Keep retry revision monotonic relative to changed candidates.

## Reviewed paths without an additional validated bug

* Dormant generation controller construction, admission on owner, per-job timeout capture, cap reduction, tick/drain while disabled.
* Processing-generation enable fence: per-state terminal epoch invalidation, done-set reset, stale in-flight generation marking; service holds terminal sends until owner adoption/session announcement.
* Per-submit and per-backfill-column immutable NBT serialization policy capture in both readers.
* Timestamp-cache adoption and bounded ordered trimming, TTL memo clear, dynamic effective AUTO budget input.
* Backfill boundary pause/rate accounting and cap checks, normal rapid off/on successor serialization and manual-pause retention.
* Store cap/cadence adoption on the batcher, unchanged deadline preservation, queued receipt cancellation.
* Region-summary request gate (existing accepted assembly is retained), dynamic queue/yield paths.
* Paper service mailbox ownership after lifecycle drain; explicit shutdown receipt cancellation and inactive-owner receipt rejection in both services.

## Validation limits

The new real reload gametest exercises publication, distance override/range, restart-pending state, invalid-file rejection and restoration. It does not itself cover all of section 6.1.1's generation-disable/enable client retry and disk-read-in-flight scenarios. Existing green suite results were supplied by the root agent; this review did not rerun them or claim new live Fabric/Paper/Folia coverage. R1 is runtime-reproduced; R2 is a deterministic source-path finding extending the independently reproduced generic failed-reload bug.

## Follow-up implementation (same worktree, no commit)

R1/R2 fixes implemented in StoreBackfill and both service twins. Backfill retains failed start intent and acknowledges off/on or raised-cap successors only after startup; explicit operator start also consumes retained intent. SQLite reconciliation compares its immutable adopted policy, and service session/gate/roster progress is retained separately across owner failures. Generation refresh tracking advances only after the re-push returns. Unreported legacy reconnect counts survive a different owner's failure until a successful report.

Focused Gradle run passed: StorePolicyReloadTest 6/0, PaperRequestProcessingServiceTest 63/0; log `/tmp/lss-yaml-validation/runtime-retry-tests.log`. This covers the two new backfill tests and three new service tests. After that run, final inspection moved successor-intent consumption into successful startRun (so explicit operator starts consume it too), and preserved unreported legacy counts across retries with an extra assertion in the changed-candidate test. These final small followups still require the root's next serialized test run. No additional build was started while the 26.1 build owns the slot. `git diff --check` passed.

## Final disposition and diagnostic followup — 2026-09-28

This section supersedes the implementation-stage caveats above about uncommitted fixes and untested final retry adjustments.

**R1 and R2 are resolved.** Main commit `a488af53560c0d343f699e2d0bccf95ac2aa27f5` contains the final runtime fixes and regressions, including successful-start intent consumption and retained legacy reconnect feedback. Backfill preserves an unmet enable/successor request until startup succeeds, including cap-increase resumption within the returned receipt. Both service twins compare desired settings against the store's adopted policy and separately completed session/service/roster policies. Same-revision retries remain valid; changed-candidate retries and reversions converge without treating publication as adoption or duplicating a completed session refresh.

The focused real-store/service run passed StorePolicyReloadTest **6/0** and PaperRequestProcessingServiceTest **63/0**. The root's subsequent full main run included the final adjustments and passed common **889/0**, Paper **536/0** and the other platform gates recorded in the [progress ledger](2026-09-28-yaml-settings-progress.md); `/tmp/lss-yaml-validation/main-full-final.log` records `BUILD SUCCESSFUL in 4m 48s`. This closes the earlier narrow-test timing caveat. It does not substitute for validation of later unrelated changes or new live acceptance.

**Server generation diagnostics are corrected** in main `4b3d62fa9d71c9231b9fa87a0de1d7d5e8e958b3`, ported as 1.21.1 `c5b735db`, 1.21.10 `149ad635`, 1.21.11 `48f9704c`, and 26.1 `bf7fec43`. The exported admission value now includes the whole-service enable gate and reads the actual generation controller on its owner. A published/configured true value no longer substitutes for an owner's false admission gate, and disabled Paper correctly retains an available service object while reporting admission false. The schema-1 field `generationConfiguredForRestart` and its accessor remain as documented legacy names for the accepted configured value. Human summaries say “generation admission” and “Generation configured”; they no longer infer restart requirements from a boolean difference. The active troubleshooting guidance and smoke summary expectations match explicit save/reload behavior.

Focused XML evidence for this diagnostic commit reports **1 common test**, **22 Paper command tests**, and **25 Paper generation tests**, all with **zero failures, errors or skips** (timestamps 2026-09-28 19:54 UTC). These include owner/configured disagreement in both directions, whole-service disable, missing owner, retained legacy JSON fields and the absence of false restart guidance. The root also reports **9 Python smoke-helper tests passed**. XML locations are `common/build/test-results/test/TEST-dev.vox.lss.common.diagnostics.ServerStatusSnapshotTest.xml`, `paper/build/test-results/test/TEST-dev.vox.lss.paper.PaperCommandsTest.xml`, and `paper/build/test-results/test/TEST-dev.vox.lss.paper.PaperChunkGenerationServiceTest.xml`.

No Gradle invocation, game server or live check was started for this final report update. Live checks remain on hold at the user's request; the progress ledger owns their eventual disposition.

## Root completion record

The user subsequently lifted the hold. Final complete builds on all five lines and
the required Fabric/Paper/Folia native reload, client lifecycle and migration boot
checks passed. All review fixes above are included in the validated final artifacts.
The [implementation ledger](2026-09-28-yaml-settings-progress.md) and
[native report](2026-09-28-yaml-settings-native-validation.md) retain exact results
and limitations. The earlier hold describes the report's original checkpoint.
