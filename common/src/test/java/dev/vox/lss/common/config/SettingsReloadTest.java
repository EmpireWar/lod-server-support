package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsReloadTest {
    @TempDir Path directory;
    private SettingsHandle<ServerSettings> handle() throws Exception {
        return new SettingsHandle<>(new SettingsStore<>(directory, "lss", SettingsSchema.server(false)));
    }
    private void edit(SettingsHandle<ServerSettings> handle, Map<String,Object> changes) throws Exception {
        var doc = handle.store().read();
        handle.store().saveDraft(doc.hash(), changes);
    }
    @Test void diskEditIsInertThenOneReloadPublishesCorrelatedPolicy() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            edit(handle, Map.of("generation.concurrency.global", 3, "generation.concurrency.per_player", 50));
            assertEquals(40, handle.state().effective().generation().concurrency().global());
            var seen = new AtomicReference<ServerSettings>();
            var result = reload.reload(Runnable::run, (before, after, rev) -> {
                seen.set(after); return CompletableFuture.completedFuture(null);
            }).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.APPLIED, result.status());
            assertEquals(3, seen.get().generation().concurrency().global());
            assertEquals(3, seen.get().generation().concurrency().perPlayer());
            assertEquals(50, handle.state().configured().generation().concurrency().perPlayer());
        }
    }
    @Test void invalidYamlPreservesBothDiskAndActiveRevision() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            String broken = "config_version: 1\nnetwork:\n  unknown: true\n";
            Files.writeString(handle.store().path(), broken);
            var before = handle.state();
            assertThrows(ExecutionException.class, () -> reload.reload(Runnable::run,
                    (a,b,r) -> { fail("invalid candidate reconciled"); return null; }).get(30, TimeUnit.SECONDS));
            assertSame(before, handle.state());
            assertEquals(broken, Files.readString(handle.store().path()));
        }
    }
    @Test void pendingBootChangesNeverTriggerRuntimeEffectsOrLeakOnLaterReload() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            var calls = new AtomicInteger();
            SettingsReload.Reconciler<ServerSettings> reconcile = (a,b,r) -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); };
            edit(handle, Map.of("storage.lod_store.enabled", false));
            var pending = reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS);
            assertTrue(pending.commit().pendingRestart().contains("storage.lod_store.enabled"));
            assertTrue(handle.state().effective().storage().lodStore().enabled());
            assertFalse(handle.state().configured().storage().lodStore().enabled());
            assertEquals(0, calls.get());
            edit(handle, Map.of("network.send_queue_limit_per_player", 64));
            reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS);
            assertTrue(handle.state().effective().storage().lodStore().enabled());
            assertEquals(1, calls.get());
            reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "unchanged reload repeated side effects");
        }
    }
    @Test void busyPersistsUntilSubsystemAcknowledgesAndFailureDoesNotClaimRollback() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            edit(handle, Map.of("generation.enabled", false));
            var adopted = new CompletableFuture<Void>();
            var entered = new CountDownLatch(1);
            var first = reload.reload(Runnable::run, (a,b,r) -> { entered.countDown(); return adopted; });
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            assertFalse(handle.state().effective().generation().enabled());
            assertThrows(ExecutionException.class, () -> reload.reload(Runnable::run,
                    (a,b,r) -> CompletableFuture.completedFuture(null)).get(30, TimeUnit.SECONDS));
            adopted.completeExceptionally(new IllegalStateException("store closed"));
            assertEquals(SettingsReload.Status.RECONCILIATION_FAILED, first.get(30, TimeUnit.SECONDS).status());
            assertFalse(handle.state().effective().generation().enabled());
        }
    }
    @Test void identicalReloadRetriesFailedOwnerThenBecomesNoop() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            edit(handle, Map.of("generation.enabled", false));
            var attempts = new AtomicInteger();
            SettingsReload.Reconciler<ServerSettings> reconcile = (a,b,r) -> {
                assertTrue(a.generation().enabled());
                assertFalse(b.generation().enabled());
                return attempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("owner unavailable"))
                        : CompletableFuture.completedFuture(null);
            };
            var failed = reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.RECONCILIATION_FAILED, failed.status());
            assertNotNull(handle.pendingAdoption());
            assertEquals(0, handle.adoptedRevision());
            var retried = reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.APPLIED, retried.status());
            assertEquals(failed.revision(), retried.revision(), "retry must not create a new accepted policy");
            assertTrue(retried.commit().changedPaths().contains("generation.enabled"));
            assertEquals(2, attempts.get());
            assertNull(handle.pendingAdoption());
            assertEquals(retried.revision(), handle.adoptedRevision());
            assertEquals(SettingsReload.Status.UNCHANGED,
                    reload.reload(Runnable::run, reconcile).get(30, TimeUnit.SECONDS).status());
            assertEquals(2, attempts.get());
        }
    }
    @Test void changedCandidateAndRevertRetainAllPartialOwnerWork() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle)) {
            edit(handle, Map.of("generation.enabled", false));
            var oldAdoption = new AtomicReference<SettingsHandle.Adoption<ServerSettings>>();
            var result = reload.reload(Runnable::run, (a,b,r) -> {
                oldAdoption.set(handle.pendingAdoption());
                // One owner may already have adopted B when another owner throws.
                throw new IllegalStateException("partial adoption");
            }).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.RECONCILIATION_FAILED, result.status());
            edit(handle, Map.of("generation.enabled", true, "network.send_pacing", false));
            var repaired = reload.reload(Runnable::run, (a,b,r) -> {
                assertTrue(b.generation().enabled());
                assertFalse(b.network().sendPacing());
                handle.acknowledge(oldAdoption.get());
                assertNotNull(handle.pendingAdoption(), "old receipt cleared a newer target");
                assertTrue(handle.pendingAdoption().paths().contains("generation.enabled"));
                return CompletableFuture.completedFuture(null);
            }).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.APPLIED, repaired.status());
            assertEquals(2, repaired.commit().changedPaths().size());
            assertNull(handle.pendingAdoption());
        }
    }
    @Test void ownerRejectionAndShutdownAlwaysCompleteAndLateTaskCannotCommit() throws Exception {
        var handle = handle();
        var reload = new SettingsReload<>(handle);
        edit(handle, Map.of("generation.enabled", false));
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var ownerReceipt = new CompletableFuture<Void>();
        var result = reload.reloadOwned(action -> { queue.add(action); return ownerReceipt; },
                (a,b,r) -> CompletableFuture.completedFuture(null));
        Runnable action = queue.poll(30, TimeUnit.SECONDS);
        assertNotNull(action);
        ownerReceipt.completeExceptionally(new RejectedExecutionException("owner stopped"));
        assertThrows(ExecutionException.class, () -> result.get(30, TimeUnit.SECONDS));
        reload.close();
        action.run();
        assertTrue(handle.state().effective().generation().enabled());
    }
    @Test void deadlineAfterPublicationReportsPendingAndAllowsAdoptionToFinish() throws Exception {
        var handle = handle();
        try (var reload = new SettingsReload<>(handle, 1000)) {
            edit(handle, Map.of("network.send_pacing", false));
            var adopted = new CompletableFuture<Void>();
            var result = reload.reload(Runnable::run, (a,b,r) -> adopted).get(30, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.ADOPTION_PENDING, result.status());
            assertTrue(reload.busy());
            assertFalse(handle.state().effective().network().sendPacing());
            adopted.complete(null);
            assertFalse(reload.busy());
            assertNull(handle.pendingAdoption());
            assertEquals(handle.state().revision(), handle.adoptedRevision());
            assertEquals(SettingsReload.Status.ADOPTION_PENDING, result.status());
        }
    }
}
