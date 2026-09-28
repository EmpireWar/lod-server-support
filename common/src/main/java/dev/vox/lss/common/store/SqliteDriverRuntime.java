package dev.vox.lss.common.store;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Properties;

/** One native engine per defining LSS loader, shared across every store lifetime.
 * Only JDK SQL types cross this boundary. Successful loaders intentionally live as
 * long as their defining loader: closing a store must never create a second native
 * SQLite lock table for a database still used by a retiring worker. */
public final class SqliteDriverRuntime {
    public static final String VERSION = "3.49.1.0";
    private static final String ROOT = "/dev/vox/lss/internal/jdbc/";
    private static final String BRIDGE = "dev.vox.lss.jdbc.PrivateSqliteBridge";
    private static SqliteDriverRuntime shared;
    private final PrivateLoader loader;
    private final Method factory;

    private SqliteDriverRuntime() throws Exception {
        if (ModuleLayer.boot().findModule("java.sql").isEmpty()
                || ModuleLayer.boot().findModule("java.logging").isEmpty()) {
            throw new IOException("private SQLite needs the JDK java.sql and java.logging modules");
        }
        Properties descriptor = new Properties();
        try (var in = SqliteDriverRuntime.class.getResourceAsStream(ROOT + "driver.properties")) {
            if (in == null) throw new IOException("missing private SQLite descriptor");
            descriptor.load(in);
        }
        byte[] capsule = resource("sqlite-jdbc.jar.bin");
        byte[] bridge = resource("bridge.class.bin");
        if (!VERSION.equals(descriptor.getProperty("version"))
                || !Long.toString(capsule.length).equals(descriptor.getProperty("size"))
                || !hash(capsule).equals(descriptor.getProperty("sha256"))
                || !hash(bridge).equals(descriptor.getProperty("bridgeSha256"))) {
            throw new IOException("private SQLite resource identity mismatch");
        }
        // Installation cwd, never the first opened world (which can be deleted).
        Path cache = Path.of(".lss", "sqlite-runtime").toAbsolutePath().normalize();
        Files.createDirectories(cache);
        Path jar = extract(cache, capsule);
        var properties = System.getProperties();
        synchronized (properties) {
            Path natives = cache.resolve("natives");
            String selected = properties.getProperty("org.sqlite.tmpdir");
            if (selected == null || selected.equals(natives.toString())) Files.createDirectories(natives);
            if (selected == null) properties.setProperty("org.sqlite.tmpdir", natives.toString());
        }
        PrivateLoader candidate = new PrivateLoader(jar.toUri().toURL());
        var sqliteLog = java.util.logging.Logger.getLogger("org.sqlite");
        var bootstrapLog = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    String message = "Private SQLite bootstrap: " + record.getMessage();
                    try {
                        if (record.getThrown() == null) dev.vox.lss.common.LSSLogger.warn(message);
                        else dev.vox.lss.common.LSSLogger.warn(message, record.getThrown());
                    } catch (LinkageError noHostLogging) {
                        // Standalone JDK-only probes have no SLF4J host. Preserve the cause there too.
                        System.getLogger(SqliteDriverRuntime.class.getName()).log(
                                System.Logger.Level.WARNING, message, record.getThrown());
                    }
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        sqliteLog.addHandler(bootstrapLog);
        try {
            Class<?> child = candidate.defineBridge(bridge);
            String nativeVersion = (String) invoke(child.getMethod("initialize"));
            if (!"3.49.1".equals(nativeVersion)) {
                System.getLogger(SqliteDriverRuntime.class.getName()).log(System.Logger.Level.WARNING,
                        "Private SQLite JDBC " + VERSION + " loaded native " + nativeVersion
                        + "; check org.sqlite.lib.path/lib.name overrides");
            }
            Class<?> jdbc = Class.forName("org.sqlite.JDBC", false, candidate);
            if (jdbc.getClassLoader() != candidate || jdbc.getModule().isNamed()) {
                throw new IOException("SQLite escaped its private unnamed module");
            }
            this.factory = child.getMethod("dataSource", String.class);
            this.loader = candidate;
        } catch (Throwable failure) {
            try { candidate.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        } finally {
            // Never leave a JUL global root to the defining plugin loader.
            sqliteLog.removeHandler(bootstrapLog);
        }
    }

    public static synchronized DataSource dataSource(String url) throws SQLException {
        try {
            if (shared == null) shared = new SqliteDriverRuntime();
            return (DataSource) invoke(shared.factory, url);
        } catch (Exception | LinkageError failure) {
            throw new SQLException("private SQLite " + VERSION + " unavailable", failure);
        }
    }

    static Connection connect(Path database) throws SQLException {
        return dataSource("jdbc:sqlite:" + database.toAbsolutePath()).getConnection();
    }

    /** Prove WAL support on this filesystem before touching the real database. */
    static void probe(Path storeDirectory) throws Exception {
        probe(storeDirectory, SqliteDriverRuntime::connect);
    }

    @FunctionalInterface interface Connector { Connection open(Path database) throws SQLException; }
    static final class ProbeCloseException extends SQLException {
        ProbeCloseException(Throwable cause) { super("SQLite probe close uncertain; retaining ownership and files", cause); }
    }

    // Connection seam allows a real file-backed probe with an injected close failure.
    static void probe(Path storeDirectory, Connector connector) throws Exception {
        Path probe = Files.createTempFile(storeDirectory, ".lss-sqlite-probe-", ".db");
        Connection writer = null, reader = null;
        Throwable original = null;
        try {
            writer = connector.open(probe);
            try (var st = writer.createStatement()) {
                requireWal(st);
                st.execute("CREATE TABLE probe (value INTEGER)");
                st.execute("INSERT INTO probe VALUES (17)");
                reader = connector.open(probe);
                try (var read = reader.createStatement(); var rs = read.executeQuery("SELECT value FROM probe")) {
                    if (!rs.next() || rs.getInt(1) != 17) throw new SQLException("SQLite file probe failed");
                }
            }
        } catch (Throwable failure) {
            original = failure;
            throw failure;
        } finally {
            Throwable closeFailure = null;
            for (Connection c : new Connection[]{reader, writer}) {
                if (c == null) continue;
                try { c.close(); }
                catch (Throwable failure) {
                    if (closeFailure == null) closeFailure = failure;
                    else closeFailure.addSuppressed(failure);
                }
            }
            if (closeFailure != null) {
                var uncertain = new ProbeCloseException(closeFailure);
                if (original != null) uncertain.addSuppressed(original);
                throw uncertain; // do not unlink possibly open files or release the lease
            }
            for (String suffix : new String[]{"-wal", "-shm", ""}) Files.deleteIfExists(Path.of(probe + suffix));
        }
    }

    static void requireWal(java.sql.Statement st) throws SQLException {
        try (var rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
            if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("LOD store filesystem did not enable SQLite WAL mode");
            }
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (var in = SqliteDriverRuntime.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException("missing private SQLite resource " + name);
            return in.readAllBytes();
        }
    }

    static Path extract(Path directory, byte[] bytes) throws Exception {
        Path target = directory.resolve("sqlite-jdbc-" + VERSION + "-" + hash(bytes) + ".jar");
        if (!Files.exists(target)) {
            Path staged = Files.createTempFile(directory, ".sqlite-", ".tmp");
            try {
                Files.write(staged, bytes);
                try { Files.move(staged, target); } catch (FileAlreadyExistsException raced) { /* verify winner */ }
            } finally { Files.deleteIfExists(staged); }
        }
        if (Files.size(target) != bytes.length || !hash(Files.readAllBytes(target)).equals(hash(bytes))) {
            // Never overwrite a potentially mapped archive/DLL. Operator can remove it offline.
            throw new IOException("corrupt private SQLite cache file: " + target);
        }
        return target;
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception e) throw e;
            if (cause instanceof Error e) throw e;
            throw new IllegalStateException(cause);
        }
    }

    private static final class PrivateLoader extends URLClassLoader {
        PrivateLoader(URL jar) { super(new URL[]{jar}, ClassLoader.getPlatformClassLoader()); }
        Class<?> defineBridge(byte[] bytes) { return defineClass(BRIDGE, bytes, 0, bytes.length); }
    }
}
