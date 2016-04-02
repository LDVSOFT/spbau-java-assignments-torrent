package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Created by ldvsoft on 02.04.16.
 */
public class TorrentTracker {
    private static final Path STATE_PATH = Paths.get("tracker-state.dat");

    private volatile ExecutorService executorService;
    private volatile ServerSocket serverSocket;
    private volatile List<FileEntry> files;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<Integer, Set<ClientInfo>> seeders;

    public TorrentTracker() throws IOException {
        serverSocket = new ServerSocket(TorrentTrackerConnection.TRACKER_PORT);
        executorService = Executors.newCachedThreadPool();
        files = new ArrayList<>();
        load();
        executorService.submit(this::work);
    }

    public void close() throws IOException {
        serverSocket.close();
        executorService.shutdown();
        store();
    }

    private void work() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                if (socket == null) {
                    return;
                }
                executorService.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (TorrentTrackerConnection connection = new TorrentTrackerConnection(socket)) {
            int request = connection.readRequest();
            switch (request) {
                case TorrentTrackerConnection.REQUEST_LIST:
                    doList(connection);
                    break;
                case TorrentTrackerConnection.REQUEST_SOURCES:
                    doSources(connection);
                    break;
                case TorrentTrackerConnection.REQUEST_UPLOAD:
                    doUpload(connection);
                    break;
                case TorrentTrackerConnection.REQUEST_UPDATE:
                    break;
                default:
                    System.err.printf("Wrong request from client: %d.\n", request);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doList(TorrentTrackerConnection connection) throws IOException {
        lock.readLock().lock();
        try {
            connection.writeListResponse(files);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void doSources(TorrentTrackerConnection connection) throws IOException {
        lock.readLock().lock();
        try {
            List<InetSocketAddress> result = connection.readSourcesRequest().stream()
                    .flatMap(i -> seeders.get(i).stream())
                    .distinct()
                    .map(ClientInfo::getSocketAddress)
                    .collect(Collectors.toList());
            connection.writeSourcesResponse(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void doUpload(TorrentTrackerConnection connection) throws IOException {
        lock.writeLock().lock();
        try {
            FileEntry newEntry = connection.readUploadRequest();
            int newId = files.size();
            newEntry.setId(newId);
            files.add(newEntry);
            connection.writeUploadResponse(newId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void store() throws IOException {
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(STATE_PATH))) {
            IOUtils.writeCollection(files, (dos1, o) -> o.writeTo(dos1), dos);
        }
    }

    private void load() throws IOException {
        if (Files.exists(STATE_PATH)) {
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(STATE_PATH))) {
                files = IOUtils.readCollection(new ArrayList<>(), dis1 -> FileEntry.readFrom(dis1, true), dis);
            }
        } else {
            files = new ArrayList<>();
        }
        seeders = new HashMap<>();
    }
}
