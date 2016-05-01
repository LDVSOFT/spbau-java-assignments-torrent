package ru.spbau.mit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static ru.spbau.mit.TorrentClientState.FileState;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentClient extends TorrentClientBase {
    public TorrentClient(TorrentClientState state) {
        super(state);
    }

    public List<FileEntry> requestList() throws IOException {
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeListRequest();
            return connection.readListResponse();
        }
    }

    public boolean get(int id) throws IOException {
        try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
            if (state.files.containsKey(id)) {
                return false;
            }
        }
        FileEntry serverEntry = requestList().stream()
                .filter(entry -> entry.getId() == id)
                .findAny().orElse(null);
        if (serverEntry == null) {
            return false;
        }
        try (LockHandler handler = LockHandler.lock(state.lock.writeLock())) {
            state.files.put(id, new FileState(serverEntry, null, state.workingDir));
        }
        return true;
    }

    public FileEntry newFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not exists or is not a regular file.");
        }

        FileEntry newEntry = new FileEntry(path.getFileName().toString(), Files.size(path));
        try (TorrentTrackerConnection connection = connectToTracker()) {
            connection.writeUploadRequest(newEntry);
            int newId = connection.readUploadResponse();
            newEntry = newEntry.setId(newId);
        }
        FileState newState = new FileState(newEntry, path, null);
        try (LockHandler handler = LockHandler.lock(state.lock.writeLock())) {
            state.files.put(newEntry.getId(), newState);
        }
        return newEntry;
    }
}
