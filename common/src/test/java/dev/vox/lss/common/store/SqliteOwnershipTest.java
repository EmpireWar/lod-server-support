package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real native handles across blocked close, timed-out writer and linkage failure. */
class SqliteOwnershipTest {
    @TempDir Path tmp;
    private static final String DIM = "minecraft:overworld";

    private SqliteLodStore.Environment env(Function<String, Path> resolver) {
        return new SqliteLodStore.Environment(tmp.resolve("store"), "ownership-test", 20, resolver, d -> "", 0);
    }
    private SqliteLodStore open(Function<String, Path> resolver) {
        return SqliteLodStore.createOrNull(LodStoreMode.FULL, env(resolver), new LodStoreDiagnostics());
    }
    private SqliteLodStore seeded() throws Exception {
        Files.createDirectories(tmp.resolve("region"));
        var store = open(d -> tmp.resolve("region"));
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        assertTrue(store.deposit(DIM, 0, new byte[]{1, 2, 3}, 100));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (store.get(DIM, 0) == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotNull(store.get(DIM, 0));
        return store;
    }
    private static void uninterruptible(CountDownLatch latch) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) {
            try { if (latch.await(100, TimeUnit.MILLISECONDS)) return; }
            catch (InterruptedException expected) { /* deliberately wedged worker */ }
        }
        throw new AssertionError("test gate deadline expired");
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private void assertUnavailable() {
        assertNull(open(d -> tmp.resolve("region")), "retiring store must keep exclusive directory ownership");
    }
    private void assertReopens() throws Exception {
        var reopened = open(d -> tmp.resolve("region"));
        assertNotNull(reopened);
        try { assertTrue(reopened.awaitSweep(10_000)); }
        finally { reopened.shutdown(); }
    }

    @Test void timedOutBatcherRetainsOwnershipUntilItActuallyExits() throws Exception {
        seeded().shutdown();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = open(d -> { entered.countDown(); uninterruptible(release); return tmp.resolve("region"); });
        assertNotNull(store);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            store.shutdown();
            assertTrue(((Thread) field(store, "batcher")).isAlive());
            assertUnavailable();
        } finally {
            release.countDown();
            ((Thread) field(store, "batcher")).join(10_000);
            store.shutdown();
        }
        assertReopens();
    }

    @SuppressWarnings("unchecked")
    private static void replaceReader(SqliteLodStore store, Connection replacement) throws Exception {
        var local = (ThreadLocal<Connection>) field(store, "readerConn");
        var all = (List<Connection>) field(store, "allReaderConns");
        synchronized (all) { all.remove(local.get()); all.add(replacement); }
        local.set(replacement);
    }
    @SuppressWarnings("unchecked")
    private static Connection reader(SqliteLodStore store) throws Exception {
        store.isBackfillRegionDone(DIM, 0, 0);
        return ((ThreadLocal<Connection>) field(store, "readerConn")).get();
    }
    private static Object invoke(Object receiver, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException cause) { throw cause.getCause(); }
    }

    @Test void blockedReaderExitCloseCannotReleaseOwnershipOrBlockShutdown() throws Exception {
        var store = seeded();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                Connection actual = reader(store);
                var proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                        (object, method, args) -> {
                            if (method.getName().equals("equals")) return object == args[0];
                            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                            if (method.getName().equals("close")) { entered.countDown(); uninterruptible(release); }
                            return invoke(actual, method, args);
                        });
                replaceReader(store, proxy);
                store.closeReaderConnForCurrentThread();
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            store.shutdown();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8));
            assertUnavailable();
        } finally {
            release.countDown();
            worker.join(10_000);
            store.shutdown();
        }
        assertNull(failure.get());
        assertFalse(worker.isAlive());
        assertReopens();
    }

    @Test void nativeReadFailureNeverEnqueuesDeletionOfValidRows() throws Exception {
        var store = seeded();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                Connection actual = reader(store);
                var proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                        (object, method, args) -> {
                            if (method.getName().equals("equals")) return object == args[0];
                            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                            Object value = invoke(actual, method, args);
                            if (!method.getName().equals("prepareStatement")) return value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class},
                                    (ps, operation, parameters) -> {
                                        if (operation.getName().equals("executeQuery")) throw new UnsatisfiedLinkError("injected native failure");
                                        return invoke(value, operation, parameters);
                                    });
                        });
                replaceReader(store, proxy);
                assertNull(store.get(DIM, 0));
                assertNull(store.getFrame(DIM, 0));
                store.closeReaderConnForCurrentThread();
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start(); worker.join(10_000);
        store.shutdown(); // drains pending deletion commands, if the regression enqueued any
        assertNull(failure.get()); assertFalse(worker.isAlive());
        try (var c = SqliteDriverRuntime.connect(tmp.resolve("store/store.db")); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM lods_1")) {
            assertTrue(rs.next()); assertEquals(1, rs.getInt(1));
        }
    }

    @Test void uncertainProbeCloseKeepsItsFiles() throws Exception {
        var actuals = new java.util.ArrayList<Connection>();
        try {
            assertThrows(SqliteDriverRuntime.ProbeCloseException.class, () -> SqliteDriverRuntime.probe(tmp, path -> {
                var actual = SqliteDriverRuntime.connect(path);
                actuals.add(actual);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                        (object, method, args) -> {
                            if (method.getName().equals("equals")) return object == args[0];
                            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                            if (method.getName().equals("close")) throw new SQLException("injected uncertain close");
                            return invoke(actual, method, args);
                        });
            }));
            try (var paths = Files.list(tmp)) { assertTrue(paths.anyMatch(p -> p.toString().endsWith(".db"))); }
        } finally { for (var c : actuals) c.close(); }
    }

    @Test void walProbeFailureDoesNotTouchExistingDatabaseWalOrShm() throws Exception {
        Path directory = Files.createDirectories(tmp.resolve("store"));
        var originals = new java.util.LinkedHashMap<Path, byte[]>();
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path path = directory.resolve("store.db" + suffix);
            byte[] bytes = ("untouched existing store " + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path, bytes); originals.put(path, bytes);
        }
        assertThrows(SQLException.class, () -> new SqliteLodStore(LodStoreMode.FULL,
                StoreCodec.zstdOrNull(), env(d -> null), new LodStoreDiagnostics(), path -> {
                    assertNotEquals(directory.resolve("store.db"), path);
                    var actual = SqliteDriverRuntime.connect(path);
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                            (object, method, args) -> {
                                Object result = invoke(actual, method, args);
                                if (!method.getName().equals("createStatement")) return result;
                                return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                        new Class[]{java.sql.Statement.class}, (st, operation, parameters) -> {
                                            if (operation.getName().equals("executeQuery")
                                                    && parameters[0].equals("PRAGMA journal_mode=WAL")) {
                                                throw new SQLException("injected WAL filesystem failure", "", 10);
                                            }
                                            return invoke(result, operation, parameters);
                                        });
                            });
                }));
        for (var entry : originals.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        try (var lease = StoreDirectoryLease.acquire(directory)) { assertNotNull(lease); }
        try (var paths = Files.list(directory)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".lss-sqlite-probe-"))); }
    }
}
