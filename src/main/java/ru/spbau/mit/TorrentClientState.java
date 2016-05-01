package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by ldvsoft on 26.04.16.
 */
class TorrentClientState implements AutoCloseable {
    private static final String DOWNLOADS_DIR = "downloads";

    private static final String STATE_FILE = "client-state.dat";

    /*package*/ Path workingDir;
    /*package*/ ReadWriteLock lock = new ReentrantReadWriteLock();
    /*package*/ Map<Integer, FileState> files;
    /*package*/ String host;

    /*package*/ TorrentClientState(String host, Path workingDir) throws IOException {
        this.host = host;
        this.workingDir = workingDir;
        load();
    }

    @Override
    public void close() throws IOException {
        store();
    }

    /*package*/ static final class FileState {
        /*package*/ ReadWriteLock fileLock = new ReentrantReadWriteLock();
        /*package*/ FileEntry entry;
        /*package*/ PartsSet parts;
        /*package*/ Path localPath;

        /*package*/ FileState(FileEntry entry, Path localPath, Path workingDir) throws IOException {
            this(entry, new PartsSet(entry.getPartsCount(), localPath != null), localPath, workingDir);
        }

        /*package*/ FileState(
                FileEntry entry,
                PartsSet parts,
                Path localPath,
                Path workingDir
        ) throws IOException {
            this.entry = entry;
            this.parts = parts;
            if (localPath == null) {
                this.localPath = workingDir.resolve(Paths.get(
                        DOWNLOADS_DIR,
                        Integer.toString(entry.getId()),
                        entry.getName()
                ));
                Files.createDirectories(this.localPath.getParent());
                try (RandomAccessFile file = new RandomAccessFile(this.localPath.toString(), "rw")) {
                    file.setLength(entry.getSize());
                }
            } else {
                this.localPath = localPath;
            }
        }

        private void writeTo(DataOutputStream dos) throws IOException {
            entry.writeTo(dos);
            parts.writeTo(dos);
            dos.writeUTF(localPath.toString());
        }

        private static FileState readFrom(DataInputStream dis) throws IOException {
            FileEntry fileEntry = FileEntry.readFrom(dis, true);
            PartsSet parts = PartsSet.readFrom(dis, fileEntry.getPartsCount());
            String localPath = dis.readUTF();
            return new FileState(fileEntry, parts, Paths.get(localPath), null);
        }
    }

    private void store() throws IOException {
        Path state = workingDir.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            Files.createDirectories(workingDir);
            Files.createFile(state);
        }
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(state))) {
            IOUtils.writeCollection(files.values(), (dos1, o) -> o.writeTo(dos1), dos);
        }
    }

    private void load() throws IOException {
        Path state = workingDir.resolve(STATE_FILE);
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
}
