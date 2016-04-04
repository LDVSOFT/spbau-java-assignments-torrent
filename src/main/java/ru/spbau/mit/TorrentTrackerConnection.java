package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentTrackerConnection extends Connection {
    public static final int TRACKER_PORT = 8081;
    public static final int DELAY = 60 * 1000;

    public static final int REQUEST_LIST = 1;
    public static final int REQUEST_UPLOAD = 2;
    public static final int REQUEST_SOURCES = 3;
    public static final int REQUEST_UPDATE = 4;

    public TorrentTrackerConnection(Socket socket) throws IOException {
        super(socket);
    }

    // LIST

    public void writeListRequest() throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_LIST);
        dos.flush();
    }

    public void writeListResponse(Collection<FileEntry> files) throws IOException {
        writeCollection(files, (dos, entry) -> {
            if (!entry.hasId()) {
                throw new IllegalStateException("Uploaded files must have id.");
            }
            entry.writeTo(dos);
        });
        getOutput().flush();
    }

    public List<FileEntry> readListResponse() throws IOException {
        return readCollection(new ArrayList<>(), (dis) -> FileEntry.readFrom(dis, true));
    }

    // UPLOAD

    public void writeUploadRequest(FileEntry file) throws IOException {
        if (file.hasId()) {
            throw new IllegalStateException("Uploading file cannot have id.");
        }

        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_UPLOAD);
        file.writeTo(dos);
        dos.flush();
    }

    public FileEntry readUploadRequest() throws IOException {
        return FileEntry.readFrom(getInput(), false);
    }

    public void writeUploadResponse(int fileId) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeInt(fileId);
        dos.flush();
    }

    public int readUploadResponse() throws IOException {
        return getInput().readInt();
    }

    // SOURCES

    public void writeSourcesRequest(Collection<Integer> ids) throws IOException {
        if (ids.size() == 0) {
            throw new IllegalStateException("There are no ids");
        }

        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_SOURCES);
        writeCollection(ids, DataOutputStream::writeInt);
        dos.flush();
    }

    public List<Integer> readSourcesRequest() throws IOException {
        return readCollection(new ArrayList<>(), DataInputStream::readInt);
    }

    public void writeSourcesResponse(Collection<InetSocketAddress> addresses) throws IOException {
        writeCollection(addresses, IOUtils::writeAddress);
    }

    public List<InetSocketAddress> readSourcesResponse() throws IOException {
        return readCollection(new ArrayList<>(), IOUtils::readAddress);
    }

    // UPDATE

    public void writeUpdateRequest(ClientInfo info) throws IOException {
        if (info.getIds().size() == 0) {
            throw new IllegalStateException("There are no ids");
        }

        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_UPDATE);
        info.writeTo(dos);
        dos.flush();
    }

    public ClientInfo readUpdateRequest() throws IOException {
        return ClientInfo.readFrom(getInput());
    }

    public void writeUpdateResponse(boolean isSuccessful) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeBoolean(isSuccessful);
        dos.flush();
    }

    public boolean readUpdateResponse() throws IOException {
        return getInput().readBoolean();
    }
}
