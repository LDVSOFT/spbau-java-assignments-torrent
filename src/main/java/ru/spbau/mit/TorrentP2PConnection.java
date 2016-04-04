package ru.spbau.mit;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.Socket;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class TorrentP2PConnection extends Connection {
    public static final int REQUEST_STAT = 1;
    public static final int REQUEST_GET = 2;

    public static final int PART_SIZE = 10 * 1024 * 1024;

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

    public void writeGetResponse(InputStream from) throws IOException {
        DataOutputStream dos = getOutput();
        IOUtils.copyLarge(from, dos, 0L, PART_SIZE);
        dos.flush();
    }

    public void readGetResponse(OutputStream to) throws IOException {
        IOUtils.copyLarge(getInput(), to, 0L, PART_SIZE);
    }
}
