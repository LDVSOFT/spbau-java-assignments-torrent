package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public abstract class Connection implements AutoCloseable {
    private Socket socket;
    protected DataInputStream dis;
    protected DataOutputStream dos;

    protected Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.dis = new DataInputStream(socket.getInputStream());
        this.dos = new DataOutputStream(socket.getOutputStream());
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public int readAction() throws IOException {
        return dis.readUnsignedByte();
    }

    protected interface Writer<T> {
        void write(DataOutputStream dos, T o) throws IOException;
    }

    protected interface Reader<T> {
        T read(DataInputStream dis) throws IOException;
    }

    protected static <T> void writeList(List<T> list, Writer<? super T> writer, DataOutputStream dos) throws IOException {
        dos.writeInt(list.size());
        for (T o : list) {
            writer.write(dos, o);
        }
    }

    protected static <T> List<T> readList(Reader<? extends T> reader, DataInputStream dis) throws IOException {
        int size = dis.readInt();
        List<T> result = new ArrayList<>(size);
        for (int i = 0; i != size; i++) {
            result.add(reader.read(dis));
        }
        return result;
    }
}
