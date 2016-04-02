package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Created by ldvsoft on 22.03.16.
 */
public final class FileEntry {
    public static FileEntry readFrom(DataInputStream dis, boolean hasId) throws IOException {
        if (hasId) {
            return new FileEntry(
                    dis.readInt(),
                    dis.readUTF(),
                    dis.readLong()
            );
        } else {
            return new FileEntry(
                    dis.readUTF(),
                    dis.readLong()
            );
        }
    }

    private boolean hasId;
    private int id;
    private String name;
    private long size;

    public FileEntry(int id, String name, long size) {
        this.hasId = true;
        this.id = id;
        this.name = name;
        this.size = size;
    }

    public FileEntry(String name, long size) {
        this.hasId = false;
        this.name = name;
        this.size = size;
    }

    public boolean hasId() {
        return hasId;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public void setId(int id) {
        this.hasId = true;
        this.id = id;
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        if (hasId) {
            dos.writeInt(id);
        }
        dos.writeUTF(name);
        dos.writeLong(size);
    }
}
