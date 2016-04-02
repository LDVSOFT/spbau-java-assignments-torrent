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
public class BitSet {
    private static final int WORD_SIZE = 64;

    private int size;
    private int count = 0;
    private long[] words;

    public BitSet(int size, boolean defaultValue) {
        this.size = size;
        this.words = new long[(size + WORD_SIZE - 1) / WORD_SIZE];
        if (defaultValue) {
            Arrays.fill(words, -1);
        }
    }

    public boolean get(int pos) {
        return (words[pos / WORD_SIZE] & (1 << (pos % WORD_SIZE))) != 0;
    }

    public void set(int pos, boolean value) {
        long oldWord = words[pos / WORD_SIZE];
        long newWord = oldWord;
        if (value) {
            newWord |= 1 << (pos % WORD_SIZE);
            if (newWord != oldWord) {
                count++;
            }
        } else {
            newWord &= ~(1 << (pos % WORD_SIZE));
            if (newWord != oldWord) {
                count--;
            }
        }
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(count);
        for (int i = 0; i != size; i++) {
            if (get(i)) {
                dos.writeInt(i);
            }
        }
    }

    public static BitSet readFrom(DataInputStream dis, int size) throws IOException {
        BitSet result = new BitSet(size, false);
        int count = dis.readInt();
        while (count > 0) {
            count--;
            result.set(dis.readInt(), true);
        }
        return result;
    }
}
