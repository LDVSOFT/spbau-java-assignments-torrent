package ru.spbau.mit;

import java.io.*;
import java.net.Socket;

import static ru.spbau.mit.FileEntry.PART_SIZE;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentP2PConnection extends Connection {
    public static final int REQUEST_STAT = 1;
    public static final int REQUEST_GET = 2;

    private static final int BUFFER_SIZE = 4096;

    public TorrentP2PConnection(Socket socket) throws IOException {
        super(socket);
    }

    // STAT

    public void writeStatRequest(int fileId) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_STAT);
        dos.writeInt(fileId);
        dos.flush();
    }

    public int readStatRequest() throws IOException {
        return getInput().readInt();
    }

    public void writeStatResponse(PartsSet parts) throws IOException {
        DataOutputStream dos = getOutput();
        parts.writeTo(dos);
        dos.flush();
    }

    public PartsSet readStatResponse(int size) throws IOException {
        return PartsSet.readFrom(getInput(), size);
    }

    // GET

    public void writeGetRequest(GetRequest request) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeByte(REQUEST_GET);
        request.writeTo(dos);
        dos.flush();
    }

    public GetRequest readGetRequest() throws IOException {
        return GetRequest.readFrom(getInput());
    }

    public void writeGetResponse(RandomAccessFile from, int partId, FileEntry entry) throws IOException {
        from.seek(PART_SIZE * partId);
        int amount = entry.getPartSize(partId);

        DataOutputStream dos = getOutput();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (amount > 0) {
            int read = from.read(buffer, 0, Math.min(amount, BUFFER_SIZE));
            if (read == -1) {
                throw new EOFException("File is shorter than recorded size.");
            }
            amount -= read;
            dos.write(buffer, 0, read);
        }
        dos.flush();
    }

    public void readGetResponse(RandomAccessFile to, int partId, FileEntry entry) throws IOException {
        to.seek(PART_SIZE * partId);
        int amount = entry.getPartSize(partId);

        DataInputStream dis = getInput();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (amount > 0) {
            int read = dis.read(buffer, 0, Math.min(amount, BUFFER_SIZE));
            if (read == -1) {
                throw new EOFException("Cannot read the end of the file from socket.");
            }
            amount -= read;
            to.write(buffer, 0, read);
        }
    }
}
