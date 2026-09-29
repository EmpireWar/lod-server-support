# YAML settings: main native validation, 2026-09-28

The targeted mainline Fabric, Paper and Folia YAML-reload scenarios passed on the final product artifacts. Every accepted run was collected with no errors and complete owned-process cleanup. These are bounded settings/lifecycle checks, not performance or exhaustive soak acceptance. Folia remains experimental.

Client menus, the 1.21.1 Fabric/NeoForge receive lifecycle, migration boots, and full build gates have separate reports in the implementation ledger. This report covers the three main server reload routes and their fixture development attempts.

## Accepted runs and artifact identity

All run roots are under `/home/vox/.local/state/lss-rig/runs/`. Preparation/probe receipts are under `/home/vox/.local/state/lss-yaml-native-20260928/`. These paths contain private local evidence; no authentication contents are included in this report.

| Platform | Exact run ID | Accepted groups | Supervisor elapsed |
| --- | --- | ---: | ---: |
| Fabric | `20260928T205549Z-1f31c617e04d` | 3 | 145.91 s |
| Paper | `20260928T210854Z-f7bba9dfbd7d` | 3 | 40.69 s |
| Folia | `20260928T211608Z-dfd94d889df1` | 4 | 40.25 s |

For each run, `proof.json` has `ready=true`, `handshake=true`, every declared assertion true, and no failures. `evidence/result.json` and the saved collection receipt report passed, no errors, and cleanup complete. `supervisor-cleanup.json` independently records `complete=true` and `remaining_children=0`. The immutable manifest can retain `cleanup=supervisor-pending`; the supervisor and collection receipts provide the completed cleanup verdict.

The LSS product JARs remained unchanged throughout all fixture fixes:

| Product | SHA-256 |
| --- | --- |
| Fabric server and both native clients | `6de05dc274ff03136950a414edc5fc19eb4c290952d7d3ab555b43735429e5a6` |
| Paper plugin, used by Paper and Folia | `66d6dbdbf66f29e776f2044ba253ee719d58f208ffed75f0e3b5f97b32bc03d5` |

These match both `/tmp/lss-yaml-validation/main-nonlive-results.json` and `main-final-results.json`. All three routes used Java 25, a fresh private normal-terrain world, loopback-only endpoints, two independently launched clients, creative mode, view/simulation distance 3, LOD radius 16, a 40-column/s client cap, generation concurrency 2 globally/1 per player, and a 256 MiB LOD store. No personal Prism instance or normal test-server directory was used.

Fabric's accepted recipe was `main-fabric-normal-01`, with a hash-bound provenance record changing only the prior flat-world properties. That proven normal-terrain setup was subsequently persisted in `prepare_yaml_reload.py`. Paper used the resulting maintained `main-paper-final-06` recipe. Folia used `main-folia-capacity-01`, changing only `max-players=2` to `4` from `main-folia-final-06`; exactly two clients were still launched, and the checker still required two registered clients. The proven Folia-only admission headroom was persisted in commit `b46fa992` and its four support-line ports.

## Actual generation and serving witnesses

The observer runs at the actual product `tick()V` return and `updatePolicy(ZIIIJ)V` return, on the service owner. It does not invent active-work counters or mutate product state. The driver saves the inert YAML edit before placement, captures a fresh observation boundary, waits for newly observed active work after that boundary, and immediately sends the real reload command through the owned supervisor command queue.

| Platform | Off: active / submitted / completed | Drained: submitted / completed | Quiet submitted | Resumed completed | Quiet → serving sections |
| --- | --- | --- | ---: | ---: | --- |
| Fabric | 2 / 4 / 2 | 4 / 4 | 4 | 5 | 39 → 96 |
| Paper | 1 / 3 / 2 | 3 / 3 | 3 | 4 | 17 → 153 |
| Folia | 2 / 4 / 2 | 4 / 4 | 4 | 5 | 45 → 156 |

All disabled drain and quiet witnesses remain on the same accepted policy revision. Quiet lasts at least two seconds; submitted work does not increase from off through drain and quiet. Timeout/removal counters do not increase. Re-enabling produces a newer policy revision and new completed work while both actual v20 client sessions continue receiving sections.

The zero-based phase indices in `evidence/settings-events.jsonl` are:

| Platform | initial | off | drained | quiet | resumed | serving | restart pending / retained / reverted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Fabric | 162 | 164 | 169 | 190 | 240 | 241 | 1210 / 1220 / 1229 |
| Paper | 156 | 159 | 178 | 199 | 218 | 219 | 231 / 242 / 252 |
| Folia | 167 | 169 | 181 | 202 | 222 | 223 | 234 / 245 / 258 |

Fabric additionally observes the actual private native generation-ticket type at previously active positions: off holds two tickets across four tracked positions; drained/quiet have zero matching tickets and zero deferred releases. Paper/Folia generation tickets belong to Moonrise; this route makes no equivalent direct ticket-table claim there.

Each route edits the configured reader-thread count, observes pending-restart=true, accepts an unrelated live concurrency revision while retaining that pending state, then reverts the configured reader value and observes pending-restart=false. The physical reader pool stays 3 threads on Fabric and 8 on Paper/Folia throughout these transitions.

Each route also passes the real saved-only, applied, unchanged/no-op, malformed YAML rejection, repaired retry, and exact restoration control sequence, using actual command acknowledgment logs and diagnostic export pairs. The control receipts are:

- Fabric: `evidence/settings-0af82c0b4fbe450cbd3b7812c18344a2/receipt.json`.
- Paper: `evidence/settings-ce25ead0da2546d5b8cb70201bcb1b29/receipt.json`.
- Folia: `evidence/settings-19a86d98f8a044f99a79291085d6502b/receipt.json`.

## Fabric backfill witnesses

A bounded vanilla forceload/save/unload operation supplies real disk terrain. The driver observes actual worker identity, live policy adoption and deposited counters; no synthetic deposits are inserted.

| Phase | Worker | Rate | Deposits | Skipped | Observation |
| --- | ---: | ---: | ---: | ---: | --- |
| Low start | 118 | 10 | 8 | 343 | Running |
| Low end, 3.00036 s later | 118 | 10 | 16 | 365 | Real deposit progress within low ceiling |
| High start | 118 | 40 | 25 | 396 | Same live worker, newer policy |
| High end, 4.90030 s later | 118 | 40 | 88 | 533 | 63 new deposits, exceeding `10 × elapsed + 10` and below high ceiling |
| Disabled/stopped | 118 | 40 | 88 | 533 | Worker stopped |
| Quiet, 2.09952 s later | 118 | 40 | 88 | 533 | No further deposits |
| Resumed | 119 | 40 | 1 | 999 | New worker, real new deposit |

Rate accounting includes skipped columns, so the configured rate is not asserted to equal deposited throughput. The strict higher-rate check demonstrates an actual increase beyond the low-rate allowance. The newly started worker's counters are a new epoch. All these witnesses report zero errors and zero pauses.

## Folia owning-region witness

The retained region observer records the two actual sessions, checks entity-region ownership, and pairs callbacks with the exact instrumented native full-tick intervals. It produced 503 qualified tick samples. Of those, 205 lie wholly inside the reload-off to resumed-serving window.

One concrete overlapping pair inside that window is:

| Subject | Region identity | Full owning tick start/end (`System.nanoTime`) |
| --- | --- | --- |
| RigSubjectB | `6` | `140351794978292` → `140351831329751` |
| RigSubjectA | `3` | `140351828595372` → `140351833849549` |

Their native tick intervals overlap by 2,734,379 ns. Both samples are after the corresponding real product registration, inside their joined sessions, and carry `owns_region=true`. This establishes the targeted concurrent owning-region reload/drain witness. It does not retire Folia's experimental label or claim an exhaustive concurrent-region soak/performance gate.

## Observer lineage and validation

Fabric's accepted source freeze was `812aec42`; Paper/Folia used `c620a2ad`. The final Folia capacity persistence changes only generated server admission headroom. Product JAR hashes above are identical across all accepted runs.

| Accepted platform | Agent JAR SHA-256 | Bootstrap recorder JAR SHA-256 |
| --- | --- | --- |
| Fabric | `08f2c0a21e572b7de643706db8fa5253329ebf97698373c6cbc3ac347e0da142` | `06611c9a490e6742634c35330aeb2b53f0e8a268423d6a97011c7d0203a14298` |
| Paper/Folia | `7de0ee30588516379e3400a763eb22a8232457063b315caa54fab703f64b473e` | `0369fa633f082ef1d6a370c2ef6647385877f091f0babaa0727e4169db9c6e4c` |

Fabric resolves ASM from its explicit server startup classpath; its agent contains no duplicate ASM classes. The two exact owned observer JARs are exposed with Fabric's supported `fabric.systemLibraries` property, preserving inherited entries. A real cached Knot negative control reproduces rejection without that allowlist; the positive control resolves the identical bootstrap recorder class and evidence sink, including a reflective child-class callback.

Paper/Folia bootstrap agents include one ASM provider. Bukkit processes plugin class bytes before `defineClass`, so the observer first verifies the raw class in the actual plugin's CodeSource JAR against the frozen candidate hash. It pins the actual plugin/classloader/source identity, reproduces the actual `UnsafeValues.processClass` call with that plugin's description, and requires full byte equality with the incoming class. It records both hashes. There is no arbitrary transformed-hash whitelist or blanket guard bypass.

| Target | Raw candidate SHA-256 | Actual Paper/Folia processed SHA-256 |
| --- | --- | --- |
| PaperRequestProcessingService | `b07df700a36bad3b7d58714ed3d75998c642bb2a3afcd2a2da14e45462594353` | `8e455292bb0b0425b80bdc84028fac12576da6c42386daf98a0d2e02024bdb1b` |
| PaperChunkGenerationService | `f54fa9461d7e4c816aad0f0d6f582a5a9f4e85c08d42fea35a752c3c8d94c6fd` | `ea264e5844ab19ec7705bf57865f43a54bbee24cb3229c48e2554d5356515b98` |

The live processed hashes exactly match the independently executed cached Paper Commodore probe. Build-time checks parse both real candidate classes with the exact runtime ASM and pin the observation method descriptors; altered raw source and altered incoming processed bytes are rejected. Every accepted settings recorder closes with `overflow=false`. The Folia timing and region writers also close successfully.

## Preserved failed attempts and dispositions

Every attempted run below is preserved under the same run root and has a complete cleanup receipt with zero remaining children. None is counted as acceptance.

| Platform/attempt | Run ID | Concrete failure | Disposition |
| --- | --- | --- | --- |
| Fabric 1 | `20260928T202442Z-6a436646d00e` | Knot rejected duplicate ASM from explicit server classpath plus agent | Fabric agent made slim; actual archive/classpath checks, standalone premain probe; fix `d96be18d` |
| Fabric 2 | `20260928T203304Z-bee91e1ee92a` | First product tick could not resolve bootstrap recorder through Knot isolation | Supported exact-path library allowlist, same-class/sink negative/positive probe; fix `153daf44` |
| Fabric 3 | `20260928T204413Z-a90d9a106f54` | Flat job completed during historical sample/setup/CLI interval; off saw active=0 | Inert edit staged first, fresh post-placement observation boundary, immediate reload; fix `a173d929` |
| Fabric 4 | `20260928T204843Z-93b27903aa0c` | Flat job still completed in the remaining 159 ms sample-to-owner-commit interval | Genuine cold normal-terrain workload, unchanged active-at-off check; validated in accepted Fabric run, persisted with `88028b3a` |
| Paper 1 | `20260928T205853Z-5bcb147df7a4` | Strict raw-class hash rejected Bukkit-processed incoming target bytes | Exact original-source plus reproduced-platform-transform equality guard; fix `88028b3a`; accepted Paper run validates native path |
| Folia 1 | `20260928T211016Z-7c899f87b4d9` | Both simultaneous clients rejected as server full before joining with max-players=2 | Cached native code counts pending reservations and rechecks during login/configuration; private four-slot headroom passed with exactly two clients, persisted by `b46fa992` |

Create-only staging run `20260928T203106Z-17a3c691abb9` was superseded before launch when the maintained preparer added forced creative mode (`45dc975b`); it is not a failed runtime test. No missing admission/drain premise, transformation failure, or login failure was changed into a pass by relaxing a checker. All fixture fixes were ported to the four support worktrees and independently reviewed/catalog-bound.

The final capacity fixture was independently reviewed and catalog-bound on main `d8aa1bc6` and support lines `df0693c5` (26.1), `0b791354` (1.21.11), `d7392fbd` (1.21.10), and `fc26ac2f` (1.21.1). The independent reviewer re-ran all three strict native checkers and the Folia region check successfully. A final read-only audit checked 61 process-identity records across all nine attempted runs: none was alive, and every supervisor cleanup receipt was complete (`main-native-final-cleanup.json` in the private preparation directory).

The targeted main Fabric/Paper/Folia native settings validation is complete. All owned native processes from these attempts are stopped. The final maintained recipe generation (`main-*-final-07`) is ready with zero missing inputs and preserves the validated normal-world properties and Folia-only login headroom.
