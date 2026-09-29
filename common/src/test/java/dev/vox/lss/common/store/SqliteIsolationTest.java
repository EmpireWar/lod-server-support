package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SqliteIsolationTest {
    @TempDir Path tmp;

    private ProcessBuilder probe(String... arguments) throws Exception {
        var classes = SqliteDriverRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        var tests = SqliteRuntimeProbe.class.getProtectionDomain().getCodeSource().getLocation();
        Path resources = Path.of(SqliteRuntimeProbe.resourcesLocation().toURI());
        String cp = Path.of(classes.toURI()) + java.io.File.pathSeparator
                + Path.of(tests.toURI()) + java.io.File.pathSeparator + resources;
        if (arguments.length > 0 && arguments[0].equals("ambient")) {
            cp += java.io.File.pathSeparator + System.getProperty("lss.test.ambientSqlite");
        }
        var command = new java.util.ArrayList<String>(java.util.List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", cp, SqliteRuntimeProbe.class.getName()));
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command).directory(tmp.toFile()).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("probe.log").toFile());
    }

    private void run(ProcessBuilder builder) throws Exception {
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "probe timed out");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(tmp.resolve("probe.log")); }
                catch (Exception e) { return e.toString(); }
            });
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test void forkedNoAmbientDriverWorksAndReusesTheEngine() throws Exception {
        run(probe());
        assertTrue(Files.readString(tmp.resolve("probe.log")).contains("PASS private SQLite"));
    }

    @Test void twoPrivateEnginesCoexistWithAnAmbientEngineOnSeparateFiles() throws Exception {
        run(probe("ambient"));
    }

    @Test void abandonedUncertainOwnerKeepsItsOperatingSystemLockAfterGc() throws Exception {
        run(probe("quarantine"));
    }

    @Test void rejectedDuplicateCannotReleaseTheOperatingSystemLock() throws Exception {
        Path directory = Files.createDirectories(tmp.resolve("store"));
        try (var lease = StoreDirectoryLease.acquire(directory)) {
            assertThrows(java.io.IOException.class, () -> StoreDirectoryLease.acquire(directory.resolve(".")));
            run(probe("locked", directory.toString()));
        }
        try (var reopened = StoreDirectoryLease.acquire(directory)) { assertNotNull(reopened); }
    }

    @Test void damagedCacheIsNeverOverwrittenOrExecuted() throws Exception {
        byte[] expected = {1, 2, 3, 4};
        Path cached = SqliteDriverRuntime.extract(tmp, expected);
        assertEquals(cached, SqliteDriverRuntime.extract(tmp, expected));
        Files.write(cached, new byte[]{4, 3, 2, 1});
        assertThrows(java.io.IOException.class, () -> SqliteDriverRuntime.extract(tmp, expected));
        assertArrayEquals(new byte[]{4, 3, 2, 1}, Files.readAllBytes(cached));
        try (var files = Files.list(tmp)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test void concurrentExtractionPublishesOneImmutableFileAndCleansStaging() throws Exception {
        byte[] bytes = new byte[32768]; new java.util.Random(43).nextBytes(bytes);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Path>>();
            for (int i = 0; i < 16; i++) results.add(workers.submit(() -> SqliteDriverRuntime.extract(tmp, bytes)));
            Path first = results.getFirst().get(10, TimeUnit.SECONDS);
            for (var result : results) assertEquals(first, result.get(10, TimeUnit.SECONDS));
            assertArrayEquals(bytes, Files.readAllBytes(first));
            try (var paths = Files.list(tmp)) { assertEquals(1, paths.count()); }
            assertThrows(java.io.IOException.class, () -> SqliteDriverRuntime.extract(first, bytes));
        }
    }

    @Test void onlyConfirmedSqliteCorruptionAuthorizesRecreation() {
        assertTrue(SqliteLodStore.isDatabaseCorruption(new SQLException("corrupt", "", 11)));
        assertTrue(SqliteLodStore.isDatabaseCorruption(new SQLException("notadb", "", 26)));
        assertTrue(SqliteLodStore.isDatabaseCorruption(new SQLException("extended corrupt", "", 267)));
        for (int code : new int[]{0, 1, 5, 6, 7, 8, 10, 13, 14}) {
            assertFalse(SqliteLodStore.isDatabaseCorruption(new SQLException("preserve", "", code)));
        }
        assertFalse(SqliteLodStore.isDatabaseCorruption(new UnsatisfiedLinkError("native")));
    }

    @Test void nonCorruptSetupFailurePreservesDatabaseAndReleasesOwnership() throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path database = store.resolve("store.db");
        // Valid SQL but an incompatible object: CREATE TABLE IF NOT EXISTS passes;
        // subsequent store SQL fails with SQLITE_ERROR, not corruption.
        try (var c = SqliteDriverRuntime.connect(database); var st = c.createStatement()) {
            st.execute("CREATE TABLE meta (unrelated TEXT)");
            st.execute("INSERT INTO meta VALUES ('precious')");
        }
        byte[] before = Files.readAllBytes(database);
        var env = new SqliteLodStore.Environment(store, "isolation-test", 20, d -> null, d -> "", 0);
        assertNull(SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics()));
        try (var c = SqliteDriverRuntime.connect(database); var st = c.createStatement();
             var rs = st.executeQuery("SELECT unrelated FROM meta")) {
            assertTrue(rs.next()); assertEquals("precious", rs.getString(1));
        }
        // Schema setup can add tables before failing; the sentinel table/data must survive.
        assertTrue(Files.size(database) >= before.length);
        try (var lease = StoreDirectoryLease.acquire(store)) { assertNotNull(lease); }
    }
}
