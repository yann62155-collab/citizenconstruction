package fr.citizen.construction;

import java.util.Arrays;

/** Petite table long -> int (adressage ouvert) pour stocker des millions de blocs sans objets. */
public final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] values;
    private int size;
    private int mask;

    public LongIntMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        keys = new long[cap];
        values = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
    }

    private static int hash(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k;
    }

    public int size() { return size; }

    public void put(long k, int v) {
        if (k == EMPTY) k = EMPTY + 1;
        if (size * 2 >= keys.length) grow();
        int i = hash(k) & mask;
        while (keys[i] != EMPTY) {
            if (keys[i] == k) { values[i] = v; return; }
            i = (i + 1) & mask;
        }
        keys[i] = k;
        values[i] = v;
        size++;
    }

    public int get(long k, int def) {
        if (k == EMPTY) k = EMPTY + 1;
        int i = hash(k) & mask;
        while (keys[i] != EMPTY) {
            if (keys[i] == k) return values[i];
            i = (i + 1) & mask;
        }
        return def;
    }

    public boolean contains(long k) { return get(k, Integer.MIN_VALUE) != Integer.MIN_VALUE; }

    private void grow() {
        long[] ok = keys;
        int[] ov = values;
        keys = new long[ok.length * 2];
        values = new int[ok.length * 2];
        Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
    }

    public interface Visitor { void visit(long key, int value); }

    public void forEach(Visitor v) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) v.visit(keys[i], values[i]);
    }
}
