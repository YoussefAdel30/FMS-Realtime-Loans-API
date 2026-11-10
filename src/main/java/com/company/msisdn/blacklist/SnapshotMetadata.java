package com.company.msisdn.blacklist;

import java.time.Instant;
import java.util.List;

public record SnapshotMetadata(
        String version,
        long versionNumber,
        int recordCount,
        long rowsRead,
        int sourceCount,
        List<SourceDefinition> sources,
        String sha256,
        String sourceFileName,
        String csvFileName,
        Instant loadedAt,
        Instant publishedAt,
        long byteSize
) {
}
