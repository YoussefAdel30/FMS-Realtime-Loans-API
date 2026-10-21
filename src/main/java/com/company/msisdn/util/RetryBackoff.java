package com.company.msisdn.util;

public final class RetryBackoff {

    private RetryBackoff() {
    }

    public static long delayMs(int attempt, long initialMs, long maxMs, double multiplier) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        double delay = initialMs * Math.pow(multiplier, attempt - 1.0d);
        if (delay > maxMs || delay < 0) {
            return maxMs;
        }
        return (long) delay;
    }
}
