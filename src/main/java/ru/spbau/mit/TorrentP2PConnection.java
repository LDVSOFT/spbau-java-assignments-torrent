package ru.spbau.mit;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.Socket;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentP2PConnection extends Connection {
    public static final int ACTION_STAT = 1;
    public static final int ACTION_GET = 2;

    public static final long PART_SIZE = 10 * 1024 * 1024 *  1024l;

    public static class GetRequest {
        public static GetRequest readFrom(DataInputStream dis) throws IOException {
            return new GetRequest(
                    dis.readInt(),
                    dis.readInt()
            );
        }

        private int fileId;
        private int partId;

        public GetRequest(int fileId, int partId) {
            this.fileId = fileId;
            this.partId = partId;
        }

        public int getFileId() {
            return fileId;
        }

        public int getPartId() {
            return partId;
        }

        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeInt(fileId);
            dos.writeInt(partId);
        }
    }

    public TorrentP2PConnection(Socket socket) throws IOException {
        super(socket);
    }

    // STAT

    public void writeStatAction(int fileId) throws IOException {
        dos.writeByte(ACTION_STAT);
        dos.writeInt(fileId);
        dos.flush();
    }

    public int readStatAction() throws IOException {
        return dis.readInt();
    }

    public void writeStatResponse(List<Integer> parts) throws IOException {
        writeList(parts, DataOutputStream::writeInt, dos);
    }

    public List<Integer> readStatResponse() throws IOException {
        return readList(DataInputStream::readInt, dis);
    }

    // GET

    public void writeGetAction(GetRequest request) throws IOException {
        dos.writeByte(ACTION_GET);
        request.writeTo(dos);
        dos.flush();
    }

    public GetRequest readGetAction() throws IOException {
        return GetRequest.readFrom(dis);
    }

    public void writeGetResponse(InputStream from) throws IOException {
        IOUtils.copyLarge(from, dos, 0l, PART_SIZE);
    }

    public void readGetResponse(OutputStream to) throws IOException {
        IOUtils.copyLarge(dis, to, 0l, PART_SIZE);
    }
}
