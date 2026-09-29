# YAML settings plan review — schema, persistence and migration

Date: 2026-09-28. Reviewer: independent Astra schema/migration review.
Reviewed the proposal and all three approval examples against `d4b415d5`, after
reading this worktree's `CLAUDE.md`. This was a read-only implementation review;
only this report was written. No builds, games, runtime changes or external posts.

**Verdict: sound design, with two migration contracts to resolve before presenting
the examples as the final approval proposal.** The bounded-parser and write-safety
details below should also be made explicit in the implementation acceptance gates.
These are targeted corrections; they do not require another settings architecture.

## Confirmed coverage

- The inventory really is 45 shared server fields, 30 client fields and one Paper
  field. The mapping covers the retained settings, including expert switches, the
  bandwidth aliases' retirement and the removed automatic-coexistence option.
- The examples preserve current defaults apart from the requested fresh Nether
  radius. The Paper event list, 300-second Paper resweep versus zero elsewhere,
  fresh-store-on/existing-store-off distinction, client sharing default and the
  far-player animation distance are consistent with current source.
- Missing versus empty collection semantics, the legacy global-distance migration,
  adoption of the selected brand's actual path, invalid-YAML precedence, no read-time
  rewrite, and configured/effective separation are appropriately specified.
- A relocated common-owned YAML dependency and low-level typed mapping are a
  reasonable fit for the current Fabric nested-common and Paper/NeoForge shading
  layouts. Artifact checks are necessary; this review did not independently resolve
  the published dependency or prove its packaging.

## Findings

### S1 — High: preserve exact legacy world keys when splitting the namespace

**Plan:** section 4, final compatibility bullet; sections 3.1 and 7.

The instruction to copy “syntactically valid resource-location entries” into
`by_dimension` does not prohibit namespace completion or other identifier
canonicalization. A legacy Paper key `the_nether` means the exact Bukkit world
name `the_nether`; it must not acquire a second meaning of
`minecraft:the_nether`. Likewise an unqualified/inert Fabric key must not start
matching a dimension after migration. Resource identifier syntax and the strings
that the old resolver actually looked up are different contracts.

**Evidence:** `ServerConfigBase.java:894` (`clampLodDistanceByWorld`) trims keys but
does not add namespaces; `ServerConfigBase.java:916` (`lodDistanceForWorld`) uses
exact `Map.get`. `PaperWorldLod.java:29` passes the Bukkit name and the full
dimension identifier separately. `ConfigValidationTest.java:139` also pins
legacy trimming/dropping/clamping rather than identifier normalization.

**Proposed fix:** first apply the legacy map's trim/drop/value-clamp rules. On
Paper, copy every surviving exact key to `by_world`; copy to `by_dimension` only
when the original surviving string is already a canonical, fully qualified
identifier, without changing its spelling. Keep unqualified or otherwise inert
legacy keys inert on Fabric/NeoForge, with a generic/path-indexed migration
warning if they are omitted from the active dimension map. Do not let strict new
identifier validation turn such an old harmless key into a whole-file failure.
Add corpus cases for `the_nether`, `minecraft:the_nether`, a custom world named
`minecraft:the_nether`, a whitespace-padded key and an invalid/inert key. Assert
the effective distance before/after for both the named custom world and the
ordinary Nether.

### S2 — High: enumerate legacy normalization before applying strict YAML validation

**Plan:** sections 3.1, 4 and 11.1.

The new YAML contract rejects nulls and invalid enums, while the migration section
only explicitly describes store and bandwidth normalization and defers the rest
to “known historical aliases.” Several accepted legacy shapes have materially
different semantics from either defaulting or rejecting the whole file. A simple
tree rename followed by the new validator would not meet the preservation promise.

**Evidence:**

- `JsonConfigLoadTest.java:415` pins a null primitive as its compiled default while
  preserving other settings in the same JSON file.
- `PaperConfigLoadTest.java:170` pins `updateEvents: null` as an **empty** list;
  absent `updateEvents` retains the nonempty default. Its null-element test also
  preserves valid neighboring events while registration skips the null.
- `ServerConfigBase.java:987` accepts trimmed/case-folded `opt-in`, `optin` and
  `opt_in`, and maps unknown/null far-player modes to **off**, rather than the
  normal on default. `ConfigValidationTest.java:1200` pins this privacy behavior.
- `ConfigValidationTest.java:575` and `:597` pin unknown/null x-ray mode to auto
  and null hidden-block list to the default list, whereas an explicit empty list
  stays empty.
- `LSSClientConfig.validate()` and `ClientAliasConfigValidationTest` distinguish
  fallback/map/null repairs from whole-group alias rejection; the canonical alias
  spelling is intentionally preserved for existing cache identity.

**Proposed fix:** add a concise migration-normalization matrix now, covering these
cases, before conversion into the strict YAML schema. Carry over current typed
values and accepted enum aliases explicitly; distinguish missing, null and empty
per field. Retain original bytes in the backup and report normalization without
private values. Keep strict null/type/enum rejection for newly authored YAML.
For permissive Gson quirks such as quoted primitive values, explicitly decide
which are converted by the migration adapter and which cause an actionable
migration error; do not silently replace them with fresh defaults. Include the
existing load-test fixtures in the migration corpus instead of relying only on
new happy-path fixtures. No legacy runtime classes need to remain.

### S3 — Medium: enforce structural budgets before building either parse tree

**Plan:** sections 3.2 and 4, migration step 1; section 11.1.

Section 3.2 says to validate node counts and nesting while traversing composed
nodes. If this is the first depth check, the composer has already processed the
entire nested input. The JSON adapter similarly specifies a bounded byte count
but no explicit pre-tree depth/token budget. A file within 1 MiB can still contain
many more than 32 nested containers. A traversal-only check does not establish the
bounded-parser promise and risks parser stack/heap failure before normal error
handling.

**Proposed fix:** enforce byte, depth and event/token/node budgets in parser
configuration or the event/stream layer **before** recursive composition/tree
materialization, for both YAML and legacy JSON. Retain post-compose type/path
checks. Pin depth 32 accepted/33 rejected, excessive shallow node counts and a
deep sub-1-MiB input for both formats; rejection must leave the active snapshot
and all source bytes unchanged. This complements the already-planned event-layer
alias rejection.

### S4 — Medium: distinguish atomic publication from no-clobber and conflict checks

**Plan:** section 4, migration steps 3–4; section 8, hash check and atomic save;
section 11.1–2.

The outcomes are correctly stated, but the required IO primitive is still
underspecified. A check that the target does not exist followed by an atomic
rename is not a no-clobber transaction; likewise checking a draft's base hash
before serializing/verifying its temp file leaves a window for an external edit.
The current `JsonConfig.trySave()` (`JsonConfig.java:135`) explicitly replaces its
target, so it cannot simply become the shared installation helper unchanged.
Migration also lacks a final source-byte recheck: a JSON edit during migration
could remain on disk while a stale YAML becomes authoritative.

**Proposed fix:** separate create-only publication (fresh creation/migration) from
replace-existing publication (Sodium save). Specify and test a genuinely
create-only installation mechanism on supported filesystems; no check-then-rename
fallback may replace a competing YAML. Create backups exclusively and verify
their bytes before publication. Recheck migration source bytes and all relevant
YAML candidates immediately before publishing; recheck a draft's base hash after
its temp file is fully written/validated, immediately before replacement. Serialize
all in-process writers through the settings handle. Test concurrent-target creation
and source/draft edits injected at these boundaries. State the external-editor
race limit honestly: a portable hash check plus rename is not a filesystem CAS
against an uncooperative editor. Avoid promising a stronger guarantee than the
selected implementation provides. Do not add a heavyweight cross-process editing
system merely to satisfy that wording.

### S5 — Low: generated examples contradict the inactive-section omission rule

**Plan:** section 3 (“Per-platform irrelevant sections are omitted from generated
files”) and the approval examples.

The Fabric/NeoForge default example emits Paper-only `lod.distance.by_world`;
the Paper default example emits the wholly inactive `storage.lod_store.backfill`
group. The comments disclose inactivity, but these are presented as generated
fresh defaults, not as copied cross-platform files. Both possible policies are
reasonable; the proposal should choose one consistently before approval.

**Proposed fix:** follow the current prose and omit `by_world` from fresh mod-loader
files and `backfill` from fresh Paper files, while continuing to recognize and
preserve them in a copied/migrated document with an inactive warning. Alternatively
explicitly revise the generation policy to emit shared inactive groups, and make
the resulting larger files an approval choice. No runtime capability change is
needed.

## Small clarity improvement

The bandwidth example comments correctly say MiB and raw payload work, but unlike
the adjacent sentinel settings do not explain zero. The existing minimum is
1,024 bytes/s (`LSSConstants.java:115`), so **zero is clamped to 0.0009765625 MiB/s,
not unlimited**. A brief inline “zero clamps to the minimum” note would prevent a
common operator mistake. This is documentation polish, not a new behavior or a
blocker.

## Scope and disposition

Resolve S1 and S2 in the plan before final approval; add S3/S4's concrete acceptance
criteria and align S5's examples with the selected policy. The principal defaults,
complete retained-field coverage, cross-brand precedence, zero/empty semantics,
comment-preserving draft model and removal of the old runtime JSON reader do not
need redesign. Runtime side effects, Sodium lifecycle and support-line gates are
the other independent review's scope.
