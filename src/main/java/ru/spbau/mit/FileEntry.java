package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Created by ldvsoft on 22.03.16.
 */
public final class FileEntry {
    public static final int PART_SIZE = 10 * 1024 * 1024;

    private static final int HASH_BASE = 31;

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

    public int getPartsCount() {
        return (int) ((size + PART_SIZE - 1) / PART_SIZE);
    }

    public int getPartSize(int partId) {
        if (partId < getPartsCount() - 1) {
            return PART_SIZE;
        }
        if (size % PART_SIZE == 0) {
            return PART_SIZE;
        }
        return (int) (size % PART_SIZE);
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(hasId) + HASH_BASE * (
                id + HASH_BASE * (name.hashCode() + (int) (HASH_BASE * size))
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FileEntry)) {
            return false;
        }
        FileEntry that = (FileEntry) obj;
        return this.hasId == that.hasId
                && this.id == that.id
                && Objects.equals(this.name, that.name)
                && this.size == that.size;
    }

    @Override
    public String toString() {
        return "FileEntry{"
                + "hasId=" + hasId
                + ", id=" + id
                + ", name='" + name + '\''
                + ", size=" + size
                + '}';
    }
}
