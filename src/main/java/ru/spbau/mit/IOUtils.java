package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by ldvsoft on 02.04.16.
 */
public abstract class IOUtils {
    private static final int IP4_LENGTH = 4;

    public static <T> void writeCollection(
            Collection<T> list,
            Writer<? super T> writer,
            DataOutputStream dos
    ) throws IOException {
        dos.writeInt(list.size());
        for (T o : list) {
            writer.write(dos, o);
        }
    }

    public static <T, C extends Collection<T>> C readCollection(
            C collection,
            Reader<? extends T> reader,
            DataInputStream dis
    ) throws IOException {
        int size = dis.readInt();
        for (int i = 0; i != size; i++) {
            collection.add(reader.read(dis));
        }
        return collection;
    }

    public static void writeAddress(DataOutputStream dos, InetSocketAddress address) throws IOException {
        dos.write(address.getAddress().getAddress());
        dos.writeShort(address.getPort());
    }

    public static InetSocketAddress readAddress(DataInputStream dis) throws IOException {
        byte[] buffer = new byte[IP4_LENGTH];
        // I hate that read() call :(
        for (int i = 0; i != IP4_LENGTH; i++) {
            buffer[i] = dis.readByte();
        }
        int port = dis.readUnsignedShort();
        return new InetSocketAddress(InetAddress.getByAddress(buffer), port);
    }

    public interface Writer<T> {
        void write(DataOutputStream dos, T o) throws IOException;
    }

    public interface Reader<T> {
        T read(DataInputStream dis) throws IOException;
    }
}
