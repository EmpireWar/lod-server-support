package dev.vox.lss.common.store;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import javax.sql.DataSource;

/** Standalone Java-21/25 and Windows probe: deliberately no JUnit/MC/ambient driver. */
public final class SqliteRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("quarantine")) {
            Path directory = Files.createDirectories(Path.of("quarantined store"));
            abandonUncertainOwner(directory);
            for (int i = 0; i < 10; i++) { System.gc(); Thread.sleep(25); }
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), SqliteRuntimeProbe.class.getName(),
                    "locked", directory.toAbsolutePath().toString()).inheritIO().start();
            try {
                if (!child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || child.exitValue() != 0) {
                    throw new AssertionError("uncertain owner lost its OS lock after collection");
                }
            } finally { if (child.isAlive()) child.destroyForcibly(); }
            return;
        }
        if (args.length > 0 && args[0].equals("locked")) {
            try (var lease = StoreDirectoryLease.acquire(Path.of(args[1]))) {
                throw new AssertionError("foreign process acquired an owned store");
            } catch (java.io.IOException expected) { System.out.println("LOCKED"); }
            return;
        }
        boolean ambient = args.length > 0 && args[0].equals("ambient");
        if (ambient) Class.forName("org.sqlite.JDBC");
        else {
            try {
                Class.forName("org.sqlite.JDBC");
                throw new AssertionError("ambient SQLite on probe classpath");
            } catch (ClassNotFoundException expected) { }
        }
        try {
            Class.forName("dev.vox.lss.jdbc.PrivateSqliteBridge");
            throw new AssertionError("bridge exposed on host classpath");
        } catch (ClassNotFoundException expected) { }
        Path directory = Files.createDirectories(Path.of("SQL probe with spaces 世界"));
        if (ambient) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("ambient.db"));
                 var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS ambient (v INTEGER)");
                st.execute("INSERT INTO ambient VALUES (1)");
            }
        }
        long bootstrapStarted = System.nanoTime();
        SqliteDriverRuntime.probe(directory);
        System.out.println("File-backed private SQLite bootstrap ms=" + (System.nanoTime() - bootstrapStarted) / 1_000_000);
        DataSource first = SqliteDriverRuntime.dataSource("jdbc:sqlite:" + directory.resolve("one.db"));
        ClassLoader engine = first.getClass().getClassLoader();
        if (engine.getParent() != ClassLoader.getPlatformClassLoader()
                || first.getClass().getModule().isNamed()) throw new AssertionError("wrong module/parent");
        roundTrip(first, true);
        long nativesBefore = nativeFiles();
        DataSource reopened = SqliteDriverRuntime.dataSource("jdbc:sqlite:" + directory.resolve("one.db"));
        if (reopened.getClass() != first.getClass()) throw new AssertionError("new engine on reopen");
        roundTrip(reopened, false);
        if (nativeFiles() != nativesBefore) throw new AssertionError("new native on store reopen");
        Path worldA = Files.createDirectory(directory.resolve("world A"));
        roundTrip(SqliteDriverRuntime.dataSource("jdbc:sqlite:" + worldA.resolve("store.db")), true);
        try (var files = Files.list(worldA)) { for (var file : files.toList()) Files.delete(file); }
        Files.delete(worldA);
        Path worldB = Files.createDirectory(directory.resolve("world B"));
        roundTrip(SqliteDriverRuntime.dataSource("jdbc:sqlite:" + worldB.resolve("store.db")), true);
        if (nativeFiles() != nativesBefore) throw new AssertionError("world switch replaced the engine");
        var bridge = Class.forName("dev.vox.lss.jdbc.PrivateSqliteBridge", false, engine);
        if (bridge.getClassLoader() != engine) throw new AssertionError("bridge loader");
        // DriverManager can see private drivers only when called from the child.
        if (((Number) bridge.getMethod("registeredDrivers").invoke(null)).intValue() != 0) {
            throw new AssertionError("private JDBC registration leaked");
        }
        var source = SqliteDriverRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        var resources = resourcesLocation();
        // Independently defined LSS copy, DIFFERENT database; never two engines on one file.
        try (var other = new URLClassLoader(new java.net.URL[]{source, resources},
                ClassLoader.getPlatformClassLoader())) {
            var runtime = Class.forName(SqliteDriverRuntime.class.getName(), true, other);
            DataSource second = (DataSource) runtime.getMethod("dataSource", String.class)
                    .invoke(null, "jdbc:sqlite:" + directory.resolve("two.db"));
            if (second.getClass() == first.getClass()) throw new AssertionError("engines not isolated");
            roundTrip(second, true);
            try (var lease = StoreDirectoryLease.acquire(directory)) {
                var own = Class.forName(StoreDirectoryLease.class.getName(), true, other);
                try {
                    own.getMethod("acquire", Path.class).invoke(null, directory);
                    throw new AssertionError("second defining loader acquired same store");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    if (!(expected.getCause() instanceof java.io.IOException)) throw expected;
                }
            }
        }
        int registered = java.util.Collections.list(DriverManager.getDrivers()).size();
        if (registered != (ambient ? 1 : 0)) throw new AssertionError("unexpected JDBC registrations: " + registered);
        if (ambient) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("ambient.db"));
                 var st = c.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM ambient")) {
                if (!rs.next() || rs.getInt(1) < 1) throw new AssertionError("ambient driver damaged");
            }
        }
        System.out.println("PASS private SQLite " + SqliteDriverRuntime.VERSION + " SQL/WAL/reopen/loader ownership");
    }

    private static void abandonUncertainOwner(Path directory) throws Exception {
        StoreDirectoryLease.acquire(directory).retainUntilProcessExit();
    }

    private static long nativeFiles() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("org.sqlite.tmpdir")))) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    static java.net.URL resourcesLocation() throws Exception {
        var resource = SqliteDriverRuntime.class.getResource("/dev/vox/lss/internal/jdbc/driver.properties");
        if (resource.getProtocol().equals("jar")) {
            return ((java.net.JarURLConnection) resource.openConnection()).getJarFileURL();
        }
        Path directory = Path.of(resource.toURI());
        for (int i = 0; i < 6; i++) directory = directory.getParent();
        return directory.toUri().toURL();
    }

    private static void roundTrip(DataSource source, boolean create) throws Exception {
        try (var c = source.getConnection(); var st = c.createStatement()) {
            SqliteDriverRuntime.requireWal(st);
            if (create) {
                st.execute("CREATE TABLE IF NOT EXISTS fixture (v INTEGER)");
                st.execute("DELETE FROM fixture");
                st.execute("INSERT INTO fixture VALUES (43)");
            }
            try (var rs = st.executeQuery("SELECT v FROM fixture")) {
                if (!rs.next() || rs.getInt(1) != 43) throw new AssertionError("lost SQL data");
            }
        }
    }
}
