package dev.vox.lss.common.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

/** Exclusive database ownership across JVMs and independently loaded plugin copies.
 * The JDK-only JVM claim prevents opening/closing a second descriptor for our lock
 * file, which would release the first owner's process-scoped POSIX locks. */
public final class StoreDirectoryLease implements AutoCloseable {
    private final Properties claims;
    private final String key;
    private final String token = UUID.randomUUID().toString();
    private FileChannel channel;
    private FileLock lock;
    private boolean quarantined;

    public static StoreDirectoryLease acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new StoreDirectoryLease(directory.toRealPath());
    }

    private StoreDirectoryLease(Path canonical) throws IOException {
        this.claims = System.getProperties();
        this.key = "dev.vox.lss.sqlite.owner:" + canonical;
        synchronized (claims) {
            if (claims.containsKey(key)) throw new IOException("LOD store already owned: " + canonical);
            claims.setProperty(key, token);
            try {
                channel = FileChannel.open(canonical.resolve(".lss-store.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = channel.tryLock();
                if (lock == null) throw new IOException("LOD store owned by another process: " + canonical);
            } catch (IOException | RuntimeException failure) {
                if (channel != null) try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
                claims.remove(key, token);
                throw failure;
            }
        }
    }

    @Override public void close() throws IOException {
        synchronized (claims) {
            if (quarantined) throw new IOException("Uncertain native close; restart the JVM before reopening this store");
            if (channel == null) return;
            // Do not unlink the permanent sidecar: a second inode would allow two owners.
            channel.close();
            channel = null;
            lock = null;
            claims.remove(key, token);
        }
    }

    /** A failed native close cannot prove that its database handles are gone.
     * Keep the descriptor alive even if the failed store and plugin are discarded.
     * This exceptional daemon deliberately retains the defining loader until JVM
     * exit; normal close/reload never creates one. A shutdown hook is unsuitable:
     * hooks run concurrently and could release ownership while SQL is still live. */
    void retainUntilProcessExit() {
        synchronized (claims) {
            if (quarantined || channel == null) return;
            quarantined = true;
            Thread holder = new Thread(null, () -> {
                for (;;) {
                    java.util.concurrent.locks.LockSupport.park(this);
                    Thread.interrupted();
                }
            }, "LSS SQLite uncertain-close guard", 0, false);
            holder.setContextClassLoader(null);
            holder.setDaemon(true);
            holder.start();
            System.getLogger(StoreDirectoryLease.class.getName()).log(System.Logger.Level.WARNING,
                    "LOD store ownership retained after uncertain native close; JVM restart required: " + key);
        }
    }
}
