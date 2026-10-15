package com.sevtinge.hyperceiler.hook.utils.prefs;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RemotePrefsMirrorTest {
    @Test
    public void connectReplacesAllRemoteKeysAndPreservesEveryPreferenceType() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        local.edit().putBoolean("allow_hook", true).putInt("count", 42)
            .putLong("large", 8_000_000_001L).putFloat("scale", 1.25f)
            .putString("text", "中文/a").putStringSet("packages", Set.of("a", "b"))
            .putStringSet("empty", Set.of()).commit();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.edit().putString("obsolete", "must disappear").commit();

        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local)) {
            assertTrue(mirror.connect(remote));
            assertEquals(local.getAll(), remote.getAll());
            assertFalse(remote.contains("obsolete"));
            assertEquals(Boolean.class, remote.getAll().get("allow_hook").getClass());
            assertEquals(Integer.class, remote.getAll().get("count").getClass());
            assertEquals(Long.class, remote.getAll().get("large").getClass());
            assertEquals(Float.class, remote.getAll().get("scale").getClass());
            assertEquals(String.class, remote.getAll().get("text").getClass());
            assertEquals(Set.of(), remote.getStringSet("empty", null));
        }
    }

    @Test
    public void editRemoveAndClearImmediatelyPublishFullSnapshot() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local)) {
            mirror.connect(remote);
            local.edit().putBoolean("allow_hook", true).putString("removed", "x").apply();
            assertEquals(local.getAll(), remote.getAll());
            local.edit().remove("removed").commit();
            assertFalse(remote.contains("removed"));
            local.edit().clear().commit();
            assertTrue(remote.getAll().isEmpty());
        }
    }

    @Test
    public void disconnectRetainsEditsAndStaleDisconnectCannotRemoveNewBinding() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        MirrorFakeSharedPreferences oldRemote = new MirrorFakeSharedPreferences();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local)) {
            mirror.connect(oldRemote);
            mirror.connect(remote);
            mirror.disconnect(oldRemote);
            local.edit().putInt("current", 3).commit();
            assertEquals(3, remote.getInt("current", -1));

            mirror.disconnect(remote);
            int commits = remote.commitCount;
            local.edit().remove("current").putString("offline", "saved").commit();
            assertEquals(commits, remote.commitCount);
            assertEquals("saved", local.getString("offline", null));
            assertTrue(mirror.connect(remote));
            assertEquals(local.getAll(), remote.getAll());
            assertFalse(remote.contains("current"));
        }
    }

    @Test
    public void commitFalseIsReportedAndNextLocalChangeRetriesLatestSnapshot() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        local.edit().putBoolean("allow_hook", true).commit();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.failCommit = true;
        RecordingListener listener = new RecordingListener();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local, listener)) {
            assertFalse(mirror.connect(remote));
            assertEquals(0, listener.successes);
            assertEquals(1, listener.failures);
            assertNotNull(listener.error);
            assertTrue(local.getBoolean("allow_hook", false));

            remote.failCommit = false;
            local.edit().putInt("new", 9).commit();
            assertEquals(local.getAll(), remote.getAll());
            assertEquals(1, listener.successes);
        }
    }

    @Test
    public void remoteExceptionDoesNotEscapeLocalEditAndReconnectRetries() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        RecordingListener listener = new RecordingListener();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local, listener)) {
            assertTrue(mirror.connect(remote));
            remote.commitException = new IllegalStateException("binder died");
            assertTrue(local.edit().putString("offline", "preserved").commit());
            assertEquals("preserved", local.getString("offline", null));
            assertEquals(1, listener.failures);
            assertEquals("binder died", listener.error.getMessage());

            remote.commitException = null;
            assertTrue(mirror.connect(remote));
            assertEquals(local.getAll(), remote.getAll());
        }
    }

    @Test
    public void mirroredStringSetsDoNotShareMutableReferences() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        Set<String> source = new LinkedHashSet<>(List.of("one", "two"));
        local.edit().putStringSet("set", source).commit();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local)) {
            assertTrue(mirror.connect(remote));
            Set<String> published = remote.getStringSet("set", null);
            assertNotSame(source, published);
            source.add("later");
            assertEquals(Set.of("one", "two"), published);
        }
    }

    @Test
    public void closeUnregistersListenerAndStopsPublishing() {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        RemotePrefsMirror mirror = new RemotePrefsMirror(local);
        assertEquals(1, local.listenerCount());
        assertTrue(mirror.connect(remote));
        mirror.close();
        mirror.close();
        assertEquals(0, local.listenerCount());
        int commits = remote.commitCount;
        local.edit().putInt("after_close", 1).commit();
        assertEquals(commits, remote.commitCount);
        assertFalse(mirror.refresh());
    }

    @Test
    public void concurrentUpdatesCannotCommitOlderSnapshotAfterNewerSnapshot() throws Exception {
        MirrorFakeSharedPreferences local = new MirrorFakeSharedPreferences();
        local.edit().putInt("version", 1).commit();
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        CountDownLatch firstCommitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        remote.beforeCommit = () -> {
            if (remote.commitCount == 1) {
                firstCommitEntered.countDown();
                await(releaseCommit);
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (RemotePrefsMirror mirror = new RemotePrefsMirror(local)) {
            Thread connect = thread(() -> assertTrue(mirror.connect(remote)), failure);
            Thread update = thread(() -> local.edit().putInt("version", 2).commit(), failure);
            connect.start();
            try {
                assertTrue(firstCommitEntered.await(5, TimeUnit.SECONDS));
                update.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (local.getInt("version", -1) != 2 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals(2, local.getInt("version", -1));
            } finally {
                releaseCommit.countDown();
                connect.join(5000);
                update.join(5000);
            }
            assertFalse("connect must finish", connect.isAlive());
            assertFalse("update must finish", update.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(2, remote.getInt("version", -1));
            assertEquals(1, remote.maximumConcurrentCommits);
        }
    }

    private static Thread thread(Runnable task, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class RecordingListener implements RemotePrefsMirror.Listener {
        int successes;
        int failures;
        Throwable error;

        @Override
        public void onSyncSuccess() {
            successes++;
        }

        @Override
        public void onSyncFailure(Throwable error) {
            failures++;
            this.error = error;
        }
    }
}
