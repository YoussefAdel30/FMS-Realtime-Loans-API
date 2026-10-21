package com.company.msisdn.audit;

import java.time.Instant;

public record ProcessingCheckpoint(
        String segment,
        long offset,
        long lines,
        boolean complete,
        Instant updatedAt,
        String partFile,
        long partBytes,
        long publishedOffset
) {

    public static ProcessingCheckpoint empty(String segment, Instant now) {
        return new ProcessingCheckpoint(segment, 0L, 0L, false, now, null, 0L, 0L);
    }

    public ProcessingCheckpoint advance(long offset, long lines, Instant now) {
        return new ProcessingCheckpoint(segment, offset, lines, complete, now, partFile, partBytes, publishedOffset);
    }

    public ProcessingCheckpoint completed(long offset, long lines, Instant now) {
        return new ProcessingCheckpoint(segment, offset, lines, true, now, null, 0L, offset);
    }
}
