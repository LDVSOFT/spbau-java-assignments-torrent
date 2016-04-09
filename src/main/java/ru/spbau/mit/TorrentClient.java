package ru.spbau.mit;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static ru.spbau.mit.TorrentP2PConnection.REQUEST_GET;
import static ru.spbau.mit.TorrentP2PConnection.REQUEST_STAT;
import static ru.spbau.mit.TorrentTrackerConnection.TRACKER_PORT;
import static ru.spbau.mit.TorrentTrackerConnection.UPDATE_DELAY;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentClient implements AutoCloseable {
    private static final long REST_DELAY = 1000;
    private static final String STATE_FILE = "client-state.dat";
    private static final String DOWNLOADS_DIR = "downloads";

    private static final class FileState {
        private ReadWriteLock fileLock = new ReentrantReadWriteLock();
        private FileEntry entry;
        private PartsSet parts;
        private String localPath;

        private FileState(FileEntry entry, String localPath, String workingDir) throws IOException {
            this(entry, new PartsSet(entry.getPartsCount(), localPath != null), localPath, workingDir);
        }

        private FileState(
                FileEntry entry,
                PartsSet parts,
                String localPath,
                String workingDir
        ) throws IOException {
            this.entry = entry;
            this.parts = parts;
            if (localPath == null) {
                this.localPath = Paths.get(
                        workingDir,
                        DOWNLOADS_DIR,
                        Integer.toString(entry.getId()),
                        entry.getName()
                ).toString();
                Files.createDirectories(Paths.get(this.localPath).getParent());
                try (RandomAccessFile file = new RandomAccessFile(this.localPath, "rw")) {
                    file.setLength(entry.getSize());
                }
            } else {
                this.localPath = localPath;
            }
        }

        private void writeTo(DataOutputStream dos) throws IOException {
            entry.writeTo(dos);
            parts.writeTo(dos);
            dos.writeUTF(localPath);
        }

        private static FileState readFrom(DataInputStream dis) throws IOException {
            FileEntry fileEntry = FileEntry.readFrom(dis, true);
            PartsSet parts = PartsSet.readFrom(dis, fileEntry.getPartsCount());
            String localPath = dis.readUTF();
            return new FileState(fileEntry, parts, localPath, null);
        }
    }

    public interface StatusCallbacks {
        void onTrackerUpdated(boolean result, Throwable e);
        void onDownloadIssue(FileEntry entry, String message, Throwable e);
        void onDownloadStart(FileEntry entry);
        void onDownloadPart(FileEntry entry, int partId);
        void onDownloadComplete(FileEntry entry);
        void onP2PServerIssue(Throwable e);
    }

    private String workingDir;
    private ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<Integer, FileState> files;
    private String host;

    private StatusCallbacks callbacks = null;
    // For run
    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private ScheduledExecutorService scheduler;

    public TorrentClient(String host, String workingDir) throws IOException {
        this.host = host;
        this.workingDir = workingDir;
        load();
    }

    @Override
    public void close() throws IOException {
        if (isRunning) {
            try (LockHandler handler = LockHandler.lock(lock.writeLock())) {
                isRunning = false;
                serverSocket.close();
            }
            threadPool.shutdown();
            scheduler.shutdown();
        }
        store();
    }

    public void setCallbacks(StatusCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    /*
     * User-land methods: list, get, newfile and run
     */

    public List<FileEntry> list() throws IOException {
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeListRequest();
            return connection.readListResponse();
        }
    }

    public boolean get(int id) throws IOException {
        if (files.containsKey(id)) {
            return false;
        }
        FileEntry serverEntry = list().stream()
                .filter(entry -> entry.getId() == id)
                .findAny().orElse(null);
        if (serverEntry == null) {
            return false;
        }
        try (LockHandler handler = LockHandler.lock(lock.writeLock())) {
            files.put(id, new FileState(serverEntry, null, workingDir));
        }
        return true;
    }

    public FileEntry newFile(String pathString) throws IOException {
        Path path = Paths.get(pathString);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        FileEntry newEntry = new FileEntry(path.getFileName().toString(), Files.size(path));
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeUploadRequest(newEntry);
            int newId = connection.readUploadResponse();
            newEntry.setId(newId);
        }
        FileState newState = new FileState(newEntry, pathString, null);
        try (LockHandler handler = LockHandler.lock(lock.writeLock())) {
            files.put(newEntry.getId(), newState);
        }
        return newEntry;
    }

    public void run() throws IOException {
        try (LockHandler handler = LockHandler.lock(lock.writeLock())) {
            isRunning = true;

            threadPool = Executors.newCachedThreadPool();
            scheduler = Executors.newScheduledThreadPool(1);

            // Starting downloaders
            for (FileState state : files.values()) {
                if (state.parts.getCount() == state.entry.getPartsCount()) {
                    continue;
                }
                threadPool.submit(() -> download(state));
            }

            // Starting seeding server
            serverSocket = new ServerSocket(0);
            threadPool.submit(this::server);

            // Starting tracking update loop
            scheduler.scheduleAtFixedRate(this::updateTracker, 0, UPDATE_DELAY, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            threadPool.shutdownNow();
            scheduler.shutdownNow();
            isRunning = false;
            throw e;
        }
    }

    // Protocol requests

    private List<InetSocketAddress> sources(Collection<Integer> files) throws IOException {
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeSourcesRequest(files);
            return connection.readSourcesResponse();
        }
    }

    private PartsSet stat(InetSocketAddress seeder, FileState state) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeStatRequest(state.entry.getId());
            return connection.readStatResponse(state.entry.getPartsCount());
        }
    }

    private void get(InetSocketAddress seeder, FileState state, int partId) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeGetRequest(new GetRequest(state.entry.getId(), partId));
            try (RandomAccessFile file = new RandomAccessFile(state.localPath, "rw")) {
                connection.readGetResponse(file, partId, state.entry);
            }
        }
    }

    // Seeding part: handling requests

    private boolean update(int port) throws IOException {
        List<Integer> availableFiles;
        try (LockHandler handler = LockHandler.lock(lock.readLock())) {
            availableFiles = files
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
            e.printStackTrace();
            notifyP2PServerIssue(e);
        }
    }

    private void doStat(TorrentP2PConnection connection) throws IOException {
        int fileId = connection.readStatRequest();
        FileState state;
        try (LockHandler handler = LockHandler.lock(lock.readLock())) {
            state = files.get(fileId);
        }
        try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
            connection.writeStatResponse(state.parts);
        }
    }

    private void doGet(TorrentP2PConnection connection) throws IOException {
        GetRequest request = connection.readGetRequest();
        FileState state;
        try (LockHandler handler = LockHandler.lock(lock.readLock())) {
            state = files.get(request.getFileId());
        }
        try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
            if (!state.parts.get(request.getPartId())) {
                throw new IllegalArgumentException("Cannot perform get on missing file part.");
            }
        }
        // We already checked that file has requested part, just read it without locking
        try (RandomAccessFile file = new RandomAccessFile(state.localPath, "r")) {
            connection.writeGetResponse(file, request.getPartId(), state.entry);
        }
    }

    // Leeching part

    private void updateTracker() {
        try (LockHandler handler1 = LockHandler.lock(lock.readLock())) {
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

    private void download(FileState state) {
        List<InetSocketAddress> seeders = null;
        int currentSeeder = 0;
        PartsSet seederParts = null;
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
                    seeders = sources(Collections.singletonList(state.entry.getId()));
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
                    seederParts = stat(seeders.get(currentSeeder), state);
                } catch (IOException e) {
                    notifyDownloadIssue(state.entry, String.format(
                            "Failed to stat seeder %s, skipping...",
                            seeders.get(currentSeeder).toString()
                    ), e);
                    continue;
                }
                try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
                    seederParts.subtract(state.parts);
                }
                canOffer = seederParts.getCount();
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
                partId = seederParts.getFirstBitAtLeast(partId);
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
                seederParts.set(partId, false);
                canOffer--;
                if (needUpdateTracker) {
                    updateTracker();
                }
                notifyDownloadPart(state.entry, partId);
            }
        }
    }

    // Utils

    private void store() throws IOException {
        Path state = Paths.get(workingDir, STATE_FILE);
        if (!Files.exists(state)) {
            Files.createDirectories(Paths.get(workingDir));
            Files.createFile(state);
        }
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(state))) {
            IOUtils.writeCollection(files.values(), (dos1, o) -> o.writeTo(dos1), dos);
        }
    }

    private void load() throws IOException {
        Path state = Paths.get(workingDir, STATE_FILE);
        if (Files.exists(state)) {
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(state))) {
                int size = dis.readInt();
                files = new HashMap<>(size);
                while (size > 0) {
                    --size;
                    FileState fs = FileState.readFrom(dis);
                    files.put(fs.entry.getId(), fs);
                }
            }
        } else {
            // Empty state
            files = new HashMap<>();
        }
    }

    private TorrentTrackerConnection connectToTracker() throws IOException {
        return new TorrentTrackerConnection(new Socket(host, TRACKER_PORT));
    }

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
