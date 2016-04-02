package ru.spbau.mit;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.Socket;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentP2PConnection extends Connection {
    public static final int ACTION_STAT = 1;
    public static final int ACTION_GET = 2;

    public static final int PART_SIZE = 10 * 1024 * 1024;

    public TorrentP2PConnection(Socket socket) throws IOException {
        super(socket);
    }

    // STAT

    public void writeStatAction(int fileId) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeByte(ACTION_STAT);
        dos.writeInt(fileId);
        dos.flush();
    }

    public int readStatAction() throws IOException {
        return getInput().readInt();
    }

    public void writeStatResponse(int size, BitSet parts) throws IOException {
        DataOutputStream dos = getOutput();
        parts.writeTo(dos);
        dos.flush();
    }

    public BitSet readStatResponse(int size) throws IOException {
        return BitSet.readFrom(getInput(), size);
    }

    // GET

    public void writeGetAction(GetRequest request) throws IOException {
        DataOutputStream dos = getOutput();
        dos.writeByte(ACTION_GET);
        request.writeTo(dos);
        dos.flush();
    }

    public GetRequest readGetAction() throws IOException {
        return GetRequest.readFrom(getInput());
    }

    public void writeGetResponse(InputStream from) throws IOException {
        DataOutputStream dos = getOutput();
        IOUtils.copyLarge(from, dos, 0L, PART_SIZE);
        dos.flush();
    }

    public void readGetResponse(OutputStream to) throws IOException {
        IOUtils.copyLarge(getInput(), to, 0L, PART_SIZE);
    }
}
