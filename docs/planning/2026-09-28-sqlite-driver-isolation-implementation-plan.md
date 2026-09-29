# Private SQLite driver isolation implementation plan

Date: 2026-09-28. Status: **implemented and validated**.

Completion: [implementation, review and validation ledger](../reviews/2026-09-28-sqlite-isolation-implementation.md). P0–P5 are complete; publication and external replies remain separate actions.

Requested outcome: resolve LSS's SQLite package collisions before the next release,
using a privately loaded bundled driver instead of relying on a shared library mod.
This fix starts independently on the release/support heads, then is integrated into
the completed YAML branches. It covers all five
maintained lines, Fabric, Paper/Folia where available, NeoForge, and both LSS/VSS
artifacts. Planning authorization does not authorize publication or issue replies.

## 1. Recommendation and established facts

Adopt classloader isolation, subject to the packaged NeoForge proof in P0. Keep
SQLite's Java packages and native binaries unchanged. Package the driver as an
opaque resource and load it with a private URLClassLoader whose parent is the JDK
platform loader. LSS communicates with it using JDK JDBC interfaces.

This removes LSS's contribution to shared SQLite package resolution and shared
driver-version selection. It cannot repair a conflict entirely between two other
mods, nor guarantee compatibility with arbitrary JVM-wide native-library overrides.
Do not promise that this solves every possible native dependency collision.

### Reports and alternatives checked

- [LSS #304](https://github.com/VoX/lod-server-support/issues/304) remains open.
  Reports cover flat SQLite in GriefLogger and a separate Minecraft SQLite JDBC
  mod alongside LSS's Jar-in-Jar library. Names such as Aeroworks or Cubes Without
  Borders in a resolution error can name a module reading the conflicting exports;
  the message alone does not establish which mod supplied SQLite.
- [PR #306](https://github.com/VoX/lod-server-support/pull/306) is open, unmerged,
  targeting `support/mc1.21.1`, head `9eb557be02413fd50c29e8d1b262d88546aaa03f`.
  It removes NeoForge's bundled SQLite, declares optional `sqlite_jdbc` in TOML,
  adds a required Modrinth dependency, and supplies a store-disabled fallback.
  It is an alternative to this plan, not an already-merged dependency to remove.
- [GriefLogger #181](https://github.com/DAQEM/GriefLogger/issues/181) and
  [its PR #182](https://github.com/DAQEM/GriefLogger/pull/182) remain open. Changing
  another mod to Jar-in-Jar helps that combination but does not establish a general
  solution for independently packaged copies.
- [Minecraft SQLite JDBC](https://modrinth.com/mod/minecraft-sqlite-jdbc) currently
  lists 1.21.1, 1.21.10, 1.21.11 and 26.1.2, but not 26.2. Thus the claim that
  *all four* other lines cannot use it is too broad. Avoiding external coverage and
  version coupling is still a reason to prefer the private-driver approach.
- [LSS #275](https://github.com/VoX/lod-server-support/issues/275) concerns zstd-jni,
  whose NeoForge Jar-in-Jar correction has shipped. Preserve and regression-test
  that fix. Isolating zstd is outside this SQLite change: its API spans compression
  paths and is not the same two-connection-factory change.

### Technical findings and planning probe

[JNI names encode Java class/method names](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html#compiling-loading-and-linking-native-methods).
Relocating SQLite Java classes alone is unsuitable for the stock native binaries.
No native rebuild, package relocation, or upstream driver fork is proposed.

The [platform loader and unnamed-module API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)
provide the required boundary. Use the platform parent, not the application,
Minecraft, plugin or null/bootstrap loader. JDK SQL types must be shared, while
ambient mod/plugin SQLite types must not resolve through our loader.

Inspection of the pinned driver found important details missing from the sketch:

1. [SQLite 3.49.1.0's native loader](https://github.com/xerial/sqlite-jdbc/blob/3.49.1.0/src/main/java/org/sqlite/SQLiteJDBCLoader.java)
   normally extracts UUID-named native copies. Its temp and explicit native-library
   overrides are nevertheless JVM system properties, not classloader-local state.
2. [SQLiteDataSource](https://github.com/xerial/sqlite-jdbc/blob/3.49.1.0/src/main/java/org/sqlite/SQLiteDataSource.java)
   calls [JDBC](https://github.com/xerial/sqlite-jdbc/blob/3.49.1.0/src/main/java/org/sqlite/JDBC.java),
   whose static initializer registers a driver globally. Merely closing a URL loader
   does not remove that registration. DriverManager access is caller-sensitive.
3. The pinned driver's [logger factory](https://github.com/xerial/sqlite-jdbc/blob/3.49.1.0/src/main/java/org/sqlite/util/LoggerFactory.java)
   falls back to JDK logging without SLF4J. We need no logging library in the private
   loader and no parent-loader exception for SLF4J.
4. There are two production `new SQLiteDataSource()` sites in `SqliteLodStore`,
   but `SoakStoreDowngrade` also uses ambient DriverManager. Tests and dev runtime
   dependency declarations need deliberate conversion; changing two constructors
   alone is insufficient.

A scratch Java probe passed on Java 21 and 25 under WSL Linux x86_64 using stock
`org.xerial:sqlite-jdbc:3.49.1.0`, SHA-256
`5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291`:

- An application-loaded driver and two independent platform-parent loaders each
  created a DB, enabled WAL, inserted/read a row and reported SQLite 3.49.1.
- Driver classes had distinct identities; isolated classes belonged to unnamed
  modules. Three distinct native `.so` copies existed simultaneously.
- Isolated connections worked without SLF4J. Deregistration from the application
  caller failed as expected; a helper defined inside each isolated loader removed
  exactly its own driver. Direct SQL continued working afterward.

Evidence: `/tmp/lss-sqlite-isolation-planning/Probe.java`, `Bridge.java`,
`probe-java21.log`, `probe-java25.log`. This is a feasibility probe, **not** a
Minecraft/module-discovery, Paper, Windows or packaged-artifact acceptance result.
No game processes or existing databases were used.

## 2. Scope and invariants

- Keep SQLite at 3.49.1.0 initially. Do not combine a driver upgrade with isolation.
- Preserve store schema, metadata, row encoding, WAL/pragmas, worker ownership,
  migration, eviction, hot policy adoption and LSS/VSS directory adoption.
- No network dependency resolution at runtime and no new user-facing setting.
- Initialize SQLite lazily when a store is requested. Client-only and store-disabled
  operation must not extract or initialize a private runtime.
- Missing/corrupt driver resources or unsupported native loading disable the LOD
  store with useful diagnostics; ordinary terrain service must continue.
- Driver/bootstrap failure must never cause deletion or recreation of an existing
  `store.db`, WAL or SHM file.
- Do not load a different mod's SQLite as a fallback. Isolation failure is visible;
  silently returning to ambient class lookup defeats the fix.
- Preserve the current promised native/platform matrix and all wire/brand contracts.

## 3. Proposed runtime design

### 3.1 Packaging and independent identity

Common owns one opaque capsule at
`dev/vox/lss/internal/jdbc/sqlite-jdbc.jar.bin`, a descriptor, and a Java-21
child bridge class packaged as `.class.bin`. Keep the existing eight native
platforms (Linux and Linux-Musl, Windows, macOS, each x86_64/aarch64); preserve
upstream classes, services, module metadata and licenses INSIDE the capsule.
Use deterministic trimming rather than the 14 MB stock payload. Pin both the
upstream SHA above and the resulting slim SHA in checked-in build/checker data.
A generated descriptor matching its own input is insufficient supply-chain proof.
No SQLite class, service or module escapes onto a normal host classpath.

Compile the child bridge separately. A narrow platform-parent URLClassLoader
subclass defines its verified raw bytes; the feasibility probe's second directory
URL did NOT prove this part. The bridge exposes only JDK SQL types, forces JDBC
initialization, then deregisters drivers defined by its own loader, including on
failed initialization. Do not register a forwarding driver or change the TCCL.

### 3.2 Extraction and runtime lifetime

Use one lazily initialized runtime per LSS defining classloader, reused across all
stores, world closes/reopens and settings reloads. Never load a second native copy
for a reopened store. Successfully initialized loaders remain available for that
classloader's lifetime; do not promise immediate native unloading or archive handle
release. The pinned driver's cached resource stream can keep the jar locked on
Windows until process exit. Failed bootstrap closes its loader after child cleanup.

Extract immutable, hash-addressed jars into an installation-level cache, independent
of world directories (including a deleted first world). Verify size/digest before
reuse; stage uniquely and install create-only. A corrupt cache entry must never
execute and must not be replaced underneath a loaded loader. Keep an explicit
`org.sqlite.tmpdir`, `org.sqlite.lib.path` or `org.sqlite.lib.name`; when tmpdir is
absent establish an installation-level native directory once, never set/restore
properties around calls. No network requests or new settings are needed.

### 3.3 Store ownership and deferred close

Acquire exclusive ownership of the canonical store directory BEFORE opening the
real database or any probe. Use a permanent sidecar FileLock for cross-process
exclusion and a JVM-wide, JDK-only claim for classloader/plugin-reload exclusion.
The JVM claim must happen before opening another descriptor for that lock file:
closing a duplicate descriptor can release process-wide POSIX locks. Do not keep
plugin objects in a JVM-global registry on normal lifecycle paths, and never unlink
the locked sidecar. If native close fails, an exceptional daemon retains the lease
and its defining loader until JVM exit. It has no inherited thread locals and a
null context loader; interruption cannot release ownership. This deliberate
failure-only retention prevents channel cleaners from releasing the OS lock after
a failed store is discarded. Restart is required; a concurrent shutdown hook must
not close the lock while SQL users can still run.

Retain ownership until all native database users finish and every connection closes.
Track reader admission around all SQL read entrypoints as well as batcher lifetime;
shutdown denies new admissions. If bounded shutdown expires, retain the writer,
runtime and directory claim; the last completing worker/reader performs final
cleanup exactly once. A new service/plugin loader must fail closed while the old
owner is alive. Uncertain connection-close failure retains ownership. Constructor
failure closes acquired connections before releasing ownership. Test same-path
aliases, repeated shutdown, blocked readers/writer, failed construction and reopen.

### 3.4 Failure classification and data preservation

Before opening an existing store, create a disposable file-backed probe in the
SAME STORE FILESYSTEM. Assert `journal_mode=WAL` actually succeeds and two
connections can write/read committed data. Close its handles before deleting only
its own temporary files. An in-memory/bootstrap probe is not evidence of WAL or
filesystem support. Probe failure leaves existing DB/WAL/SHM bytes intact.

Narrow recovery in `openOrRecreateWriter`: rebuild only for SQLite primary error
codes CORRUPT (11), NOTADB (26), or the existing explicit metadata DROP verdict.
I/O, permissions, locks, native/linkage and arbitrary setup errors preserve the
store and disable acceleration honestly. Unwrap reflective causes; report private
driver origin/version and a useful failure cause.

Likewise `get`/`getFrame` may remove a damaged row only for explicit row integrity
or codec corruption, never merely because an exception is not SQLException.
Linkage/native failures must not erase valid rows. Existing schema migrations,
registry permutation, eviction, metadata invalidation and worker ownership stay
unchanged. Preserve YAML policy adoption when forward-integrating.

## 4. Build and integration changes

| Area | Planned change |
| --- | --- |
| `common/build.gradle` | Add a non-transitive, resolution-only private driver configuration; generate descriptor/resource and child-only bridge bytes for production and dev/test resources. Remove ordinary production compile-time SQLite linkage outside the bridge. |
| `SqliteLodStore` / `LodStores` | Own/bootstrap the private runtime; replace writer/reader construction; protect existing DBs on runtime failure; close safely; concise origin/version/failure diagnostics. |
| Fabric | Remove SQLite from `storeDeps`, normal dev runtime and `fabric.mod.json` jars. Keep zstd's existing slim nested mod. Common carries the opaque SQLite resource. |
| NeoForge | Remove SQLite from `implementation` and `jarJarStore`, generated jarJar metadata and any SQLite dev classpath workaround. Keep zstd stock Jar-in-Jar and its range/identity checks. |
| Paper/Folia | Remove SQLite from the ordinary runtime/shadow dependency graph. Common carries the opaque resource; outer plugin exposes no SQLite package or JDBC provider. Preserve zstd packaging. |
| Soaks/tools/tests | Move `SoakStoreDowngrade` off ambient DriverManager; use the same private engine. Convert every ordinary SQL fixture to that engine, including helpers that edit an open store. |
| LSS/VSS repackaging | Same private resource/bridge bytes and effective JDBC behavior; preserve local brand/path adoption. No wire differences or new external dependency. |
| Catalogs/docs | Update packaging facts, surfaces, runtime input identities, references to no-relocate/Jar-in-Jar, native notices and operator troubleshooting. Keep historical reports historical. |

Dev runs must exercise the same opaque-resource route. A correct release jar with
an accidental ambient dependency in tests is not sufficient. In particular retain
the YAML parser's independent 1.21.1 NeoForge dev setup; removing SQLite plumbing
must not remove that unrelated fix.

## 5. Release checks and meaningful tests

### Packaging checks on every final artifact

- Exactly one private driver capsule and expected child bridge per product; verify
  descriptor length/digest AND the independent checked-in capsule pin against actual bytes, version, required natives and
  license. Walk Fabric's nested common jar and both shaded layouts explicitly.
- No outer/ordinary library `org/sqlite/**`, SQLite module declaration or SQLite
  provider entry. A JDBC service file **inside the opaque capsule** is permitted.
- No SQLite entry in Fabric `jars`, NeoForge jarJar metadata, normal runtime module
  inputs, or release dependency declarations. The capsule itself is allowed to
  retain upstream module-info and stock service files as inert inner contents.
- No accidental relocation of driver/bridge references; no test fixtures shipped.
- Preserve all zstd #275 checks; do not blanket-relax native or nested-jar checks.
- Negative fixtures must catch reintroduced flat classes, declared nested SQLite,
  duplicate/wrong capsules, altered digests, missing bridge/license/native, exposed
  bridge classes and accidental external `sqlite_jdbc` dependency requirements.
- Extend both recursive artifact walkers to inspect the exact opaque capsule path.
  Invert the old flat/nested SQLite pins on all three loaders, narrow Paper’s
  relocation heuristic to classes, and retain zstd checks unchanged.
- Extend Paper/NeoForge LSS/VSS identity to capsule, bridge and descriptor bytes,
  in addition to classes. Negative fixtures include modifying both descriptor and
  capsule together, omitted native/license, duplicate payload and forbidden metadata.
- Keep package isolation and all other release guarantees.

### Common/runtime regression tests

Use real SQLite SQL/WAL operations, not source-string assertions, for the core proof:

1. Isolated runtime works when SQLite is absent from the application classpath;
   a deliberately incompatible ambient copy cannot intercept it.
2. Two runtimes plus an ambient driver operate concurrently with distinct class
   identities, correct code sources, separate natives, and independent databases.
3. Driver cleanup removes only this loader's registration; connections continue
   working afterward; initialization failure leaves no registered private driver.
4. Existing warm store opens with rows preserved. Startup/native failure does not
   delete DB/WAL/SHM; unsupported platforms retain store-disabled fallback.
5. Concurrent extraction, corrupt/truncated cache, changed digest, spaces/non-ASCII
   paths, permission failure and temp cleanup have concrete IO assertions.
6. Reader setup failure, shutdown/open races, failed constructor, slow batcher,
   repeated close and service reopen exercise connection and archive ownership.
7. Existing store migration, frame serving, read errors, backfill, registry
   permutation and YAML reload/pending-policy tests stay meaningful and pass.

Remove ambient SQLite from common/Fabric test and dev classpaths. Every ordinary
fixture uses the store runtime; a deliberately ambient driver runs only in a
separate coexistence JVM against a DIFFERENT database. Fork no-ambient tests and
assert child-only bridge visibility, shared JDK types and absence of game/loader
dependencies. Platform `check` already depends on `:common:test`; preserve that gate.

## 6. Implementation sequence and acceptance gates

### P0 — packaged 1.21.1 NeoForge feasibility

Start from release/support heads, not the YAML stack or PR #306. Preserve exact candidate,
dependency and fixture hashes. In a private disposable rig:

- Reproduce #304 with the released LSS jar and each independently valid minimal
  collider stack: GriefLogger 1.2.10 with its real dependencies; the Minecraft
  SQLite JDBC mod (separate valid stacks, never the two colliders combined).
- Prove each third-party stack boots without LSS. If two foreign mods conflict
  with each other, record that independently rather than making LSS's gate
  impossible or mislabeling it as fixed.
- Boot the candidate as a real final-packaged NeoForge mod, without an external
  SQLite mod, then alongside each valid collider stack. Verify module discovery,
  private driver origin and **actual LOD-store deposit/read/reopen**. A boot that
  silently disables the store is a failure, not a successful isolation test.
- Preserve the existing Neo-Voxy/XMMP zstd packaging pins. A new live render-stack
  run is optional follow-up, as recommended by review M6: no zstd bytes or discovery
  mechanism change in this patch.
- Compare PR #306's no-library/with-library behavior and dependency assumptions
  against these results. Keep #306 unmerged while evaluating the replacement.

**Go/no-go:** ordinary stock driver/native loading, opaque discovery, contained
failure and owned cleanup all work. If a Java/native fork or broad reflective
framework becomes necessary, stop expansion and revise this plan.

### P1 — common runtime and store lifecycle

Implement sections 3 and 5 in small reviewable changes. Establish real common
regressions before replacing every packaging path. Runtime bootstrap and database
recovery must remain separate. Capture initialization cost once; do not start an
unrelated multi-hour performance campaign for connection-factory changes.

### P2 — packaging and tooling on 1.21.1 and main

Apply the table in section 4. Update final-artifact checker fixtures and dev launches
together. Validate the packaged route with no ambient SQLite, then all LSS/VSS
artifacts. Native tests use the actual final jars, not development classpaths.

### P3 — remaining support lines

Port shared code identically, preserving line-specific Gradle/loader seams:

| Line | Platform Java | Fabric client game test | Paper family | NeoForge shipping |
| --- | ---: | --- | --- | --- |
| 1.21.1 | 21 | Unavailable | Paper/Purpur | Yes |
| 1.21.10 | 21 | Available | Paper/Purpur | No; build remains maintained |
| 1.21.11 | 21 | Available | Paper/Purpur/Folia | No; build remains maintained |
| 26.1, targeting 26.1.2 | 25 | Available | Paper/Purpur/Folia | Yes |
| 26.2 | 25 | Available | Paper/Purpur/Folia | Yes |

Common and the bridge emit Java 21 everywhere. Validate actual catalogs after ports;
do not overwrite line facts from an older banner or #306's one-line implementation.
Release-head baselines (the current 1.21.11 and 26.1 branches use the `-v0.14`
suffix; the unsuffixed branches are historical v0.8 lines):

| Line | Exact base |
| --- | --- |
| 1.21.1 | `0390908b4e7857e0ec11fbd329364a5376582349` |
| 1.21.10 | `4d4f630078b84ec51f2564fd4f55843c4a14b3cc` |
| 1.21.11 | `e2aed3953f2a13d3fe9b2e9c978ef8973220c245` |
| 26.1 | `decdeceedc323749304c4dcb73255de07544b6f0` |
| 26.2 | `d4b415d5d84c2b272532e011669a265109db509c` |

Classify new files in `config/lines/classification.json`; update adapted blob pins,
exact cross-line source refs, port-batch provenance and rendered catalog data.
Run catalog CI against the five actual source commits. Forward-integrate the
independent commits into the five YAML branches, resolve deliberately, and repeat
catalog/policy checks there. The YAML overlays get all five loader builds,
platform JUnit suites, targeted common isolation/ownership/policy/settings tests,
and artifact checks; repeat Fabric/NeoForge server gametests on 1.21.1 and 26.2.
The complete general common/server matrix gates the independent release patches;
there is no second ten-branch general campaign. A wholesale YAML rebase is not a prerequisite.

### P4 — focused acceptance and independent review

- All five: common and platform JUnit, server gametests/NeoForge smoke, all three
  loader builds and VSS variants, release/artifact checks and catalog CI. Check
  all 30 final product jars. Renderer/client-gametest and broad performance/soak
  campaigns are not required by this connection/packaging change.
- Forked no-ambient SQL/WAL tests, independent-driver coexistence on separate DBs,
  ownership/shutdown races, failure data preservation and checker negative fixtures.
- Real final-packaged 1.21.1 NeoForge: candidate alone, GriefLogger 1.2.10 and its
  dependencies, and Minecraft SQLite JDBC separately; each foreign stack proven
  valid without LSS. Check actual store activity, deposit/read and restart reuse.
  A store-disabled boot fails this gate. Neo-Voxy/XMMP live re-testing is optional;
  its unchanged zstd packaging contracts remain required.
- Representative final-packaged Fabric and Paper: store enabled, deposit/read and
  warm reopen; brands additionally checked by resource/class equality and a VSS
  boot. No whole additional cross-line live matrix is required.
- Headless real Windows Java 21 and 25: packaged SQL/WAL, close/reopen and immutable
  extraction in paths containing spaces. Never open/operate the Windows desktop.
- All eight native resource variants are checked; report absent macOS/ARM execution
  coverage honestly, without expanding support claims.
- Independent implementation review covers runtime/native ownership, destructive
  recovery, tests, packaging/checker integrity and cross-line/YAML integration.

Serialize heavy builds and rigs under the shared harness lock, retain exact hashes,
first failures and owned process identities. Use disposable rigs; no personal Prism,
normal server/world or live Modrinth installation changes are part of this gate.

### P5 — release preparation and #306 disposition

Update the final ledger with actual results and any residual platform limits.
Once isolation passes, recommend superseding #306: its required Modrinth dependency
automatically brings the SQLite library mod into GriefLogger installs, where those
two foreign providers conflict independently. Do not ship a temporary
external dependency only to remove it immediately. Preserve attribution and
its verified reproductions/version-range lesson. If it merges meanwhile, explicitly
remove its TOML/Modrinth dependencies, driver-absence bootstrap hooks, library-mod
dev staging, toggles and obsolete install instructions on the affected line only.

Draft issue/PR updates distinguishing the already-offered #306 CI workaround from
the new self-contained candidate. Do not post, close, merge or release without the
corresponding user instruction. A later release note should state that SQLite
coexistence no longer requires an extra mod, existing stores are retained, and the
exact tested combinations; it must not claim fixes for unrelated third-party pairs.

## 7. Definition of done

The final LSS/VSS artifacts no longer expose SQLite to the host mod/plugin loader;
all maintained lines use the same verified private-driver mechanism; existing store
data and YAML reload behavior survive; actual reported valid collider stacks boot
**with functioning stores**; driver/connection/archive cleanup and failure paths
are covered; zstd packaging remains correct; required build/native gates and
independent review pass. No SQLite native fork, ambient fallback, external library
requirement or new configuration knob is introduced.

The runtime can remain small, but the complete work includes lifecycle, extraction,
packaging, tools and real coexistence tests. A fixed 150-line estimate is not an
appropriate completion criterion.

## Review disposition

The supplied [review](2026-09-28-sqlite-driver-isolation-review.md) motivated this
revision. All five high findings and the extraction/bridge/Windows/slimming
corrections are accepted. Two qualifications: common tests already gate platform
checks, and small automated all-artifact/Fabric/Paper checks remain necessary even
with the reduced live matrix. No issue reply, PR merge or release is authorized.

### Detailed supplied-review disposition

H1–H5 and M1–M5 are incorporated in the runtime, recovery, fixture, capsule and
independent-delivery requirements above. M6 narrows live coverage: no new
Neo-Voxy/XMMP renderer run, Folia load campaign, Paper foreign-plugin load-order
matrix or macOS/ARM execution is required. Automated all-line artifact checks
remain cheap and required. M7 adds private-driver status, startup failure cause,
bootstrap JUL forwarding, dependency absence pins, notice/surface updates and
brand-adopting benchmark paths. Historical compatibility records keep their
original dependency identities; new validation recipes remove ambient SQLite.
L1–L4 require operator native-access/cache documentation, JDK-module assertions,
foreign-native version warnings and reopen/native-count probes. L5 means identical
common source blobs, each line's real artifact checks, and the two named packaged
collider stacks; it does not claim unexecuted operating-system coverage.

The runtime review additionally requires the uncertain-close daemon described in
§3.3. Normal store close and reload create no global plugin root. A deterministic
classloader-GC/unload deadline is not a portable acceptance test; driver registry
cleanup and cross-process directory exclusion after GC are the enforced claims.
