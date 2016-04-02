package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class ClientInfo {
    public static ClientInfo readFrom(DataInputStream dis) throws IOException {
        return new ClientInfo(
                dis.readUnsignedShort(),
                Connection.readList(DataInputStream::readInt, dis)
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
        Connection.writeList(ids, DataOutputStream::writeInt, dos);
    }
}
