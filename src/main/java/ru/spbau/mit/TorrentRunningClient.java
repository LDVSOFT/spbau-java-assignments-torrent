package ru.spbau.mit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ru.spbau.mit.TorrentP2PConnection.REQUEST_GET;
import static ru.spbau.mit.TorrentP2PConnection.REQUEST_STAT;
import static ru.spbau.mit.TorrentTrackerConnection.UPDATE_DELAY;

/**
 * Created by ldvsoft on 26.04.16.
 */
public class TorrentRunningClient extends TorrentClientBase {
    private static final long REST_DELAY = 1000;

    public interface RunCallbacks {
        void onTrackerUpdated(boolean result, Throwable e);
        void onDownloadIssue(FileEntry entry, String message, Throwable e);
        void onDownloadStart(FileEntry entry);
        void onDownloadPart(FileEntry entry, int partId);
        void onDownloadComplete(FileEntry entry);
        void onP2PServerIssue(Throwable e);
    }

    private volatile RunCallbacks callbacks = null;
    private boolean isRunning = false;
    private volatile ServerSocket serverSocket;
    private volatile ExecutorService threadPool;
    private volatile ScheduledExecutorService scheduler;

    public TorrentRunningClient(TorrentClientState state) {
        super(state);
    }

    public void startRun(RunCallbacks callbacks) throws IOException {
        try {
            isRunning = true;
            this.callbacks = callbacks;

            threadPool = Executors.newCachedThreadPool();
            scheduler = Executors.newScheduledThreadPool(1);

            // Starting downloaders
            try (LockHandler handler1 = LockHandler.lock(state.lock.readLock())) {
                for (TorrentClientState.FileState fileState : state.files.values()) {
                    if (fileState.parts.getCount() == fileState.entry.getPartsCount()) {
                        continue;
                    }
                    threadPool.submit(() -> download(fileState));
                }
            }

            // Starting seeding server
            serverSocket = new ServerSocket(0);
            threadPool.submit(this::server);

            // Starting tracking update loop
            scheduler.scheduleAtFixedRate(this::updateTracker, 0, UPDATE_DELAY, TimeUnit.MILLISECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (IOException e) {
            threadPool.shutdownNow();
            scheduler.shutdownNow();
            isRunning = false;
            throw e;
        }
    }

    public void shutdown() {
        try {
            if (!isRunning) {
                return;
            }
            serverSocket.close();
            threadPool.shutdown();
            scheduler.shutdown();
            state.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<InetSocketAddress> fetchSources(Collection<Integer> files) throws IOException {
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeSourcesRequest(files);
            return connection.readSourcesResponse();
        }
    }

    private PartsSet stat(InetSocketAddress seeder, TorrentClientState.FileState state) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeStatRequest(state.entry.getId());
            return connection.readStatResponse(state.entry.getPartsCount());
        }
    }

    private void get(InetSocketAddress seeder, TorrentClientState.FileState state, int partId) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeGetRequest(new GetRequest(state.entry.getId(), partId));
            try (RandomAccessFile file = new RandomAccessFile(state.localPath.toString(), "rw")) {
                connection.readGetResponse(file, partId, state.entry);
            }
        }
    }

    // Seeding part: handling requests

    private boolean update(int port) throws IOException {
        List<Integer> availableFiles;
        try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
            availableFiles = state.files
                    .values()
                    .stream()
                    .filter(fileState -> {
                        try (LockHandler handler1 = LockHandler.lock(fileState.fileLock.readLock())) {
                            return fileState.parts.getCount() > 0;
                        }
                    })
                    .map(fileState -> fileState.entry.getId())
                    .collect(Collectors.toList());
        }
        ClientInfo info = new ClientInfo(new InetSocketAddress("", port), availableFiles);
        try (TorrentTrackerConnection trackerConnection = connectToTracker()) {
            trackerConnection.writeUpdateRequest(info);
            return trackerConnection.readUpdateResponse();
        }
    }

    private void server() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> handle(socket));
            } catch (IOException e) {
                notifyP2PServerIssue(e);
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (TorrentP2PConnection connection = new TorrentP2PConnection(socket)) {
            int request = connection.readRequest();
            switch (request) {
                case REQUEST_STAT:
                    doStat(connection);
                    break;
                case REQUEST_GET:
                    doGet(connection);
                    break;
                default:
                    throw new IllegalArgumentException(
                            String.format("Wrong request %d from connection.", request)
                    );
            }
        } catch (Exception e) {
            notifyP2PServerIssue(e);
        }
    }

    private void doStat(TorrentP2PConnection connection) throws IOException {
        int fileId = connection.readStatRequest();
        TorrentClientState.FileState fileState;
        try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
            fileState = state.files.get(fileId);
        }
        try (LockHandler handler = LockHandler.lock(fileState.fileLock.readLock())) {
            connection.writeStatResponse(fileState.parts);
        }
    }

    private void doGet(TorrentP2PConnection connection) throws IOException {
        GetRequest request = connection.readGetRequest();
        TorrentClientState.FileState fileState;
        try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
            fileState = state.files.get(request.getFileId());
        }
        try (LockHandler handler = LockHandler.lock(fileState.fileLock.readLock())) {
            if (!fileState.parts.get(request.getPartId())) {
                throw new IllegalArgumentException("Cannot perform get on missing file part.");
            }
        }
        // We already checked that file has requested part, just read it without locking
        try (RandomAccessFile file = new RandomAccessFile(fileState.localPath.toString(), "r")) {
            connection.writeGetResponse(file, request.getPartId(), fileState.entry);
        }
    }

    // Leeching part

    private void updateTracker() {
        try (LockHandler handler1 = LockHandler.lock(state.lock.readLock())) {
            if (!isRunning) {
                return;
            }
        }
        try {
            boolean result = update(serverSocket.getLocalPort());
            notifyTrackerUpdated(result, null);
        } catch (IOException e) {
            notifyTrackerUpdated(false, e);
        }
    }

    private void download(TorrentClientState.FileState state) {
        List<InetSocketAddress> seeders = null;
        int currentSeeder = 0;
        PartsSet partsToDownload = null;
        int canOffer = 0;
        notifyDownloadStart(state.entry);
        while (true) {
            try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
                if (!isRunning) {
                    return;
                }
                if (state.parts.getCount() == state.entry.getPartsCount()) {
                    notifyDownloadComplete(state.entry);
                    return;
                }
            }

            if (seeders == null || seeders.size() == 0) {
                try {
                    seeders = fetchSources(Collections.singletonList(state.entry.getId()));
                    currentSeeder = -1;
                    canOffer = 0;
                } catch (IOException e) {
                    notifyDownloadIssue(state.entry, "Failed to fetch seeders.", e);
                    delay(REST_DELAY);
                    continue;
                }
            }
            if (seeders == null || seeders.size() == 0) {
                notifyDownloadIssue(state.entry, "No seeders.", null);
                delay(REST_DELAY);
                continue;
            }

            if (canOffer == 0 && currentSeeder + 1 < seeders.size()) {
                currentSeeder++;
                try {
                    partsToDownload = stat(seeders.get(currentSeeder), state);
                } catch (IOException e) {
                    notifyDownloadIssue(state.entry, String.format(
                            "Failed to stat seeder %s, skipping...",
                            seeders.get(currentSeeder).toString()
                    ), e);
                    continue;
                }
                try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
                    partsToDownload.subtract(state.parts);
                }
                canOffer = partsToDownload.getCount();
            }

            if (canOffer == 0) {
                if (currentSeeder == seeders.size() - 1) {
                    seeders = null;
                }
                notifyDownloadIssue(state.entry, "Noone seeds remaining parts.", null);
                delay(REST_DELAY);
                continue;
            }

            int partId = 0;
            if (canOffer > 0) {
                partId = partsToDownload.getFirstBitAtLeast(partId);
                try {
                    get(seeders.get(currentSeeder), state, partId);
                } catch (IOException e) {
                    notifyDownloadIssue(state.entry, String.format(
                            "Download error: part %d from %s.",
                            partId,
                            seeders.get(currentSeeder).toString()
                    ), e);
                    delay(REST_DELAY);
                }
                boolean needUpdateTracker = false;
                try (LockHandler handler = LockHandler.lock(state.fileLock.writeLock())) {
                    state.parts.set(partId, true);
                    if (state.parts.getCount() == 1) {
                        needUpdateTracker = true;
                    }
                }
                partsToDownload.set(partId, false);
                canOffer--;
                if (needUpdateTracker) {
                    updateTracker();
                }
                notifyDownloadPart(state.entry, partId);
            }
        }
    }

    // Utils

    private TorrentP2PConnection connectToSeeder(InetSocketAddress seeder) throws IOException {
        return new TorrentP2PConnection(new Socket(seeder.getAddress(), seeder.getPort()));
    }

    private void delay(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ignored) {
        }
    }

    private void notifyTrackerUpdated(boolean result, Throwable e) {
        if (callbacks != null) {
            callbacks.onTrackerUpdated(result, e);
        }
    }

    private void notifyDownloadIssue(FileEntry entry, String message, Throwable e) {
        if (callbacks != null) {
            callbacks.onDownloadIssue(entry, message, e);
        }
    }

    private void notifyDownloadComplete(FileEntry entry) {
        if (callbacks != null) {
            callbacks.onDownloadComplete(entry);
        }
    }

    private void notifyP2PServerIssue(Throwable e) {
        if (callbacks != null) {
            callbacks.onP2PServerIssue(e);
        }
    }

    private void notifyDownloadStart(FileEntry entry) {
        if (callbacks != null) {
            callbacks.onDownloadStart(entry);
        }
    }

    private void notifyDownloadPart(FileEntry entry, int partId) {
        if (callbacks != null) {
            callbacks.onDownloadPart(entry, partId);
        }
    }

}
