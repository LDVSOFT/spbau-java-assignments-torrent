package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;

/**
 * Created by ldvsoft on 22.03.16.
 */
public abstract class Connection implements AutoCloseable {
    private Socket socket;

    private DataInputStream dis;
    private DataOutputStream dos;

    protected Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.dis = new DataInputStream(socket.getInputStream());
        this.dos = new DataOutputStream(socket.getOutputStream());
    }

    public DataInputStream getInput() {
        return dis;
    }

    public DataOutputStream getOutput() {
        return dos;
    }

    public String getHost() {
        return ((InetSocketAddress) socket.getRemoteSocketAddress()).getHostString();
    };

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public int readRequest() throws IOException {
        return dis.readUnsignedByte();
    }

    protected <T> void writeCollection(
            Collection<T> list,
            IOUtils.Writer<? super T> writer
    ) throws IOException {
        IOUtils.writeCollection(list, writer, dos);
    }

    protected <T, C extends Collection<T>> C readCollection(
            C collection,
            IOUtils.Reader<? extends T> reader
    ) throws IOException {
        return IOUtils.readCollection(collection, reader, dis);
    }
}
