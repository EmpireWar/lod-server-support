# RegionFault containment-test isolation review — 2026-09-28

The 1.21.10 and 1.21.11 complete Fabric Tier 2 runs each failed the corrupt-region containment test with `errors=2 success=0 sent=1 submitted=2 completed=2 not_found=0 saturated=0`. The failure was diagnosed before any retry; it was not dismissed as the historical corrupt-label variation.

## Established evidence

- The 1.21.10 log at line 238 and 1.21.11 log at line 232 record the valid target `(-220,2)` exceeding the existing ten-second disk-read deadline, with one additional timeout accumulated by the same reader's throttle. Both reads completed as errors; the pool did not remain wedged.
- The preserved valid region `r.-7.0.mca`, slot 68, decompresses to 14,582 bytes of NBT containing full status and sections. The corrupt region `r.-60.60.mca`, slot 0, still fails zlib with the intended invalid header. SHA evidence is in `12110-region-fault-source-diagnosis.json` and the complete source/config/class/region archive manifest is in `12110-full-attempt1-failure/region-diagnostic-inputs.json`.
- The YAML reload test ran in its own environment: before the default batch on 1.21.10, restoring settings, and after the failed default batch on 1.21.11. No RegionFault method-body change was part of YAML implementation.
- The same compiled RegionFault class and byte-identical YAML passed alone in the existing game-test world: one required test, 1.260 seconds native execution, 16 seconds Gradle. The real corrupt read produced ZipException, while all valid-read, counter, slot and pending-result assertions passed. Log: `12110-region-fault-isolated.log`.
- To remove the warmed-world ambiguity, the unchanged test was then run alone with the supported vanilla `--universe` argument pointing to the previously absent task-private `12110-region-fault-fresh-universe` directory. It passed one required test in 5.006 seconds native execution, 22 seconds Gradle. The compiled test class and staged YAML were checked byte-for-byte against the failed-run archive. Log: `12110-region-fault-isolated-fresh.log`.

Both diagnostic invocations used the cached Fabric API's supported `fabric-api.gametest.filter` property via private Gradle init scripts and the shared owned harness wrapper. Neither invocation modified repository source or deleted the existing world. The first launch attempt of the wrapper used a non-executable script directly and exited 126 before staging or starting a game; invoking it through Bash then ran the controls above.

## Scope of the conclusion and fix

The evidence establishes intact fixture bytes and a passing real containment/pool-survival path when unrelated tests are absent, including a fresh world. Shared background I/O starvation under concurrent generation/save traffic is consistent with the exact timeout signature and the existing documented read-priority design. These controls do not identify a unique scheduler event or prove a product regression. No product timeout, read-count assertion, error classification or containment guarantee was weakened.

The fix assigns RegionFault a separate `lss:region_fault` test environment on the four modern lines, using the established `minecraft:all_of` definition over `minecraft:default`. Minecraft 1.21.1 instead uses its supported `batch = "lss_region_fault"` annotation. Every test method body and the 1200-tick ceiling remain byte-identical. Existing entrypoint discovery remains intact. The environment isolates this correctness test from unrelated generation/save workload; it does not remove tests or reduce the required complete-suite gates.

A second independent Astra reviewer inspected every line's source diff, complete method remainder, environment resource, supported 1.21.1 batch flavor, and test entrypoint. Review result: no blocking findings. Subsequent complete-suite results and observed batch counts belong to the implementation progress ledger; isolated controls alone are not a substitute for those gates.

## Exact source commits

- 26.2: `8374591ebf61361a8bd3e138851c9737477ff05b` (metadata binding `6d5f2df3f632d8477d1128f8bd8f133e192c639c`).
- 26.1: `436c797b11a07b334c0ac97fadb2da3b1178b8c2` (metadata binding `68d9b773d67ee3cd191ad52942a8bb62fd53431d`).
- 1.21.11: `a7e0404d768eddbd74c0bee59cc2a2c3f65477bc` (metadata binding `6ef36ee2f233a73f58363b52587f59c34803930d`).
- 1.21.10: `9b2d5dcab718fd553609a34f7b182e822c44ae3a` (metadata binding `3002f81745e4f9c9cd901950dc4b9ad3c8c11b43`).
- 1.21.1: `69f5ad889fa910df090e442be8fce21220931145` (metadata binding `d3db04c019670e2108440f2fc5de15e1f287ba04`).

Only test annotation/comment, environment resource and the bounded evidence entry in `docs/operations/test-flakes.md` changed in the source commits. Hashes of all 30 already-built LSS/VSS product jars remained unchanged by the patch; the exact hashes are in `region-isolation-artifacts-before.json`. Classification gained one precise adapted resource row (absent on 1.21.1), updated the existing RegionFault blobs, and retained its historical basis. Explicit-ref classification and default compatibility CI passed on all five lines. Root owns the subsequent serialized complete-suite runs.

## Complete-suite followup

All five final Fabric server suites passed: 26.2 79, 1.21.1 78, 1.21.10 80,
1.21.11 80 and 26.1 79 required tests. Each log records the real containment test
in its separate one-test environment or supported 1.21.1 batch. The latter three
lines passed their full build gates after the change; main and 1.21.1 repeated
their complete Fabric server suites after their earlier full gates. All 30 product
artifacts remain byte-identical to the frozen validation candidates. The
[final ledger](2026-09-28-yaml-settings-progress.md#final-acceptance-and-cleanup)
records exact logs, aggregate evidence and the remaining platform results.
