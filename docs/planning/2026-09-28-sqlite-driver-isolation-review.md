# SQLite driver isolation plan — review (3 Opus 5.5 + 1 Fable 5.1, consolidated)

Date: 2026-09-28. Reviewed: `2026-09-28-sqlite-driver-isolation-implementation-plan.md`
(commit 7542b6ce) against tree HEAD 77bddb47, the author's probe under
`/tmp/lss-sqlite-isolation-planning/`, the stock driver jar, and PR #306 at
`/home/vox/projects/lss-neo-sqlite-1.21.1`. Read-only; no builds, no posts.
Lenses: classloader/JNI (Opus), store lifecycle (Opus), packaging/lines (Opus),
product/fact-check (Fable). Findings marked **[verified]** were re-checked by the
consolidating agent against source; the rest are the reviewer's evidence.

**Verdict: the mechanism is right and the direction is right; do not start P1 as
written.** Two reviewers independently found the same data-safety hole (H1), one
found that the plan's core "never delete an existing store" invariant is not
actually enforced by the code it relies on (H2), and the delivery is chained
behind an unmerged 42-commit feature branch (H5). Fold H1–H5 and the mediums,
then proceed.

## HIGH

### H1 — Two SQLite library copies on one `store.db` inside one process (§3.4)
**[verified]** `SqliteLodStore.shutdown()` joins the batcher for 5 s
(`SqliteLodStore.java:1201`) and, when it is still alive, deliberately skips
`closeWriter()` (`:1212`). The reader pool and backfill worker have the same
bounded waits (`AbstractChunkDiskReader.java:915`, `StoreBackfill.java:284`).
§3.4 asks for exactly that deferral AND for a fresh runtime on integrated-world
reopen or plugin re-enable. That new runtime extracts and loads a second native
copy. Two independently loaded SQLite libraries do not share a lock table; on
POSIX any `close()` of the file by one copy drops the whole process's `fcntl`
locks for the other. This is sqlite.org's "multiple copies of SQLite linked
into the same application" corruption case. Today one native per loader makes
Fabric/NeoForge immune; Paper `/reload` already carries the hazard.
**Fix:** one runtime per LSS *defining loader*, held in a static of an LSS class
(dies with the mod/plugin loader, no JVM-global pin) and reused across store
lifetimes. Belt: each runtime takes `FileChannel.tryLock` on
`<store>/owner.lock` until final cleanup; Java file locks are JVM-wide, so a
second open on the same path gets `OverlappingFileLockException` and the store
stays off for that boot with files untouched (also covers two processes on one
world). Deferred cleanup must refcount batcher, readers AND backfill, not only
the batcher (§3.4 covers only the batcher). Move `SoakStoreDowngrade` onto the
same runtime (it runs at SERVER_STARTING before the store opens).
**Acceptance:** wedge the batcher, reopen the same directory → refused/waits,
rows and DB/WAL/SHM bytes unchanged; one native copy per loader after N reopens;
a reader stuck mid-query during shutdown never sees its loader closed.

### H2 — The recreate path deletes the DB on ANY exception; the plan's invariant rests on ordering alone (§2, §3.2 step 6, §3.3)
**[verified]** `openOrRecreateWriter` wraps everything in `catch (Exception first)`
→ `deleteDbFiles()` → rebuild (`SqliteLodStore.java:445-452`), and
`PRAGMA journal_mode=WAL` (`:470`) runs inside that block. A filesystem without
shm/mmap support (network, FUSE) or a transient "database is locked" deletes a
multi-GB store and then fails identically on every boot. The plan's in-memory
probe (§3.2 step 6) exercises no file VFS, WAL, shm or locking, so it cannot
protect against this.
**Fix:** run the probe file-backed in `.sqlite-runtime/probe.db` (WAL, one write,
one reader) before `store.db` is touched; narrow the recreate branch to
`SQLException.getErrorCode()` ∈ {11 SQLITE_CORRUPT, 26 SQLITE_NOTADB} plus the
existing `MetaVerdict.DROP`; everything else → store-off, files kept.
`getErrorCode()` is plain `java.sql`, so the JDK-only boundary holds. Same
narrowing for the read path: `get`/`getFrame` enqueue `DeleteRows` for any
non-`SQLException` (`:811`, `:873`) — a `LinkageError` from a closed loader or a
capsule changed under it would delete good rows on every read.
**Acceptance:** keep `corruptDbFileIsDroppedAndRecreated`; add "WAL pragma throws
on a seeded DB → bytes unchanged" and "reader throws LinkageError → row count
unchanged".

### H3 — The Tier-1 store tests would run a second ambient driver against a live store (§5)
Seven test files open fixture connections through the ambient
`new org.sqlite.SQLiteDataSource()` while the store's writer and readers are
open (`SqliteLodStoreTest.java:609`, `SqliteLodStoreMigrationTest` ×7,
`SqliteFrameServingTest:149`, `StoreBackfillTest:379`,
`SqliteLodStoreMaskShutdownTest:75`, `StoreLegacyRowCompositionTest` ×4,
`LodStoreExperimentTool:431`). After isolation that is the H1 hazard inside the
test JVM (flaky or corrupting), and the `testImplementation` driver hides any
accidental reliance on the classpath copy. `build.yml` runs `:fabric:build` /
`:paper:test`, not `:common:test`; Fabric duplicates the store tests and has a
`localRuntime` sqlite row (`fabric/build.gradle:76`).
**Fix:** package-private `openFixtureConnectionForTest()` backed by the store's
own runtime; drop `testImplementation`/`localRuntime` sqlite from common and
fabric; a named forked `Test` task with a filtered classpath wired into `check`.
**Acceptance:** the common test classpath has no `org/sqlite`; Tier 1 still green.

### H4 — Release-check pins invert, the capsule is never inspected, and the VSS pair gate ignores it (§3.1, §5)
**[verified]** `release_check.py:422` flags any `dev/vox/lss/**` entry containing
`/sqlite/` as an illegal relocation — the plan's own capsule path
`dev/vox/lss/internal/sqlite/sqlite-jdbc.jar.bin` trips it. The NeoForge probe
requires `SqliteLodStore.class` to reference `org/sqlite/SQLiteDataSource`
(`:569`), Paper requires flat `org/sqlite/JDBC.class` (`:419`), Fabric requires
the nested `sqlite-jdbc-slim.jar` (`:383-410`), and the notices check looks for
the license inside that slim jar (`:813-817`). The nested walkers recurse only
into `META-INF/jars|jarjar/*.jar` (`:102-125`) and `check_artifacts.py:33` only
into `*.jar`, so a `.jar.bin` trips nothing — and is also never inspected.
The descriptor digest is generated from the same bytes, so "descriptor matches
capsule" proves nothing about provenance. The VSS pair identity hashes only
`dev/vox/lss/**.class` on Paper/NeoForge (`:1086`), so a VSS jar with a wrong
or missing capsule passes.
**Fix:** a rule table in §5 (each old pin → its inverse); a new capsule walker
(exactly one capsule per artifact: flat on Paper/NeoForge, inside the nested
common jar on Fabric) comparing SHA-256 and length to a **checked-in** constant,
then opening it to check natives, LICENSE, `META-INF/services/java.sql.Driver`
and the bridge; add capsule+descriptor+bridge bytes to the pair digest; choose a
capsule path without `/sqlite/` or rewrite the heuristic; negative selftest
fixtures for wrong-SHA-but-matching-descriptor, duplicate capsule, missing
native/license, reintroduced flat/nested sqlite, `sqlite_jdbc` TOML row.

### H5 — Delivery is chained behind an unmerged feature branch, and the cross-line catalog is unplanned (§6 P0/P3, §7)
**[verified]** All five baseline SHAs exist locally but sit on no remote branch;
77bddb47 is 42 commits past `origin/main` (`support/mc1.21.1` is at 0390908b).
#304 is a live boot failure; the fix touches common store code and build files
that are orthogonal to the YAML program. Meanwhile the catalog
(`config/lines/classification.json`, `tools/compat/ci.py`) classifies
`common/build.gradle`, `SqliteLodStore.java`, `LodStores.java`, the common store
tests, `THIRD-PARTY-NOTICES` and `check_artifacts.py` as **identical** across
five lines, and each loader's `build.gradle`, `release_check.py`,
`ReleaseWorkflowContractTest`, `test-server.sh` and `release.yml` as **adapted**
with one reviewed blob per line; new unclassified paths fail the check.
**Fix:** implement on the support-branch heads (1.21.1 NeoForge first), rebase
YAML over it; drop the SHA list. Add to P3: classify the new files (bridge source
set, generator, descriptor, runtime tests), five reviewed blobs per adapted file,
a `port-batch.json` whose `final_shared_invariants` names the new common files,
`source-refs.json` update, and `tools/compat/ci.py` as an explicit P4 gate.

## MEDIUM

### M1 — The bridge "defined from verified resource bytes" is not what the probe proved (§1, §3.1)
**[verified]** `Probe.java` puts `Bridge.class` on the URL path as a second
*directory* URL; it never calls `defineClass`, and a plain `URLClassLoader` has
no public one. Use a tiny `URLClassLoader` subclass calling the protected
`defineClass` once. `isDriverAllowed` resolves the driver name through the
bridge's loader, so deregistration still passes. The bridge must force
`org.sqlite.JDBC` initialization BEFORE `getDrivers()`/deregister, otherwise the
first `createConnection` runs `<clinit>`, re-registers, and pins the loader.
Acceptance: re-run the probe with the bytes-defined bridge; zero registrations
remain; a phantom reference to the loader clears after close.

### M2 — Global `org.sqlite.tmpdir` pinned to the first world + per-runtime extraction into worlds (§3.2, §3.3)
`SqliteLodStore.java:378` sets the property only when null. Today one native
loads per JVM, so a stale value is harmless; after the change every runtime
re-extracts into that directory: world B's natives land in world A's `lss-lod`,
and if A was deleted `SQLiteJDBCLoader` falls through to
`NativeLibraryNotFoundException` and B's store is off for the session. A 5–14 MB
capsule inside every singleplayer save also bloats backups, and on Windows the
open jar blocks "Delete World" on a running integrated server.
**Fix:** extract capsule and natives to one per-install, content-addressed
directory (e.g. `<gameDir>/.lss/sqlite-runtime/`), not the world; set `tmpdir`
only when absent, remember that LSS set it, and re-point when stale. noexec
remains an existing mode (natives already extract today), not a new one.
Acceptance: open world A, delete it, open world B → B's store works.

### M3 — `URLClassLoader.close()` does not release the extracted jar on Windows (§3.4)
The driver's `VersionHolder` opens `pom.properties` via `getResource(...).openStream()`
with default caching and never closes it; that handle lives in `JarFileFactory`'s
cache, which `close()` does not touch. Treat the extracted jar as locked until
JVM exit: quarantine by writing a new digest-named file, never rename/delete in
place (fits M2's per-install dir). Acceptance: Windows close/reopen test asserting
rename behaviour.

### M4 — Ship the slim capsule, not the stock jar (§3.1)
`fabric/build/store-jars/sqlite-jdbc-slim.jar` (4,981,674 B, 8 natives,
deterministic 1980 timestamps) already ships nested on Fabric, Paper flat-shades
the same 8, and `_check_sqlite_natives` pins exactly those. A slim capsule keeps
Fabric/Paper flat in size and shrinks NeoForge (23.2 MB today) by ~9 MB. The
descriptor records the slim digest plus the stock provenance SHA. Note the plan
mislabels 4,981,674 B as "the Fabric slim jar" (that is the nested driver jar;
the Fabric jar is 8,759,112 B), and "trimming without changing bytes" (§3.1) is
impossible: trimming is a new capsule identity with a constant bump.

### M5 — The #306 comparison omits the decisive facts, and P0 is under-specified (§1, §6 P0)
#306's REQUIRED Modrinth dependency auto-installs the library mod into
GriefLogger installs; the library and GriefLogger's flat copy conflict with each
other (GriefLogger #181 is open), so #306 converts LSS-vs-GriefLogger into
library-vs-GriefLogger. #306 also moves LSS to driver 3.53.2.0 while it compiles
against 3.49.1.0. Isolation is the only approach that boots both reported stacks
with a working store — say so. P0 stacks: LSS + GriefLogger 1.2.10 (dedicated
server only; GriefLogger is server-only) and LSS + library mod; never stage all
three; drop "Aeroworks where obtainable" (it is only a library consumer). Name
the harness: `test-server.sh run-neoforge` (borrow #306's `LSS_NEO_SQLITE` knob),
`LSSNeoGameTests.storeActiveOnFreshWorld` (runs off class dirs — the capsule must
reach `sourceSets.main` resources via the generator task, and the zstd/YAML
`additionalRuntimeClasspath` rows must stay), `/lsslod store status` `state=`.
Observable: served deposit ≥1 row → restart → hits > 0, `state=ok`.

### M6 — Minimum credible gate versus gold-plating (§5, §6 P4)
Minimum: (a) forked no-ambient common tests (two private loaders + ambient; warm
store keeps rows; failure preserves DB); (b) packaged 1.21.1 NeoForge dedicated
boot with both M5 stacks + deposit/read/reopen; (c) release_check pins on all
three loaders; (d) ONE headless Windows JVM run of the common test jar (the DLL
is the only native with zero Linux evidence). Record as later/optional: macOS/ARM
execution, Folia smoke (store code unchanged), Paper foreign-plugin both load
orders, the Neo-Voxy/XMMP re-run (zstd pins cover it), "check all 30 jars" as a
manual step, per-line Fabric client gametests.

### M7 — Operator story, diagnostics and the §4 table (§3.3, §4, §5)
Extraction/bootstrap failure must route into `LodStores.createOrNull`'s existing
degrade (`store=unavailable`, once-WARN naming path + cause). Add a
`driver=private/3.49.1.0` token to `store status`. Forward the driver's JUL
records during init (native-load failures are otherwise swallowed; only the tried
paths reach the final exception). "No SQLite in release dependency declarations"
has no enforcer: add an absence assertion to `ReleaseWorkflowContractTest` on
all lines and a no-`sqlite_jdbc` TOML rule. §4 omits `THIRD-PARTY-NOTICES:6-9`
(becomes false on all three loaders), `per-version-surfaces.md:26-28`,
`fabric.mod.json` `jars`, the slim keep-rows and Paper `storeNativeKeep` rows,
compat profile `mc262-native-smoke-client.json:328` (records the Fabric dev
sqlite input), and that Paper's own bundled sqlite-jdbc
(`mc262-folia-server-feasibility.json:1015`) is the ambient copy on Paper.
`SoakStoreDowngrade` is in `xplat/` (NeoForge compiles it too), hard-codes
`<brand>-lod` instead of `LodStores.brandedStoreDir`.

## LOW

- **L1 (§3.3)** Java 25 prints the JEP 472 restricted-method warning once per
  runtime unnamed module (`probe-java25.log`); a future deny default blocks
  `System.load`. Document `--enable-native-access=ALL-UNNAMED`.
- **L2 (§3.2 step 4)** Assert `ModuleLayer.boot()` has `java.sql` and
  `java.logging` at bootstrap (NeoForge launches from a named module;
  `JDBC.<clinit>` builds a JUL logger unconditionally).
- **L3 (§3.3)** An operator `org.sqlite.lib.path` binds a foreign native to our
  3.49.1.0 classes: check `sqlite_version()` against the descriptor and warn.
- **L4 (§3.4)** Native copies accumulate per reopen (`deleteOnExit`; Windows
  DLLs are cleaned next boot) — test the file count, note it in docs. The size
  cap counts logical pages only; capsule + natives are uncounted — say so.
- **L5 (§7)** "Same verified mechanism on all lines" is unfalsifiable as
  written: define it as identical common bytes + per-line release_check pins +
  the two named stacks.

## Verified as correct

- Platform-parent `URLClassLoader` sees only `java.*`/`javax.sql`/JUL; the
  driver's SLF4J use is a guarded `Class.forName` fallback; nothing from
  `sun.misc`/`jdk.unsupported`/`java.desktop`; Multi-Release `module-info` is
  inert under a URLClassLoader; the only TCCL use is the unused `:resource:`
  URL branch; DriverManager's service scan cannot leak drivers across loaders;
  two differently named native copies coexist (rule keyed by path; 3 copies
  proven on Linux); a bridge defined in the private loader may deregister its own
  driver. **[verified]** the JNI symbol facts and unique native naming.
- FML reads only `META-INF/jarjar/metadata.json` and root services; Knot serves
  the nested common jar's resources; Paper's `mojang` mapping and Shadow leave
  `.bin` resources alone — so the opaque resource is invisible to all three
  loaders' scanners (real packaged boots still required, as P0 says).
- The store's entire `org.sqlite` surface is the two `SQLiteDataSource` sites
  plus `setUrl` and the `tmpdir` string; every pragma is plain SQL;
  `instanceof SQLException` works across loaders. **[verified]** `deleteDbFiles`
  touches only `store.db`/`-wal`/`-shm`; invalidate-all is the in-DB `DropAll`.
- Brand-dir adoption (`brandedStoreDir`) and the YAML-era hot policy adoption
  (`updatePolicy`, `RequestProcessingService.java:699`) need no second runtime.
- Issue/PR states, library-mod version coverage (no 26.2), stock jar size and
  SHA-256, probe results on Java 21/25, and the P3 line matrix all check out.

## Recommendation

Proceed with classloader isolation and supersede #306 (do not merge it even as a
stopgap — its required Modrinth dependency spreads the library mod into
GriefLogger installs). Implement on the support-branch heads with 1.21.1 NeoForge
first, the slim capsule, per-install extraction, the H1 runtime ownership model,
the H2 narrowed recreate path, and the M6 gate; everything else in P4 becomes a
follow-up ledger entry. Fold H1–H5 and M1–M5 into the plan before P1 begins.
