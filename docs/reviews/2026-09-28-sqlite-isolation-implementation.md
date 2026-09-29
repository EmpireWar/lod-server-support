# SQLite isolation implementation and validation

Completed 2026-09-28. All P0–P5 gates passed on the independent release-head patches and their five YAML integrations. The changes remain local and unreleased. Exact build commits, artifact hashes and evidence identities are in the [machine-readable results](2026-09-28-sqlite-isolation-results.json).

## Changes and supplied-review disposition

The [supplied plan review](../planning/2026-09-28-sqlite-driver-isolation-review.md) is incorporated. SQLite remains upstream 3.49.1.0 with unchanged class/native bytes. No JNI fork, package relocation, ordinary JDBC dependency, external library-mod requirement, or ambient fallback was added. Zstd keeps its existing loader-specific packaging.

| Finding | Implementation and evidence |
| --- | --- |
| H1: independently loaded engines touching one database | `SqliteDriverRuntime` is shared for the lifetime of each defining LSS loader. `StoreDirectoryLease` claims the canonical directory before opening its permanent sidecar descriptor. Reader admission and batcher completion defer final cleanup. The tests cover a wedged writer, blocked reader close, duplicate classloader/process ownership, and abandoned uncertain ownership after GC. |
| H2: recreation after arbitrary startup failures | A two-connection, file-backed WAL probe precedes the real database. Recreation requires primary CORRUPT/NOTADB or the existing explicit metadata invalidation. WAL-failure tests compare exact DB/WAL/SHM bytes. Native read failures preserve valid rows. |
| H3: ambient drivers in store fixtures | All seven ordinary fixture/helper families and `SoakStoreDowngrade` use the private engine. SQLite is absent from the normal common/Fabric test and dev classpaths. Only a dedicated fork deliberately adds an ambient driver, on its own database. |
| H4: opaque packaging and inadequate release pins | An independent stock/capsule digest, exact capsule walker, native/license checks, outer-package/module/provider absence checks, real bridge class identity, and LSS/VSS resource equality cover all three loaders. Negative fixtures include multi-release classes, co-tampered descriptor/capsule pairs and a disguised test class in the bridge resource. |
| H5: YAML dependency and missing port metadata | Independent commits sit on current maintenance heads, then are forward-integrated into all five YAML branches. Classification, exact source references and reviewed port-batch blobs are refreshed, followed by explicit five-line checks. The current 1.21.11/26.1 maintenance branches have the `-v0.14` suffix; their unsuffixed v0.8 branches are historical. |
| M1–M5 | The bridge is defined from raw verified bytes; it initializes JDBC before deregistering only its own driver. Extraction is per installation and immutable by digest. The slim capsule retains eight native variants and upstream notices. Both collider stacks are tested separately; #306 remains open and unmerged. |
| M7 and L1–L5 | Startup diagnostics forward bootstrap JUL warnings temporarily and remove that handler afterward. Store status names the private driver. Native overrides are preserved and mismatched native versions warned about. Documentation covers Java 25 native access, cache lifetime/size and recovery. Module presence and native-count/world-switch tests provide concrete assertions. |

M6's bounded validation scope is adopted. No new renderer/performance campaign, Folia load test, Paper foreign-plugin load-order matrix, or macOS/ARM execution is claimed. The automated artifact checks still inspect every built LSS/VSS loader variant. Historical compatibility records retain their original dependency identities; the new private recipes remove ambient client SQLite inputs.

## Independent Astra reviews

Two Astra reviewers examined runtime/lifecycle and packaging/checker behavior, followed by focused re-reviews of the ports and YAML integration.

The runtime review found a reader-close race: removing a reader from the tracking list before its native close completed could release directory ownership too early. Reader exit now holds an admission permit through close; final cleanup owns the remaining connections. A real blocked-close regression covers this ordering.

It also found that a string ownership claim alone could not retain the operating-system lock after an uncertain native close: garbage collection could reclaim the channel. The exceptional close-failure path now creates one daemon retaining the lease until JVM exit. It inherits no thread locals, has a null context classloader and tolerates interruption. This deliberately retains the defining loader on uncertainty; successful shutdown creates no guardian. A shutdown hook was rejected because it could race other native shutdown work. The forked GC/second-process exclusion regression passes.

The packaging review identified multi-release/module visibility and opaque-bridge test-fixture bypasses. Both are now checked against actual classfile identity, with negative fixtures. The reviewer subsequently accepted the six 1.21.1 artifacts and all seven adapted-file patches across five lines. MC/JDK targets, shipping flags, Tier 3 availability and the FML4-only development dependency seam remain intact.

The YAML review confirmed that policy revisions, owner-thread adoption, cap enforcement, sweep rescheduling, backfill sequencing and cancellation still use the existing store. Relocated YAML resources and private SQLite resources coexist. Final reviews reported no remaining material findings; review-only passes did not themselves run builds.

## Confirmed native reproduction and fix

On Minecraft 1.21.1 / NeoForge 21.1.248:

| Stack | Result |
| --- | --- |
| GriefLogger 1.2.10 with its actual dependencies, without LSS | Clean dedicated-server boot and shutdown. |
| GriefLogger stack plus released LSS 0.15.1 | Reproduced `ResolutionException` involving SQLite. |
| Minecraft SQLite JDBC 3.53.2.0+2026-06-06, without LSS | Clean dedicated-server boot and shutdown. |
| SQLite library mod plus released LSS 0.15.1 | Reproduced `ResolutionException` involving SQLite. |
| New LSS alone | Private store active; real client disk delivery committed a row; server restarted; fresh client received identical content from the store. |
| New LSS plus GriefLogger stack | Same write/restart/read proof passed. |
| New LSS plus SQLite library mod | Same write/restart/read proof passed. |
| VSS variant alone | Same write/restart/read proof passed. |

Each candidate run has separate server boot logs, a healthy private-driver status after restart, actual v20 client receipts, independently inspected persisted data and complete owned cleanup. No mock transport or direct test deposit substitutes for the client request path. The two foreign SQLite providers were never combined into one claimed working stack.

Headless native Windows runs passed on Java 21 and Java 25, with spaces/non-ASCII paths, SQL/WAL, same-engine reopen, deleting world A before opening world B, and independent-loader ownership. Native-file counts stay unchanged on world reopen. Two defining LSS loaders produce two DLLs, as expected. Observed first file-backed bootstrap costs were 300 ms and 247 ms respectively; these single samples are diagnostics, not a performance comparison. No Windows game window or desktop input was used.

## Final build, test and port results

All listed tests finished with zero failures/errors. JUnit totals include the explicitly reported skips; the columns are suite executions, not a count of unique behaviors across platforms. The independent patches run the complete common/platform JUnit and server-gametest matrix. The YAML overlays run common isolation, ownership, store-policy and configuration tests, all platform JUnit, all loader/brand builds, and representative server gametests on 1.21.1/26.2. Fabric client gametests were excluded as specified in the revised plan.

### Full independent release-head matrix

| Minecraft line | Common JUnit | Fabric JUnit | Paper JUnit | NeoForge JUnit | Fabric / Neo server gametests | Artifact checks |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 1.21.1 | 802 | 1725 (4 skipped) | 548 | 23 | 79 / 8 | 6/6 |
| 1.21.10 | 801 | 1722 (6 skipped) | 548 | 19 | 81 / 8 | 6/6 |
| 1.21.11 | 801 | 1726 (4 skipped) | 548 | 23 | 81 / 8 | 6/6 |
| 26.1 | 801 | 1733 (4 skipped) | 548 | 23 | 80 / 8 | 6/6 |
| 26.2 | 802 | 1732 (4 skipped) | 550 | 23 | 80 / 8 | 6/6 |

### YAML integration matrix

| Minecraft line | Common JUnit | Fabric JUnit | Paper JUnit | NeoForge JUnit | Fabric / Neo server gametests | Artifact checks |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 1.21.1 | 154 | 1652 (4 skipped) | 536 | 23 | 78 / 8 | 6/6 |
| 1.21.10 | 154 | 1648 (6 skipped) | 536 | 19 | Not repeated | 6/6 |
| 1.21.11 | 154 | 1653 (4 skipped) | 536 | 23 | Not repeated | 6/6 |
| 26.1 | 154 | 1660 (4 skipped) | 536 | 23 | Not repeated | 6/6 |
| 26.2 | 154 | 1659 (4 skipped) | 538 | 23 | 79 / 8 | 6/6 |

Each of the 60 built artifacts passed the release checker and test-fixture exclusion checks; checker selftests passed on every line. Both families passed catalog validation/rendering, each line's candidate CI, and a separate complete five-ref shared/adapted-source check. The new SQLite runtime/bridge common sources are identical across lines; existing Minecraft-dependent common seams retain their approved adaptations. Source and catalog commit identities are retained in the JSON results; subsequent completion commits change documentation only.

The original independent fix branches are `fix/sqlite-isolation-mc<line>` under `lss-sqlite-lines/`; the integrated branches are `feat/yaml-settings-mc<line>` under `lss-yaml-lines/`, with 26.2 in `lss-settings-plan/`. No support branch merge or remote push was performed.

## Packaged native acceptance identities

| Actual stack | Retained run ID | Store write → server restart → fresh-client read |
| --- | --- | --- |
| 1.21.1 NeoForge, LSS alone | `20260928T235839Z-db233b84a6da` | Passed; cleanup complete |
| 1.21.1 NeoForge + GriefLogger | `20260928T235942Z-1935397f9e67` | Passed; cleanup complete |
| 1.21.1 NeoForge + SQLite library mod | `20260929T000046Z-74e608ebf956` | Passed; cleanup complete |
| 1.21.1 NeoForge, VSS | `20260929T000150Z-8720a89c1982` | Passed; cleanup complete |
| 26.2 Fabric, LSS | `20260929T003140Z-b49c72cb18d7` | Passed; cleanup complete |
| 26.2 Paper, LSS + Paper bundled driver | `20260929T003545Z-3be449ac2367` | Passed; cleanup complete |

The Fabric and Paper checks use the 26.2 release-head artifacts. Paper's own bundled SQLite driver was present throughout the successful test. Each LSS native candidate SHA matches its corresponding final build matrix jar. The VSS native run used a packaged jar rebuilt from the same implementation; its ZIP timestamps differ from the matrix rebuild, but every entry name and every entry's bytes match exactly. Both exact archive hashes and the equal deterministic entry-content digest are recorded, rather than claiming byte-identical archives.

## First-attempt failures retained

- The first standalone test assembled a resource-jar URL as a filesystem path; the harness now handles jar resources explicitly.
- The first Windows probe accidentally selected the common test-fixture jar; the corrected selector excludes fixture/source artifacts.
- A test-source brace typo failed compilation and was corrected before validation.
- The initial rig recipe named an immutable asset tree `smoke-assets`, which the current verifier rejects. The next immutable attempt uses the supported `assets` root.
- The first successful native write/restart/read sequence failed the final report check because the older checker expected both handshakes in one server log. The optional restart route now checks both real logs, session ordering and the reopened store status. Its 24 smoke-checker tests passed, and all four candidate scenarios were rerun successfully. The initial failed report was retained.
- The first catalog refresh correctly rejected an unclassified `port-batch.json`. It is now classified as line-local metadata with schema/exact-blob validation and a separate complete five-line gate.
- `fabric-main-01` was rejected before launch because the saved asset-index identity was stale. The replacement was checked against Mojang's current index SHA-1 `52695890153d94cf946455da532806db8c530831`; all 5,057 objects were verified by size/hash. Its 77 changed language objects were already cached. The recipe binds the actual vendor client jar and freezes mutable metadata aliases.
- `fabric-main-02` passed the actual store write/restart/read assertions but failed evidence collection: Minecraft extracted 39 verified vanilla server libraries not declared in the recipe's immutable input tree. The next recipe pre-extracts the verified server bundle, checks each embedded SHA-256 and declares libraries/versions. `fabric-main-03` passed collection and cleanup.
- `paper-main-02` was rejected during staging because an overbroad recipe filter removed Paper's own SQLite driver. The filter now removes only the obsolete ambient client dependency and preserves all referenced server cache inputs. `paper-main-03` passed with Paper's driver present.

All private logs, run manifests, dependency hashes, scripts and failed attempts are retained under `~/.local/state/lss-sqlite-isolation-20260928/`. Public-facing draft replies and notes are [separate](../planning/2026-09-28-sqlite-isolation-release-notes-draft.md); nothing has been posted, merged, deployed to personal instances, or published by this task.

## Remaining execution coverage limits

Linux x86_64 and Windows x86_64 execute the native driver. Packaging checks preserve and verify Linux/Musl, Windows and macOS x86_64/aarch64 resources; macOS, ARM and musl execution was not performed. Fabric client gametests and unrelated performance/renderer campaigns are outside this patch's gate. Existing platform support tiers remain unchanged.
