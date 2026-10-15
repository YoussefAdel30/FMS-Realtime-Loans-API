package com.company.msisdn.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SegmentBatchReader {

    private SegmentBatchReader() {
    }

    public static Read read(Path segment, long offset, int maxEvents, ObjectMapper objectMapper) throws IOException {
        List<AuditEvent> events = new ArrayList<>();
        List<String> corrupt = new ArrayList<>();
        long lines = 0;
        String truncated = null;
        try (ByteLineReader reader = ByteLineReader.open(segment, offset)) {
            while (events.size() < maxEvents) {
                ByteLineReader.Line line = reader.readLine();
                if (line == null) {
                    return new Read(events, corrupt, reader.offset(), lines, true, null);
                }
                if (!line.complete()) {
                    truncated = line.text();
                    return new Read(events, corrupt, line.startOffset(), lines, true, truncated);
                }
                if (line.text().isBlank()) {
                    offset = line.endOffset();
                    continue;
                }
                try {
                    events.add(objectMapper.readValue(line.text(), AuditEvent.class));
                    lines++;
                    offset = line.endOffset();
                } catch (Exception ex) {
                    corrupt.add(line.text());
                    offset = line.endOffset();
                }
            }
            return new Read(events, corrupt, offset, lines, false, null);
        }
    }

    public record Read(
            List<AuditEvent> events,
            List<String> corruptLines,
            long nextOffset,
            long lines,
            boolean endOfSegment,
            String truncatedTail
    ) {
    }
}
