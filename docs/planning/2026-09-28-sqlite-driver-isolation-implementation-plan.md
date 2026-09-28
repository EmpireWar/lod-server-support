# Private SQLite driver isolation implementation plan

Date: 2026-09-28. Status: **proposed; implementation is not started**.

Requested outcome: resolve LSS's SQLite package collisions before the next release,
using a privately loaded bundled driver instead of relying on a shared library mod.
This plan follows the completed YAML settings implementation. It covers all five
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

### 3.1 Packaging and ownership

Common owns exactly one driver resource, for example
`dev/vox/lss/internal/sqlite/sqlite-jdbc.jar.bin`, plus a generated descriptor
containing coordinate, version, byte length and SHA-256. Use the **stock upstream
jar bytes** initially; the resource is not a mod, Jar-in-Jar entry or flat classes.
The `.bin` suffix and absence from loader descriptors make the intent explicit.
Prove that real loader discovery ignores it rather than assuming this from its name.

The stock jar is 14,317,659 bytes; today's Fabric slim jar is 4,981,674 bytes.
Accept approximately 9.3 MB additional uncompressed payload on Fabric/Paper as a
deliberate simplification. NeoForge already carries the stock SQLite jar. Record
actual final archive sizes. Retain upstream license/native resources and notices;
extra bundled platforms do not expand our support commitment. Native trimming can
be reconsidered separately after correctness, without changing Java/native bytes.

Add a small Java-21 child-only bridge, compiled as a separate common source set.
Package its class bytes as an opaque resource as well. It must not be loadable as
an ordinary LSS class or acquire the host plugin/mod loader as its defining loader.
The private URLClassLoader defines this one helper from verified resource bytes;
all other non-JDK classes come solely from the verified driver jar.

The bridge creates/configures SQLiteDataSource and removes its own DriverManager
registration. All public boundary arguments/results are JDK types: String, Path
converted to a URL string, DataSource, Connection and SQLException. No SQLite
type, exception cast, callback or class literal crosses into common/platform code.
No arbitrary child-first loader, package allowlist framework or reflective module
opening is needed. Keep the bridge narrow and test its classloader explicitly.

### 3.2 Extraction and bootstrap

`SqliteDriverRuntime` in common provides the narrow facade:

1. Locate resources relative to the defining LSS class and verify their descriptor.
2. Extract the driver beneath the adopted store directory, e.g.
   `<store>/.sqlite-runtime/sqlite-jdbc-<version>-<sha256>.jar`.
3. Reuse an existing file only after digest/size verification. A mismatched entry
   must never execute. Stage to a unique same-directory temp file and install
   create-only; concurrent extraction must verify the winner. Preserve unrelated
   files. Corruption recovery may quarantine only the owned cache entry.
4. Construct the private platform-parent loader and load the child bridge. Assert
   the driver's defining loader, unnamed module and code-source jar match intent.
5. Initialize JDBC and immediately deregister only driver instances defined by this
   loader, from code also defined by this loader. Cleanup must run on initialization
   failure too. Never deregister another plugin's driver. Do not install a forwarding
   JDBC driver or change the caller's thread context classloader.
6. Open/close a private in-memory connection to establish native availability before
   any existing-store corruption/rebuild path is entered.
7. Supply DataSources/connections for the existing writer and reader setup code.
   Their pragmas, connection-thread ownership and SQL remain unchanged.

Avoid caching reflective Method/Constructor objects in process-global registries.
Within the runtime, resolve the small bridge API once rather than reflecting per
row or statement. Reflection is on initialization/connection creation only.

### 3.3 Native properties and failure boundaries

Keep the existing operator compatibility policy for `org.sqlite.tmpdir`: preserve
an explicit value; when absent, establish the existing store-directory fallback
before private native initialization. Do not temporarily set/restore that property
per connection or loader; unrelated drivers do not synchronize on our lock.
Do not overwrite `org.sqlite.lib.path` or `org.sqlite.lib.name`.

This deliberately retains an existing JVM-global surface rather than claiming
native-property isolation. Explicit overrides, unwritable directories and noexec
mounts need tests and truthful diagnostics. A writable world directory does not
prove native execution is allowed. P0 must establish that ordinary coexisting
drivers use separate extracted native files without new global-property churn.
If robust isolation requires driver-bytecode changes or native forking, revisit
the design instead of silently adding those mechanisms.

Bootstrap/extraction/class linkage errors are separate from corrupt database
errors. Place private runtime initialization before `openOrRecreateWriter()`;
preserve the database if this prerequisite fails. Unwrap reflection exceptions
without losing the actual cause, and bound repeated diagnostics. No changes to
world contents or live server configuration are necessary.

### 3.4 Lifetime and shutdown

One runtime per store/service lifetime, shared by all its writer and reader
connections; never one per connection, dimension or settings reload. Keep ownership
explicit on the store, with no JVM-global singleton retaining plugin classloaders.

- Failed construction closes any connection, bridge and loader it acquired.
- Normal shutdown drains/stops the batcher, closes statements and all reader/writer
  connections, then closes the loader. Remove references retained by reader-thread
  locals through the existing pool lifecycle or an explicit owner cleanup path.
- The existing bounded shutdown can time out while the batcher is still alive.
  In that case do **not** close its runtime underneath it: defer final cleanup to
  worker completion, exactly once. Test this path and reader/open shutdown races.
- Closing URLClassLoader releases archive handles; it does not promise immediate
  native unloading. Do not delete loaded DLLs or require deterministic GC. Keep
  immutable extracted jars reusable; avoid a new background cache-pruning service.
- Reopening an integrated-server world or re-enabling a plugin must produce a valid
  runtime without stale registration, old-world ownership or version confusion.

## 4. Build and integration changes

| Area | Planned change |
| --- | --- |
| `common/build.gradle` | Add a non-transitive, resolution-only private driver configuration; generate descriptor/resource and child-only bridge bytes for production and dev/test resources. Remove ordinary production compile-time SQLite linkage outside the bridge. |
| `SqliteLodStore` / `LodStores` | Own/bootstrap the private runtime; replace writer/reader construction; protect existing DBs on runtime failure; close safely; concise origin/version/failure diagnostics. |
| Fabric | Remove SQLite from `storeDeps`, normal dev runtime and `fabric.mod.json` jars. Keep zstd's existing slim nested mod. Common carries the opaque SQLite resource. |
| NeoForge | Remove SQLite from `implementation` and `jarJarStore`, generated jarJar metadata and any SQLite dev classpath workaround. Keep zstd stock Jar-in-Jar and its range/identity checks. |
| Paper/Folia | Remove SQLite from the ordinary runtime/shadow dependency graph. Common carries the opaque resource; outer plugin exposes no SQLite package or JDBC provider. Preserve zstd packaging. |
| Soaks/tools/tests | Move `SoakStoreDowngrade` off ambient DriverManager; explicitly scope its private runtime. Test fixture SQL may use a separate driver only where intentional, never to supply a missing production dependency. |
| LSS/VSS repackaging | Same private resource/bridge bytes and effective JDBC behavior; preserve local brand/path adoption. No wire differences or new external dependency. |
| Catalogs/docs | Update packaging facts, surfaces, runtime input identities, references to no-relocate/Jar-in-Jar, native notices and operator troubleshooting. Keep historical reports historical. |

Dev runs must exercise the same opaque-resource route. A correct release jar with
an accidental ambient dependency in tests is not sufficient. In particular retain
the YAML parser's independent 1.21.1 NeoForge dev setup; removing SQLite plumbing
must not remove that unrelated fix.

## 5. Release checks and meaningful tests

### Packaging checks on every final artifact

- Exactly one private driver capsule and expected child bridge per product; verify
  descriptor length/digest against the actual bytes, version, required natives and
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
- Keep LSS/VSS pair identity, package isolation and all existing release guarantees.

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

The broad suites may retain an ambient driver for fixture preparation, but the
dedicated packaging/runtime tests must fork a JVM without it. Child-only bridge
visibility and absence of game/loader dependencies must be executable assertions.

## 6. Implementation sequence and acceptance gates

### P0 — packaged 1.21.1 NeoForge feasibility

Start from the completed YAML branch, not PR #306. Preserve exact candidate,
dependency and fixture hashes. In a private disposable rig:

- Reproduce #304 with the released LSS jar and each independently valid minimal
  collider stack: GriefLogger 1.2.10 with its real dependencies; the Minecraft
  SQLite JDBC mod; and the relevant reported Aeroworks setup where obtainable.
- Prove each third-party stack boots without LSS. If two foreign mods conflict
  with each other, record that independently rather than making LSS's gate
  impossible or mislabeling it as fixed.
- Boot the candidate as a real final-packaged NeoForge mod, without an external
  SQLite mod, then alongside each valid collider stack. Verify module discovery,
  private driver origin and **actual LOD-store deposit/read/reopen**. A boot that
  silently disables the store is a failure, not a successful isolation test.
- Repeat the existing Neo-Voxy/XMMP combination to guard zstd's distinct fix.
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
Suggested baselines: main `77bddb47`, 1.21.1 `aeef2a83`, 1.21.10 `f21ccb4f`,
1.21.11 `7c5f18c0`, 26.1 `bd8d687a`; confirm newer work before starting.

### P4 — complete validation and review

- All five: full build/JUnit/server game tests, explicit Fabric client game tests
  where registered, NeoForge smoke, `vssJars`, release/artifact checks, catalog
  render/validate and exact cross-line classification. Check all 30 jars.
- Native 1.21.1 NeoForge: P0 collider matrix, client boot without store initialization,
  dedicated server with real store IO, both branded artifacts (one at a time).
- Main Fabric and Paper: store-enabled dedicated boot, served-row deposit, warm
  restart and reuse. Paper additionally gets a deliberately foreign SQLite plugin
  using a different version, checked in both load orders; both plugins perform SQL.
- Main Folia: representative store/reload/shutdown smoke preserving ownership and
  experimental designation. No 1.21.1 Folia task or nonexistent client test.
- Other three lines: representative packaged store-enabled startup/read/reopen on
  supported loaders; prove packaging/classloader integration, not new renderer
  certification.
- Real Windows Java 21 and 25: isolated-driver SQL, coexistence and close/reopen
  checks with native DLLs and spaces in paths. This can run headlessly through
  PowerShell; do not open or operate the Windows Minecraft desktop. WSL/DrvFS
  evidence alone is not Windows JVM evidence.
- macOS and ARM variants: artifact/native-resource checks everywhere; execute
  native smoke on available matching runners. Record unavailable runtime coverage
  explicitly, with no expanded platform support claim.
- Independent implementation review should focus on classloader/native ownership,
  lifecycle/failure containment, and artifact/support-line completeness before
  release. Review execution is a later implementation gate, not claimed here.

Serialize heavy builds and rigs under the shared lock. Preserve first failures,
exact hashes and owned process identities. Private WSL displays only; no changes
to the live Modrinth server, normal test server, personal Prism or user worlds are
needed for this implementation gate.

### P5 — release preparation and #306 disposition

Update the final ledger with actual results and any residual platform limits.
Once isolation passes, recommend superseding #306 rather than shipping a temporary
external dependency and removing it again immediately. Preserve attribution and
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
