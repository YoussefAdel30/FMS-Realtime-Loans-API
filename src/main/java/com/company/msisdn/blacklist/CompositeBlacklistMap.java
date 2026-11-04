package com.company.msisdn.blacklist;

/**
 * Open-addressed map from an MSISDN and a national ID to a source bitmask.
 * Keys and values are primitive longs. A published map is not mutated.
 */
public final class CompositeBlacklistMap {

    private long[] msisdnKeys;
    private long[] nidKeys;
    private long[] values;
    private int size;
    private final float loadFactor;
    private int threshold;

    public CompositeBlacklistMap(int expected, float loadFactor) {
        if (expected < 1) {
            throw new IllegalArgumentException("expected capacity must be positive");
        }
        if (loadFactor <= 0f || loadFactor >= 1f) {
            throw new IllegalArgumentException("load factor must be between 0 and 1");
        }
        int capacity = nextPowerOfTwo(Math.max(16, (int) Math.ceil(expected / loadFactor)));
        this.msisdnKeys = new long[capacity];
        this.nidKeys = new long[capacity];
        this.values = new long[capacity];
        this.loadFactor = loadFactor;
        this.threshold = (int) (capacity * loadFactor);
    }

    public int size() {
        return size;
    }

    public long get(long msisdn, long nid) {
        int index = find(msisdn, nid);
        if (index < 0) {
            return 0L;
        }
        return values[index];
    }

    public boolean contains(long msisdn, long nid) {
        return find(msisdn, nid) >= 0;
    }

    public void put(long msisdn, long nid, long mask) {
        if (msisdn == 0L || nid == 0L) {
            throw new IllegalArgumentException("msisdn and nid must be non-zero");
        }
        if (size >= threshold) {
            rehash(msisdnKeys.length << 1);
        }
        insert(msisdn, nid, mask);
    }

    public void trim() {
        int needed = nextPowerOfTwo(Math.max(16, (int) Math.ceil(Math.max(size, 1) / loadFactor)));
        if (needed < msisdnKeys.length) {
            rehash(needed);
        }
    }

    private int find(long msisdn, long nid) {
        if (msisdn == 0L || nid == 0L) {
            return -1;
        }
        int index = indexOf(msisdn, nid, false);
        return index < 0 || msisdnKeys[index] == 0L ? -1 : index;
    }

    private int indexOf(long msisdn, long nid, boolean forInsert) {
        int mask = msisdnKeys.length - 1;
        int index = (int) mix(msisdn, nid) & mask;
        while (true) {
            long slotMsisdn = msisdnKeys[index];
            if (slotMsisdn == 0L) {
                return forInsert ? index : -1;
            }
            if (slotMsisdn == msisdn && nidKeys[index] == nid) {
                return index;
            }
            index = (index + 1) & mask;
        }
    }

    private void rehash(int capacity) {
        long[] oldMsisdn = msisdnKeys;
        long[] oldNid = nidKeys;
        long[] oldValues = values;
        msisdnKeys = new long[capacity];
        nidKeys = new long[capacity];
        values = new long[capacity];
        threshold = (int) (capacity * loadFactor);
        size = 0;
        for (int i = 0; i < oldMsisdn.length; i++) {
            if (oldMsisdn[i] != 0L) {
                insert(oldMsisdn[i], oldNid[i], oldValues[i]);
            }
        }
    }

    private void insert(long msisdn, long nid, long mask) {
        int index = indexOf(msisdn, nid, true);
        if (msisdnKeys[index] == 0L) {
            msisdnKeys[index] = msisdn;
            nidKeys[index] = nid;
            size++;
        }
        values[index] = mask;
    }

    static long mix(long msisdn, long nid) {
        long x = msisdn ^ Long.rotateLeft(nid, 21);
        x = (x ^ (x >>> 33)) * 0xFF51AFD7ED558CCDL;
        x = (x ^ (x >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return x ^ (x >>> 33);
    }

    private static int nextPowerOfTwo(int value) {
        int power = 16;
        while (power < value) {
            power <<= 1;
        }
        return power;
    }
}
