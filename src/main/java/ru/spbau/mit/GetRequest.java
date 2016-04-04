package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Created by ldvsoft on 22.03.16.
 */
public class GetRequest {
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

    public static GetRequest readFrom(DataInputStream dis) throws IOException {
        return new GetRequest(
                dis.readInt(),
                dis.readInt()
        );
    }
}
