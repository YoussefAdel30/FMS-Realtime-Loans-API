package com.company.msisdn.blacklist;

import java.util.ArrayList;
import java.util.List;

public final class BitmaskCodec {

    public static final int MAX_SOURCES = 64;

    private BitmaskCodec() {
    }

    public static long setBit(long mask, int index) {
        checkIndex(index);
        return mask | (1L << index);
    }

    public static boolean isSet(long mask, int index) {
        checkIndex(index);
        return (mask & (1L << index)) != 0L;
    }

    public static String toUnsignedDecimal(long mask) {
        return Long.toUnsignedString(mask);
    }

    public static String toHex(long mask) {
        return String.format("%016X", mask);
    }

    public static long fromHex(String hex) {
        if (hex == null || !hex.matches("[0-9A-Fa-f]{1,16}")) {
            throw new IllegalArgumentException("reason mask hex is invalid");
        }
        return Long.parseUnsignedLong(hex, 16);
    }

    public static List<String> activeDisplayNames(long mask, List<SourceDefinition> sources) {
        List<String> names = new ArrayList<>();
        int limit = Math.min(sources.size(), MAX_SOURCES);
        for (int index = 0; index < limit; index++) {
            if (isSet(mask, index)) {
                names.add(sources.get(index).displayName());
            }
        }
        return List.copyOf(names);
    }

    public static String formatKoReason(long mask, List<SourceDefinition> sources) {
        return "Anti-fraud : " + String.join(", ", activeDisplayNames(mask, sources));
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= MAX_SOURCES) {
            throw new IllegalArgumentException("source bit index must be between 0 and 63");
        }
    }
}
