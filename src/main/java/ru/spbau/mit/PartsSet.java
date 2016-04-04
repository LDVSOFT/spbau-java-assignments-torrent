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
    private static final int WORD_SIZE = 64;
    private static final int BYTE_SIZE = 8;
    private static final long BYTE_MASK = (1 << BYTE_SIZE) - 1;
    private static final int[] BITS_IN_BYTE = new int[1 << BYTE_SIZE];

    static {
        for (int i = 0; i != 1 << BYTE_SIZE; ++i) {
            BITS_IN_BYTE[i] = 0;
            for (int j = 0; j != BYTE_SIZE; ++j) {
                if ((i & (1 << j)) != 0) {
                    BITS_IN_BYTE[i]++;
                }
            }
        }
    }

    private int size;
    private int count = 0;
    private long[] words;

    public PartsSet(int size, boolean defaultValue) {
        this.size = size;
        this.words = new long[getWordCount()];
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

    public int getCount() {
        return count;
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(count);
        for (int i = 0; i != size; i++) {
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
        count = 0;
        for (int i = 0; i != getWordCount(); ++i) {
            long mask = 1 << (size - i * WORD_SIZE) - 1;
            long newWord = this.words[i] & (~other.words[i]) & mask;
            count += bitsCount(newWord);
        }
    }

    public int getFirstBitAtLeast(int pos) {
        int startWord = pos / WORD_SIZE;
        for (int i = startWord; i != getWordCount(); ++i) {
            if (words[i] == 0) {
                continue;
            }
            int startBit = (i == startWord) ? pos % WORD_SIZE : 0;
            for (int j = startBit; j != WORD_SIZE; ++j) {
                if ((words[i] & (1 << j)) != 0) {
                    return i * WORD_SIZE + j;
                }
            }
        }
        return -1;
    }

    private int getWordCount() {
        return (size + WORD_SIZE - 1) / WORD_SIZE;
    }

    private int bitsCount(long word) {
        int result = 0;
        for (int i = 0; i != WORD_SIZE; ++i) {
            result += BITS_IN_BYTE[(int) ((word >> (BYTE_SIZE * i)) & BYTE_MASK)];
        }
        return result;
    }
}
