# YAML settings plan review: runtime, UI and support lines

Reviewer: Astra, independent review 2. Date: 2026-09-28.
Reviewed proposal: `2026-09-28-yaml-settings-implementation-plan.md` and all three
YAML examples, against main `d4b415d5`. Read this checkout's `CLAUDE.md`.

**Verdict: revise before presenting the final approval examples.** The proposed
ownership model is sound, but the advertised hot Xaero transition conflicts with
the frozen cache identity, and Sodium persistence failures need an explicit UI
transaction design. Neither requires a larger settings framework or live cache
identity migration. The smaller corrections below preserve the requested scope.

This was read-only source, test, metadata and cached dependency bytecode inspection.
No implementation, builds, tests, game launches or external posts were performed.
Only this review file was written. Line facts were checked directly in
`/home/vox/projects/lss-v0.15.1/{1.21.1,1.21.10,1.21.11,26.1,26.2}/config/compatibility/line.json`.

## R1 — HIGH: hot Xaero enable conflicts with reconnect-only cache identity

**Proposal evidence:** §6.2 marks `integrations.xaero_map.enabled` H, while
`cache.address_aliases` and the cache session group remain frozen until reconnect.
The client example says aliases stay inactive while the bridge is enabled, yet
does not mark the bridge switch `[reconnect]`.

**Source evidence:** `ClientNetGlue.createRequestManager` (lines 186–224) obtains
the address decision from `AliasLatch.forConnection`; rebuilding the request
manager does not recompute that decision. `AliasLatch.java` lines 7–17 and 52–59
make that retention explicit. `ClientNetGlue.computeAliasDecision` lines 249–268
consults the bridge only while computing the decision. `AliasCorroboration.java`
lines 4–10 and its first guard document why an aliased LSS cache must not coexist
with Xaero's unaliased, per-address map store: cached freshness can suppress the
terrain needed by the newly active consumer.

**Failure:** start with a corroborated alias and the bridge off; then enable the
bridge by reload. The frozen address remains aliased, even after the proposed
re-handshake. Previously satisfied columns can remain absent from the map. A
blanket manager rebuild does not solve the identity mismatch.

**Concrete fix:** classify the bridge enabled flag S on **both** transitions, in
the same frozen cache/consumer session group. Keep bridge backpressure H. Update
the mapping, YAML comment, reload feedback and lifecycle tests; remove the
hot-enable/re-handshake promise. Continue to preserve committed native rebuilds
for the separate H receive-off operation. Do not introduce live identity
migration merely to keep this one switch hot.

**Acceptance:** reload bridge changes in an aliased session and show them pending;
dimension switches, receive off/on, reset and server config re-push must not activate
them. On the next connection, compute the cache decision using the newly accepted
bridge setting before loading cached freshness or creating acquisition work.

## R2 — HIGH: Sodium marks options clean before the shared save handler runs

**Proposal evidence:** §8 promises that a conflicting or failed save retains the
draft and keeps pending edits visible, with one storage handler. The stable draft
adapter is necessary, but it alone does not implement that promise.

**Source evidence:** `LegacySodiumPageTest.theScreensApplyContractSavesEachStorageOnce`
(lines 180–218) models the actual order: call each option's `applyChanges`, then
save each distinct storage. `OptionImpl.applyChanges` sets its baseline value to
the edited value before storage persistence. This was confirmed directly with
`javap` on cached Sodium `mc1.21.1-0.6.13-neoforge`. The same ordering exists in
cached `mc26.2-0.9.1-beta.3-fabric`: `StatefulOption.applyChanges` commits its
baseline and schedules the handler; `Config.applyAllOptions` flushes handlers
after traversing options. Returning from a failed handler leaves host controls
clean. Throwing out of the handler is not a safe error UI contract either.

**Failure:** an external file edit or disk failure causes the save to be refused,
but Sodium has already cleared its dirty controls. Apply may be disabled, and
reopening from disk can discard the user's supposedly retained draft. This is
especially misleading for a sharing opt-out.

**Concrete fix:** specify an LSS-owned document edit session with a persisted base,
staged changed paths and save outcome independent of Sodium's `hasChanged` state.
Use generation-specific lifecycle adapters to restore host dirty state safely,
or retain the draft in an explicit translated error/retry/conflict dialog. Either
approach must give a usable retry/discard path and survive the expected screen
transition. Conflict resolution must re-read and explicitly rebase/reapply the
retained edits or discard them; a retry must not silently overwrite the new file.
Keep one atomic save and no settings publication. Extend the real Sodium surface
checks for any new refresh/open hooks, rather than assuming the registration
callback runs again every time the modern screen opens.

**Acceptance:** both generations must demonstrate failed Apply → edits retained
and visibly unsaved → retry succeeds without re-entering values; external edit →
conflict → disk preserved; cancel/discard behavior; reopen after success. Verify
active client state and preferences remain unchanged throughout. Existing stubs
must retain the real host's baseline-before-save ordering so tests can catch this.

## R3 — MEDIUM: distinguish accepted privacy settings from outbound delivery

**Proposal evidence:** §5 already acknowledges post-publication reconciliation
failures, and §6.2 correctly puts sharing preferences before receive retirement.
Make that requirement concrete for the existing send helper: the current helper
cannot return the outcome the proposed UI needs.

**Source evidence:** `FarPlayerClientSupport.maybeSendPrefs` lines 160–175 is
`void`, catches send exceptions and only logs at debug. Its success guard records
the last enqueued preferences; there is no server acknowledgment. The callers are
settings/session/reset events, not a permanent delivery retry loop.
`ClientSessionGate.reconcileReception` lines 145–169 deliberately retires
acquisition while retaining the connection and privacy state.

**Concrete fix:** add a small typed preference-send result and an owner-held
pending reconciliation marker. A thrown send must produce visible “saved/accepted
locally; privacy preference not sent” feedback and remain retryable while the same
valid connection exists, including when reception is off. Clear that work on
disconnect and guard it by connection identity. Successful enqueue may be reported
as “sent”, not “server confirmed”. An unchanged reload must remain a settings
no-op while not erasing or suppressing an already pending delivery retry. Do not
add a wire acknowledgment or new privacy protocol as part of this redesign.

**Acceptance:** exercise a throwing preference sender during a combined
receive-off/share-off reload, then a successful retry while reception stays off;
ensure no acquisition restarts, no stale retry reaches another server, and the UI
never claims remote confirmation. Also keep the no-channel/legacy limitation
visible, as the plan already requires.

## R4 — LOW: examples contradict the promised omission of inactive sections

**Evidence:** §3 says fresh generated files omit platform-irrelevant sections.
The Fabric/NeoForge example still emits `lod.distance.by_world`, and the Paper
example emits both `storage.lod_store.backfill` options even though §6.1 marks
them inactive there. A generated default should not immediately warn about its
own deliberately inactive settings.

**Concrete fix:** omit `by_world` from the Fabric/NeoForge fresh example and omit
the backfill block from the Paper fresh example. Continue recognizing and retaining
these paths when an operator copies a file across platforms, with the documented
inactive notice. Alternatively explicitly change the generation rule to a common
superset, but that is noisier than the existing proposal and should be an approval
choice rather than an accidental contradiction.

## Details to preserve during implementation

- The stable settings handle, deeply immutable snapshots and per-operation capture
  address the real `final config` capture in both request services. Boot settings
  must remain distinct from requested restart values in every old config reader,
  including handshakes and disk-gate auto sizing. The plan already states this well.
- Keep the Paper control path independent of the running service. The existing
  `PaperCommands` null-service guard precedes `set`, while
  `PaperRequestProcessingService.enqueueRuntimeTask` explicitly permits dropped
  tasks during shutdown (lines 785–790). Reload needs a completion/cancellation
  outcome owned by the handle, and must not inherit that silent-drop behavior.
- Clarify the S boundary using actual connection identity. `AliasLatch` intentionally
  resets at play JOIN because server play→configuration transitions may not emit
  disconnect. A new play JOIN on the same network connection must not accidentally
  adopt pending S settings. Recompute any required runtime world/alias observation
  using the still-frozen settings; a real reconnect adopts the accepted pending
  group. Add this case beside dimension switch and manager re-push tests.
- A persistent disk-save notice should distinguish saved-but-not-reloaded,
  accepted-but-reconnect-pending and unsaved/conflicted edits. The sharing text
  should describe the active state as well as the draft. Keep it available on
  unsupported-renderer loaders: sharing is independent of local rendering.
- The three locale key checks should include translated labels, dynamic units and
  error/pending paths; matching English keys alone does not establish translation
  completeness. Preserve the existing curved rate slider and its rule that saving
  another option never snaps an untouched hand-authored high rate to the UI maximum.
- The support matrix matches all five current line metadata files. Preserve the
  listed Java, renderer, shipping and gametest gates. The proposed bounded live
  matrix is proportionate; there is no need for another performance campaign or
  extra unsupported-platform feature work to approve this settings design.

With R1–R4 folded into the proposal and examples, this is ready for the user's
schema/behavior approval before implementation. The checks above are future
implementation gates, not claims that runtime validation has already passed.
