package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static ru.spbau.mit.TorrentP2PConnection.*;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentClient implements AutoCloseable {
    private static final Path STATE_PATH = Paths.get("client-state.dat");

    private static final class FileState {
        private FileEntry entry;
        private BitSet parts;
        private String localPath;
        private RandomAccessFile file = null;

        private FileState(FileEntry entry, String localPath) {
            this(entry, new BitSet(getPartsCount(entry), localPath != null), localPath);
        }

        private FileState(FileEntry entry, BitSet parts, String localPath) {
            this.entry = entry;
            this.parts = parts;
            if (localPath == null) {
                this.localPath = Paths.get(
                        "downloads",
                        Integer.toString(entry.getId()),
                        entry.getName()
                ).toString();
            }
        }

        private static int getPartsCount(FileEntry entry) {
            return (int) ((entry.getSize() + PART_SIZE - 1) / PART_SIZE);
        }

        private void writeTo(DataOutputStream dos) throws IOException {
            entry.writeTo(dos);
            parts.writeTo(dos);
            dos.writeUTF(localPath);
        }

        private static FileState readFrom(DataInputStream dis) throws IOException {
            FileEntry fileEntry = FileEntry.readFrom(dis, true);
            BitSet parts = BitSet.readFrom(dis, getPartsCount(fileEntry));
            return new FileState(fileEntry, parts, dis.readUTF());
        }
    }

    private Map<Integer, FileState> files;
    private String host;

    public TorrentClient(String host) throws IOException {
        this.host = host;
        load();
    }

    @Override
    public void close() throws IOException {
        store();
    }

    public List<FileEntry> list() throws IOException {
        try (TorrentTrackerConnection trackerConnection = new TorrentTrackerConnection(
                new Socket(host, TorrentTrackerConnection.TRACKER_PORT)
        )) {
            trackerConnection.writeListRequest();
            return trackerConnection.readListResponse();
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
        files.put(id, new FileState(serverEntry, null));
        return true;
    }

    public int newFile(String pathString) throws IOException {
        Path path = Paths.get(pathString);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        try (TorrentTrackerConnection trackerConnection = new TorrentTrackerConnection(
                new Socket(host, TorrentTrackerConnection.TRACKER_PORT)
        )) {
            FileEntry newEntry = new FileEntry(path.getFileName().toString(), Files.size(path));
            trackerConnection.writeUploadRequest(newEntry);
            int newId = trackerConnection.readUploadResponse();
            newEntry.setId(newId);
            FileState newState = new FileState(newEntry, pathString);
            files.put(newId, newState);
            return newId;
        }
    }

    public boolean update(int port) throws IOException {
        ClientInfo info = new ClientInfo(new InetSocketAddress("", port), new ArrayList<>(files.keySet()));
        try (TorrentTrackerConnection trackerConnection = new TorrentTrackerConnection(
                new Socket(host, TorrentTrackerConnection.TRACKER_PORT)
        )) {
            trackerConnection.writeUpdateRequest(info);
            return trackerConnection.readUpdateResponse();
        }
    }

    private void store() throws IOException {
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(STATE_PATH))) {
            dos.writeInt(files.size());
            for (FileState state : files.values()) {
                state.writeTo(dos);
                if (state.file != null) {
                    state.file.close();
                }
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
}
