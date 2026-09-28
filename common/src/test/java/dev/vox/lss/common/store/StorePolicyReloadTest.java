package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StorePolicyReloadTest {
    @TempDir Path directory;

    private SqliteLodStore store() throws Exception {
        var store = SqliteLodStore.createOrNull(LodStoreMode.FULL,
                new SqliteLodStore.Environment(directory.resolve("store"), "reload-test", 18,
                        dim -> directory.resolve("region"), dim -> "", 0), new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        return store;
    }

    private void region() throws Exception {
        Files.createDirectories(directory.resolve("region"));
        var header = ByteBuffer.allocate(8192);
        header.putInt(0, 513);
        header.putInt(4, 769);
        Files.write(directory.resolve("region/r.0.0.mca"), header.array());
    }

    @Test void storeAdoptionChangesCapWithoutReopeningAndRejectsStaleRevision() throws Exception {
        var store = store();
        try {
            store.updatePolicy(1024 * 1024, 30, 2).get(10, TimeUnit.SECONDS);
            assertEquals(1024 * 1024, store.sizeCapBytes());
            assertEquals(2, store.adoptedPolicyRevision());
            long firstDeadline = store.nextResweepNanosForTest();
            store.updatePolicy(2 * 1024 * 1024, 30, 3).get(10, TimeUnit.SECONDS);
            assertEquals(firstDeadline, store.nextResweepNanosForTest(), "cap-only edits retain the sweep deadline");
            store.updatePolicy(2 * 1024 * 1024, 60, 4).get(10, TimeUnit.SECONDS);
            assertTrue(store.nextResweepNanosForTest() > firstDeadline, "lengthening reschedules from adoption");
            store.updatePolicy(2 * 1024 * 1024, 5, 5).get(10, TimeUnit.SECONDS);
            assertTrue(store.nextResweepNanosForTest() < firstDeadline, "shortening reschedules from adoption");
            store.updatePolicy(0, 0, 6).get(10, TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, store.sizeCapBytes());
            assertEquals(Long.MAX_VALUE, store.nextResweepNanosForTest(), "zero removes the periodic deadline");
            assertThrows(Exception.class, () -> store.updatePolicy(1024, 1, 1).get(10, TimeUnit.SECONDS));
            assertEquals(Long.MAX_VALUE, store.sizeCapBytes());
            assertTrue(store.isHealthy());
        } finally { store.shutdown(); }
        assertTrue(store.updatePolicy(1, 1, 7).isCompletedExceptionally());
    }

    @Test void backfillOffOnDuringReadStartsOneSuccessorAndManualPauseSurvivesRateEdit() throws Exception {
        region();
        var store = store();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var worker = new StoreBackfill(store, dim -> directory.resolve("region"), dim -> new long[]{0, 0},
                List.of("minecraft:overworld"), (dim, x, z) -> {
                    if (reads.incrementAndGet() == 1) {
                        entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    }
                    return new byte[]{1, 2, 3};
                }, () -> true, () -> true, 1000);
        try {
            assertTrue(worker.start());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var off = worker.updatePolicy(false, 1000, 1);
            assertFalse(off.isDone(), "active read must finish without interruption");
            var on = worker.updatePolicy(true, 1000, 2);
            assertTrue(off.isCompletedExceptionally(), "the obsolete receipt is terminal");
            release.countDown();
            on.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (worker.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(worker.isRunning());
            assertTrue(reads.get() >= 2 && reads.get() <= 3,
                    "one successor covers both columns; an uncommitted deposit may be read again");
            worker.stop();
            worker.updatePolicy(true, 5, 3).get(10, TimeUnit.SECONDS);
            assertFalse(worker.isRunning(), "rate-only reload does not undo an operational pause");
            worker.updatePolicy(false, 5, 4).get(10, TimeUnit.SECONDS);
            assertFalse(worker.start(), "master enable gate constrains operational starts");
        } finally { release.countDown(); worker.shutdown(); store.shutdown(); }
        assertTrue(worker.updatePolicy(true, 100, 5).isCompletedExceptionally());
    }
    @Test void failedBackfillEnableRetainsStartIntentForSameRevisionRetry() throws Exception {
        region();
        var store = store();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new StoreBackfill(store, dim -> directory.resolve("region"), dim -> new long[]{0, 0},
                List.of("minecraft:overworld"), (dim, x, z) -> {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return new byte[]{1, 2, 3};
                }, () -> true, () -> true, 1000);
        var serving = SqliteLodStore.class.getDeclaredField("serving");
        serving.setAccessible(true);
        try {
            worker.updatePolicy(false, 1000, 1).get(10, TimeUnit.SECONDS);
            // Reproduce the startup-sweep health boundary without timing a real sweep.
            serving.setBoolean(store, false);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> worker.updatePolicy(true, 1000, 2).get(10, TimeUnit.SECONDS));
            assertFalse(worker.isRunning());
            serving.setBoolean(store, true);
            worker.updatePolicy(true, 1000, 2).get(10, TimeUnit.SECONDS);
            assertTrue(entered.await(10, TimeUnit.SECONDS), "retry must fulfill the failed start intent");
            assertTrue(worker.isRunning());
            worker.stop();
            worker.updatePolicy(true, 500, 3);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(worker.isRunning(), "manual pause still cancels successor intent");
            worker.updatePolicy(true, 500, 3).get(10, TimeUnit.SECONDS);
            assertFalse(worker.isRunning(), "successful repeated policy must not restart a paused run");
        } finally {
            serving.setBoolean(store, true);
            release.countDown();
            worker.shutdown();
            store.shutdown();
        }
    }

    @Test void failedOffOnSuccessorIsReportedAndRetriedAfterHealthRecovers() throws Exception {
        region();
        var store = store();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restarted = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var worker = new StoreBackfill(store, dim -> directory.resolve("region"), dim -> new long[]{0, 0},
                List.of("minecraft:overworld"), (dim, x, z) -> {
                    if (reads.incrementAndGet() == 1) {
                        entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } else restarted.countDown();
                    return new byte[]{1, 2, 3};
                }, () -> true, () -> true, 1000);
        var serving = SqliteLodStore.class.getDeclaredField("serving");
        serving.setAccessible(true);
        try {
            assertTrue(worker.start());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            worker.updatePolicy(false, 1000, 1);
            var enabled = worker.updatePolicy(true, 1000, 2);
            serving.setBoolean(store, false);
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> enabled.get(10, TimeUnit.SECONDS), "acknowledgment must include successor startup");
            assertFalse(worker.isRunning());
            serving.setBoolean(store, true);
            worker.updatePolicy(true, 1000, 2).get(10, TimeUnit.SECONDS);
            assertTrue(restarted.await(10, TimeUnit.SECONDS));
        } finally {
            serving.setBoolean(store, true);
            release.countDown();
            worker.shutdown();
            store.shutdown();
        }
    }

    @Test void queuedStoreAdoptionGetsTerminalCancellationDuringShutdown() throws Exception {
        var store = store();
        var permit = store.pauseBatcherForTest();
        // The batcher may finish one poll already in progress; enough updates ensure
        // an acknowledgement is still queued behind the held owner boundary.
        var receipts = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int i = 1; i <= 5; i++) receipts.add(store.updatePolicy(1024 * i, i, i));
        store.shutdown();
        for (var receipt : receipts) assertTrue(receipt.isDone(), "shutdown cannot silently discard adoption receipts");
        assertTrue(receipts.getLast().isCompletedExceptionally());
        permit.release();
    }

    @Test void rateEditsPreserveAlreadyChargedWorkUntilTheSameWindowExpires() {
        var window = new StoreBackfill.RateWindow(0);
        assertTrue(window.tryAcquire(10, 3));
        assertTrue(window.tryAcquire(20, 3));
        assertFalse(window.tryAcquire(30, 1), "lowering below charged work blocks the next column");
        assertTrue(window.tryAcquire(40, 3), "raising exposes only the remaining allowance");
        assertFalse(window.tryAcquire(50, 3), "rate increase cannot mint a new window burst");
        assertTrue(window.tryAcquire(1_000_000_000L, 1));
        assertFalse(window.tryAcquire(1_000_000_001L, 1));
        assertEquals(1, window.completedWindows());
    }

}
