# SQLite isolation — proposed release notes and replies

Not published or posted. Release version remains to be selected.

## Release notes

- Fixed NeoForge startup collisions with other mods that bundle SQLite. LSS now carries a private SQLite driver and does not require an additional SQLite library mod.
- Verified working LOD storage with GriefLogger 1.2.10 and with Minecraft SQLite JDBC, tested separately on Minecraft 1.21.1 NeoForge, including a server restart and a fresh client reading persisted data.
- Protected existing stores from deletion after native-loading, filesystem, locking, or WAL setup failures. Confirmed corruption and the existing incompatible-metadata policy retain their recovery behavior.
- Improved store ownership during shutdown and plugin reload. A failed native close safely requires a JVM restart before the same store can reopen.
- Applied the packaging and runtime change to Fabric, Paper, and maintained NeoForge builds across all five Minecraft support lines. Existing support tiers remain unchanged.

This does not resolve conflicts between two third-party SQLite providers. Their databases remain independent of LSS.

## Draft issue #304 reply

@SU-5-KsavvaZ We reproduced the startup collision and have validated a self-contained fix: LSS loads its bundled SQLite driver privately, so it no longer exposes the conflicting package to NeoForge. Both GriefLogger 1.2.10 and the SQLite library mod now work alongside LSS in separate tests, including saving LOD data and reading it after a server restart. No additional SQLite mod is required by the new LSS build. This is not released yet.

## Draft PR #306 disposition

The library dependency workaround helped establish the collision and version-range requirements. The replacement privately loads LSS's bundled driver across all support lines, keeping SQLite 3.49.1.0. It also avoids automatically installing the library mod into GriefLogger stacks, where those two providers conflict independently. Recommend superseding this PR in favor of the validated isolation patch, rather than shipping and immediately removing a required dependency.
