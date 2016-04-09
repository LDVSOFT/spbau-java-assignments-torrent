package ru.spbau.mit;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Created by ldvsoft on 04.04.16.
 */
public class TorrentTest {
    private static final Path EXAMPLE_PATH = Paths.get("src", "test", "resources", "checkstyle.xml");
    private static final long TIME_LIMIT = 10000000L;

    @Test
    public void testListAndUpload() throws Throwable {
        Catcher catcher1 = new Catcher(1);
        Catcher catcher2 = new Catcher(2);
        try (
                TorrentTracker tracker = new TorrentTracker("test/server");
                TorrentClient client1 = new TorrentClient("localhost", "test/client-01");
                TorrentClient client2 = new TorrentClient("localhost", "test/client-02")
        ) {
            client1.setCallbacks(catcher1);
            client2.setCallbacks(catcher2);

            assertAllCollectionEquals(Collections.emptyList(), client1.list(), client2.list());

            FileEntry entry1 = client1.newFile(EXAMPLE_PATH.toString());
            FileEntry entry2 = client2.newFile(EXAMPLE_PATH.toString());
            assertNotEquals("Should be different ids", entry1.getId(), entry2.getId());

            assertAllCollectionEquals(Arrays.asList(entry1, entry2), client1.list(), client2.list());
        } finally {
            if (catcher1.error != null) {
                throw new AssertionError("Exception in client1", catcher1.error);
            }
            if (catcher2.error != null) {
                throw new AssertionError("Exception in client2", catcher2.error);
            }
        }
    }

    @Test
    public void testListConsistency() throws Throwable {
        Catcher catcher = new Catcher(1);
        try (TorrentClient client = new TorrentClient("localhost", "test/client-01")) {
            client.setCallbacks(catcher);

            FileEntry entry;
            try (TorrentTracker tracker = new TorrentTracker("test/server")) {
                entry = client.newFile(EXAMPLE_PATH.toString());
            }

            List<FileEntry> list;
            try (TorrentTracker tracker = new TorrentTracker("test/server")) {
                list = client.list();
            }
            assertEquals(Collections.singletonList(entry), list);
        } finally {
            if (catcher.error != null) {
                throw new AssertionError("Exception in client1", catcher.error);
            }
        }
    }

    @Test(timeout = TIME_LIMIT)
    public void testDownload() throws Throwable {
        Catcher catcher1 = new Catcher(1);
        DownloadWaiter catcher2 = new DownloadWaiter(2);
        try (
                TorrentTracker tracker = new TorrentTracker("test/server");
                TorrentClient client1 = new TorrentClient("localhost", "test/client-01");
                TorrentClient client2 = new TorrentClient("localhost", "test/client-02")
        ) {
            client1.setCallbacks(catcher1);
            client2.setCallbacks(catcher2);

            FileEntry entry = client1.newFile(EXAMPLE_PATH.toString());
            assertTrue(client2.get(entry.getId()));

            // seeding
            client1.run();
            // leeching
            client2.run();
            synchronized (catcher2) {
                while (!catcher2.ready) {
                    catcher2.wait();
                }
            }

            assertTrue("Downloaded file is different!", FileUtils.contentEquals(
                    EXAMPLE_PATH.toFile(),
                    Paths.get(
                            "test",
                            "client-02",
                            "download",
                            Integer.toString(entry.getId()),
                            EXAMPLE_PATH.getFileName().toString()
                    ).toFile()
            ));
        } finally {
            if (catcher1.getError() != null) {
                throw new AssertionError("Exception in client1", catcher1.getError());
            }
            if (catcher2.getError() != null) {
                throw new AssertionError("Exception in client2", catcher2.getError());
            }
        }
    }

    @Before
    @After
    public void clear() throws IOException {
        clearDirectory("test");
    }

    private void clearDirectory(String name) throws IOException {
        Path path = Paths.get(name);
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new Deleter());
    }

    private void assertAllCollectionEquals(Collection<?> expected, Collection<?>... others) {
        for (Collection<?> other : others) {
            assertEquals(expected, other);
        }
    }

    private static class Deleter extends SimpleFileVisitor<Path> {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return super.visitFile(file, attrs);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return super.postVisitDirectory(dir, exc);
        }
    }

    private static class Catcher implements TorrentClient.StatusCallbacks {
        private Throwable error = null;
        private int id;

        protected Catcher(int id) {
            this.id = id;
        }

        protected Throwable getError() {
            return error;
        }

        private void submit(Throwable e) {
            if (e != null && error != null) {
                error = e;
            }
        }

        @Override
        public void onTrackerUpdated(boolean result, Throwable e) {
            submit(e);
            synchronized (System.err) {
                System.out.printf("%d | Tracker updated: result %d; e:\n", id, result ? 1 : 0);
                if (e != null) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onDownloadIssue(FileEntry entry, String message, Throwable e) {
            submit(e);
            synchronized (System.err) {
                System.out.printf(
                        "%d | %s (%d): download issue %s:\n",
                        id,
                        entry.getName(),
                        entry.getId(),
                        message
                );
                if (e != null) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onDownloadStart(FileEntry entry) {
        }

        @Override
        public void onDownloadPart(FileEntry entry, int partId) {
        }

        @Override
        public void onP2PServerIssue(Throwable e) {
            submit(e);
            synchronized (System.err) {
                System.out.printf("%d | P2P issue:\n", id);
                e.printStackTrace();
            }
        }

        @Override
        public void onDownloadComplete(FileEntry entry) {
            synchronized (System.err) {
                System.out.printf("%d | %s (%d): downloaded.\n", id, entry.getName(), entry.getId());
            }
        }
    }

    private static final class DownloadWaiter extends Catcher {
        private boolean ready = false;

        private DownloadWaiter(int id) {
            super(id);
        }

        @Override
        public void onDownloadComplete(FileEntry entry) {
            super.onDownloadComplete(entry);
            synchronized (this) {
                ready = true;
                notify();
            }
        }
    }
}
