package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Created by ldvsoft on 02.04.16.
 *
 * I write my own bitset because I don't like the java.util one with auto-incrementing size etc
 */
public class PartsSet {
    private int count = 0;
    private boolean[] flags;

    public PartsSet(int size, boolean defaultValue) {
        flags = new boolean[size];
        if (defaultValue) {
            Arrays.fill(flags, true);
            count = size;
        }
    }

    public boolean get(int pos) {
        return flags[pos];
    }

    public void set(int pos, boolean value) {
        if (flags[pos] == value) {
            return;
        }
        flags[pos] = !flags[pos];
        if (flags[pos]) {
            count++;
        } else {
            count--;
        }
    }

    public int getCount() {
        return count;
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(count);
        for (int i = 0; i != flags.length; i++) {
            if (get(i)) {
                dos.writeInt(i);
            }
        }
    }

    public static PartsSet readFrom(DataInputStream dis, int size) throws IOException {
        PartsSet result = new PartsSet(size, false);
        int count = dis.readInt();
        while (count > 0) {
            count--;
            result.set(dis.readInt(), true);
        }
        return result;
    }

    public void subtract(PartsSet other) {
        assert other.flags.length == flags.length;
        for (int i = 0; i != flags.length; i++) {
            if (other.get(i)) {
                set(i, false);
            }
        }
    }

    public int getFirstBitAtLeast(int pos) {
        for (int i = pos; i != flags.length; i++) {
            if (get(i)) {
                return i;
            }
        }
        return -1;
    }
}
