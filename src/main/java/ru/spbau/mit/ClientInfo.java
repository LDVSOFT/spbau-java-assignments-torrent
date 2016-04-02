package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class ClientInfo {
    public static ClientInfo readFrom(DataInputStream dis) throws IOException {
        return new ClientInfo(
                IOUtils.readAddress(dis),
                IOUtils.readCollection(new ArrayList<>(), DataInputStream::readInt, dis)
        );
    }

    private InetSocketAddress socketAddress;
    private List<Integer> ids;

    public ClientInfo(InetSocketAddress socketAddress, List<Integer> ids) {
        this.socketAddress = socketAddress;
        this.ids = ids;
    }

    public InetSocketAddress getSocketAddress() {
        return socketAddress;
    }

    public List<Integer> getIds() {
        return ids;
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        IOUtils.writeAddress(dos, socketAddress);
        IOUtils.writeCollection(ids, DataOutputStream::writeInt, dos);
    }

}
