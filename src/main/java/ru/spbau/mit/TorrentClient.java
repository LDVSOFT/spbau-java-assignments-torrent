package ru.spbau.mit;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static ru.spbau.mit.TorrentP2PConnection.*;
import static ru.spbau.mit.TorrentTrackerConnection.DELAY;
import static ru.spbau.mit.TorrentTrackerConnection.TRACKER_PORT;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentClient implements AutoCloseable {
    private static final Path STATE_PATH = Paths.get("client-state.dat");

    private static final class FileState {
        private ReadWriteLock fileLock = new ReentrantReadWriteLock();
        private FileEntry entry;
        private PartsSet parts;
        private String localPath;

        private FileState(FileEntry entry, String localPath) throws IOException {
            this(entry, new PartsSet(getPartsTotal(entry), localPath != null), localPath);
        }

        private FileState(FileEntry entry, PartsSet parts, String localPath) throws IOException {
            this.entry = entry;
            this.parts = parts;
            if (localPath == null) {
                this.localPath = Paths.get(
                        "downloads",
                        Integer.toString(entry.getId()),
                        entry.getName()
                ).toString();
                new RandomAccessFile(this.localPath, "rw").setLength(entry.getSize());
            }
        }

        private static int getPartsTotal(FileEntry entry) {
            return (int) ((entry.getSize() + PART_SIZE - 1) / PART_SIZE);
        }

        private int getPartsTotal() {
            return getPartsTotal(entry);
        }

        private void writeTo(DataOutputStream dos) throws IOException {
            entry.writeTo(dos);
            parts.writeTo(dos);
            dos.writeUTF(localPath);
        }

        private static FileState readFrom(DataInputStream dis) throws IOException {
            FileEntry fileEntry = FileEntry.readFrom(dis, true);
            PartsSet parts = PartsSet.readFrom(dis, getPartsTotal(fileEntry));
            return new FileState(fileEntry, parts, dis.readUTF());
        }
    }

    private ReadWriteLock filesLock = new ReentrantReadWriteLock();
    private Map<Integer, FileState> files;
    private String host;
    // For run
    private ServerSocket serverSocket = null;
    private List<Integer> filesToDownload;
    private ExecutorService executorService;
    private boolean isRunning = false;

    public TorrentClient(String host) throws IOException {
        this.host = host;
        load();
    }

    @Override
    public void close() throws IOException {
        store();
        if (isRunning) {
            try (
                    @SuppressWarnings("unused")
                    LockHandler handler = LockHandler.lock(filesLock.writeLock())
            ) {
                isRunning = false;
                serverSocket.close();
            }
            executorService.shutdown();
        }
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
        filesLock.writeLock().lock();
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(filesLock.writeLock())
        ) {
            files.put(id, new FileState(serverEntry, null));
        }
        return true;
    }

    public int newFile(String pathString) throws IOException {
        Path path = Paths.get(pathString);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        try (TorrentTrackerConnection connection = connectToTracker()) {
            FileEntry newEntry = new FileEntry(path.getFileName().toString(), Files.size(path));
            connection.writeUploadRequest(newEntry);
            int newId = connection.readUploadResponse();
            newEntry.setId(newId);
            FileState newState = new FileState(newEntry, pathString);
            try (
                    @SuppressWarnings("unused")
                    LockHandler handler = LockHandler.lock(filesLock.writeLock())
            ) {
                files.put(newId, newState);
            }
            return newId;
        }
    }

    public void run() throws IOException {
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(filesLock.writeLock())
        ) {
            serverSocket = new ServerSocket(0);
            executorService = Executors.newCachedThreadPool();

            // Starting downloaders
            filesToDownload = new ArrayList<>();
            for (FileState state : files.values()) {
                if (state.parts.getCount() == state.getPartsTotal()) {
                    continue;
                }
                filesToDownload.add(state.entry.getId());
                executorService.submit(() -> download(state));
            }

            // Starting seeding server
            executorService.submit(this::server);

            // Starting tracking update loop
            executorService.submit(() -> {
                while (true) {
                    try {
                        update(serverSocket.getLocalPort());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    delay();
                    try (
                            @SuppressWarnings("unused")
                            LockHandler handler1 = LockHandler.lock(filesLock.readLock())
                    ) {
                        if (!isRunning) {
                            return;
                        }
                    }
                }
            });

            isRunning = true;
        }
    }

    // Protocol requests

    private List<InetSocketAddress> sources(Collection<Integer> files) throws IOException {
        try (TorrentTrackerConnection connection = new TorrentTrackerConnection(new Socket(host, TRACKER_PORT))) {
            connection.writeSourcesRequest(files);
            return connection.readSourcesResponse();
        }
    }

    private PartsSet stat(InetSocketAddress seeder, FileState state) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeStatRequest(state.entry.getId());
            return connection.readStatResponse(state.getPartsTotal());
        }
    }

    private void get(InetSocketAddress seeder, FileState state, GetRequest request) throws IOException {
        try (TorrentP2PConnection connection = connectToSeeder(seeder)) {
            connection.writeGetRequest(request);
            try (RandomAccessFile file = new RandomAccessFile(state.localPath, "w")) {
                file.seek(request.getPartId() * PART_SIZE);
                connection.readGetResponse(Channels.newOutputStream(file.getChannel()));
            }
        }
    }

    // Seeding part: handling requests

    private boolean update(int port) throws IOException {
        ClientInfo info;
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(filesLock.readLock())
        ) {
            info = new ClientInfo(new InetSocketAddress("", port), new ArrayList<>(files.keySet()));
        }
        try (TorrentTrackerConnection trackerConnection = connectToTracker()) {
            trackerConnection.writeUpdateRequest(info);
            return trackerConnection.readUpdateResponse();
        }
    }

    private void server() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                executorService.submit(() -> handle(socket));
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (TorrentP2PConnection connection = new TorrentP2PConnection(socket)) {
            while (true) {
                int request;
                try {
                    request = connection.readRequest();
                } catch (EOFException e) {
                    break;
                }
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
            }
        } catch (IOException | IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    private void doStat(TorrentP2PConnection connection) throws IOException {
        int fileId = connection.readStatRequest();
        FileState state;
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(filesLock.readLock())
        ) {
            state = files.get(fileId);
        }
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(state.fileLock.readLock())
        ) {
            connection.writeStatResponse(state.parts);
        }
    }

    private void doGet(TorrentP2PConnection connection) throws IOException {
        GetRequest request = connection.readGetRequest();
        FileState state;
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(filesLock.readLock())
        ) {
            state = files.get(request.getFileId());
        }
        try (
                @SuppressWarnings("unused")
                LockHandler handler = LockHandler.lock(state.fileLock.readLock())
        ) {
            if (!state.parts.get(request.getPartId())) {
                throw new IllegalArgumentException("Cannot perform get on missing file part.");
            }
        }
        // We already checked that file has requested part, just read it without locking
        try (RandomAccessFile file = new RandomAccessFile(state.localPath, "r")) {
            file.seek(PART_SIZE * request.getPartId());
            connection.writeGetResponse(Channels.newInputStream(file.getChannel()));
        }
    }

    // Leeching part

    private void download(FileState state) {
        List<InetSocketAddress> seeders = null;
        int currentSeeder = 0;
        PartsSet seederParts = null;
        int canOffer = 0;
        while (true) {
            try (
                    @SuppressWarnings("unused")
                    LockHandler handler = LockHandler.lock(state.fileLock.readLock())
            ) {
                if (!isRunning) {
                    return;
                }
                if (state.parts.getCount() == state.getPartsTotal()) {
                    System.out.printf("File \"%s\" downloaded!\n", state.entry.getName());
                    return;
                }
            }

            if (seeders == null || seeders.size() == 0) {
                try {
                    seeders = sources(Collections.singletonList(state.entry.getId()));
                    currentSeeder = -1;
                } catch (IOException e) {
                    System.err.printf("Failed to fetch seeders for \"%s\"", state.entry.getName());
                    delay();
                    continue;
                }
            }
            if (seeders == null || seeders.size() == 0) {
                System.err.printf("Noone seeds \"%s\", delaying...\n", state.entry.getName());
                delay();
                continue;
            }

            if (canOffer == 0 && currentSeeder < seeders.size()) {
                currentSeeder++;
                try {
                    seederParts = stat(seeders.get(currentSeeder), state);
                } catch (IOException e) {
                    System.err.printf(
                            "Failed to stat seeder at %s, skipping...",
                            seeders.get(currentSeeder).toString()
                    );
                    continue;
                }
                try (
                        @SuppressWarnings("unused")
                        LockHandler handler = LockHandler.lock(state.fileLock.readLock())
                ) {
                    seederParts.subtract(state.parts);
                }
                canOffer = seederParts.getCount();
            }

            if (canOffer == 0) {
                System.err.printf("Noone seeds missing parts of \"%s\", delaying...\n", state.entry.getName());
                delay();
                continue;
            }

            int partId = 0;
            if (canOffer > 0) {
                partId = seederParts.getFirstBitAtLeast(partId);
                try {
                    get(seeders.get(currentSeeder), state, new GetRequest(state.entry.getId(), partId));
                } catch (IOException e) {
                    System.err.printf(
                            "Failed to download part %d of file %s from %s...\n",
                            partId,
                            state.entry.getName(),
                            seeders.get(currentSeeder)
                    );
                    delay();
                }
            }
        }
    }

    // Utils

    private void store() throws IOException {
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(STATE_PATH))) {
            dos.writeInt(files.size());
            for (FileState state : files.values()) {
                state.writeTo(dos);
            }
        }
    }

    private void load() throws IOException {
        if (Files.exists(STATE_PATH)) {
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(STATE_PATH))) {
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

    private void delay() {
        try {
            Thread.sleep(DELAY);
        } catch (InterruptedException ignored) {
        }
    }
}
