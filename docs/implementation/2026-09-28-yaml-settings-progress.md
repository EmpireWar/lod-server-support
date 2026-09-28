# Structured YAML settings implementation

User approval: 2026-09-28. Scope: the [reviewed implementation plan](../planning/2026-09-28-yaml-settings-implementation-plan.md), all five maintained lines, followed by an independent Astra implementation review. No release, push, merge, personal launcher deployment or normal test-server change is included.

## Work and evidence

| Area | Current state |
| --- | --- |
| Schema, annotated YAML, typed records | Implemented on the 26.2 development branch. Shared schema drives defaults, validation, references and the Java settings-file helper. |
| Legacy migration and persistence | Implemented. Retains original JSON and exact backup; preserves old distance/store defaults and tick-based timing. Strict YAML, comment-preserving edits, create-only startup and conflict-aware replacement covered by tests. |
| Reload transactions | Implemented with bounded IO, owner publication, immutable effective/configured/boot/session state, pending boundaries and tracked subsystem receipts. |
| Server performance controls | Generation, backfill, store/cache, serialization and queue policies implemented on mod and Paper services. Owner-adoption tests and live game tests are being validated. |
| Client and menus | Explicit reload, physical-connection settings freeze, draft-only Sodium Apply, retained save/conflict handling, privacy-send retries, modern/legacy adapters and three locales implemented. |
| Commands and cleanup | Old set/preset/status-toggle publishers removed. Source references to automatic coexistence suppression removed; the README retains the inspiration acknowledgement. |
| Tooling and documentation | Native YAML fixture/rig conversion and operational guide updates in progress. Historic measurement results are not rewritten. |
| Support-line ports | Not yet validated. 1.21.1, 1.21.10, 1.21.11 and 26.1 require isolated ports and line-specific checks. |
| Independent Astra implementation reviews | Pending completed implementation; the earlier two plan reviews are not implementation reviews. |

Initial validation on 26.2:

- Shared parser/migration/store/handle/CLI focused standalone tests passed; the suite grew as integrated tests found additional cases. Actual C: DrvFS testing passed 30 store/handle/CLI cases in a task-owned temporary directory, which was removed. This is WSL Java accessing DrvFS, not native Windows JVM certification.
- The first full common Gradle suite passed **870 tests, zero failures/errors/skips**. A packaged-boundary test first exposed an engine-type return signature altered by shading; the parser type no longer escapes that boundary.
- Production Fabric, Paper and NeoForge compilation passed. Fabric and NeoForge test sources compiled. Subsequent test and game-test fixture migrations are under validation.
- First full Fabric run: 1,653 tests, eight failures, four skips. Findings included two historical primitive-to-string migration cases and stale source-contract patterns. The migration cases were fixed rather than weakening the old accepted-input semantics.
- First Paper run found an adoption-test fixture accidentally booted with generation enabled and a source-order assertion matching a newly added reconciliation method. Both were corrected while retaining the behavior/ownership assertions.
- Client draft tests found an emitter failure expanding an inline-commented empty collection. Added a regression for expansion and returning to an empty collection; the client draft/privacy standalone suite passes eight tests.
- Common shaded JAR inspection passed for the relocated parser and license. Platform/brand artifact checks are implemented but still require final JARs.

Local detailed logs are under `/tmp/lss-yaml-validation/` and `/tmp/lss-yaml-src/`.
Final per-line results, live attempts and review dispositions must be recorded here
before the task is called complete. Compilation and simulated rig receipts do not
establish live UI, Folia multi-region or performance acceptance.
