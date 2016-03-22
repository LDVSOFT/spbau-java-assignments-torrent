package ru.spbau.mit;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentTrackerConnection extends Connection {
    public static final int ACTION_LIST = 1;
    public static final int ACTION_UPLOAD = 2;
    public static final int ACTION_SOURCES = 3;
    public static final int ACTION_UPDATE = 4;

    public static final class FileEntry {
        public static FileEntry readFrom(DataInputStream dis, boolean hasId) throws IOException {
            if (hasId) {
                return new FileEntry(
                        dis.readInt(),
                        dis.readUTF(),
                        dis.readLong()
                );
            } else {
                return new FileEntry(
                        dis.readUTF(),
                        dis.readLong()
                );
            }
        }

        private boolean hasId;
        private int id;
        private String name;
        private long size;

        public FileEntry(int id, String name, long size) {
            this.hasId = true;
            this.id = id;
            this.name = name;
            this.size = size;
        }

        public FileEntry(String name, long size) {
            this.hasId = false;
            this.name = name;
            this.size = size;
        }

        public boolean hasId() {
            return hasId;
        }
        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public void setId(int id) {
            this.hasId = true;
            this.id = id;
        }

        public void writeTo(DataOutputStream dos) throws IOException {
            if (hasId) {
                dos.writeInt(id);
            }
            dos.writeUTF(name);
            dos.writeLong(size);
        }
    }

    public static class ClientInfo {
        public static ClientInfo readFrom(DataInputStream dis) throws IOException {
            return new ClientInfo(
                    dis.readUnsignedShort(),
                    readList(DataInputStream::readInt, dis)
            );
        }

        private int port;
        private List<Integer> ids;

        public ClientInfo(int port, List<Integer> ids) {
            this.port = port;
            this.ids = ids;
        }

        public int getPort() {
            return port;
        }

        public List<Integer> getIds() {
            return ids;
        }

        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeShort(port);
            writeList(ids, DataOutputStream::writeInt, dos);
        }
    };

    public TorrentTrackerConnection(Socket socket) throws IOException {
        super(socket);
    }

    // LIST

    public void writeListAction() throws IOException {
        dos.writeByte(ACTION_LIST);
        dos.flush();
    }

    public void writeListResponse(List<FileEntry> files) throws IOException {
        writeList(files, (dos, entry) -> {
            if (!entry.hasId()) {
                throw new IllegalStateException("Uploaded files must have id.");
            }
            entry.writeTo(dos);
        }, dos);
        dos.flush();
    }

    public List<FileEntry> readListResponse() throws IOException {
        return readList((dis) -> FileEntry.readFrom(dis, true), dis);
    }

    // UPLOAD

    public void writeUploadAction(FileEntry file) throws IOException {
        if (file.hasId()) {
            throw new IllegalStateException("Uploading file cannot have id.");
        }

        dos.writeByte(ACTION_UPLOAD);
        file.writeTo(dos);
        dos.flush();
    }

    public FileEntry readUploadAction() throws IOException {
        return FileEntry.readFrom(dis, false);
    }

    public void writeUploadResponse(int fileId) throws IOException {
        dos.writeInt(fileId);
        dos.flush();
    }

    public int readUploadResponse() throws IOException {
        return dis.readInt();
    }

    // SOURCES

    public void writeSourcesAction(List<Integer> ids) throws IOException {
        if (ids.size() == 0) {
            throw new IllegalStateException("There are no ids");
        }

        dos.writeByte(ACTION_SOURCES);
        writeList(ids, DataOutputStream::writeInt, dos);
        dos.flush();
    }

    public List<Integer> readSourcesAction() throws IOException {
        return readList(DataInputStream::readInt, dis);
    }

    public void writeSourcesResponse(List<InetSocketAddress> addresses) throws IOException {
        writeList(addresses, (dos, address) -> {
            dos.write(address.getAddress().getAddress());
            dos.writeShort(address.getPort());
        }, dos);
    }

    public List<InetSocketAddress> readSourcesResponse() throws IOException {
        byte buffer[] = new byte[4];
        return readList((dis) -> {
            if (dis.read(buffer, 0, 4) != 4)
                throw new EOFException("Cannot read address");
            int port = dis.readShort();
            return new InetSocketAddress(InetAddress.getByAddress(buffer), port);
        }, dis);
    }

    // UPDATE

    public void writeUpdateAction(ClientInfo info) throws IOException {
        if (info.getIds().size() == 0) {
            throw new IllegalStateException("There are no ids");
        }

        dos.writeByte(ACTION_UPDATE);
        info.writeTo(dos);
        dos.flush();
    }

    public ClientInfo readUpdateAction() throws IOException {
        return ClientInfo.readFrom(dis);
    }

    public void writeUpdateResponse(boolean isSuccessful) throws IOException {
        dos.writeBoolean(isSuccessful);
        dos.flush();
    }

    public boolean readUpdateResponse() throws IOException {
        return dis.readBoolean();
    }
}
