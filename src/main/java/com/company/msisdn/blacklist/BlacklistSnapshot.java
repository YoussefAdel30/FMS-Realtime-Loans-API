package com.company.msisdn.blacklist;

import java.time.Instant;
import java.util.List;

public final class BlacklistSnapshot {

    private final CompositeBlacklistMap entries;
    private final List<SourceDefinition> sources;
    private final String version;
    private final long versionNumber;
    private final Instant loadedAt;
    private final Instant publishedAt;
    private final int recordCount;
    private final long rowsRead;
    private final String sourceFileName;
    private final String sha256;
    private final long byteSize;

    public BlacklistSnapshot(
            CompositeBlacklistMap entries,
            List<SourceDefinition> sources,
            String version,
            long versionNumber,
            Instant loadedAt,
            Instant publishedAt,
            long rowsRead,
            String sourceFileName,
            String sha256,
            long byteSize
    ) {
        this.entries = entries;
        this.sources = List.copyOf(sources);
        this.version = version;
        this.versionNumber = versionNumber;
        this.loadedAt = loadedAt;
        this.publishedAt = publishedAt;
        this.recordCount = entries.size();
        this.rowsRead = rowsRead;
        this.sourceFileName = sourceFileName;
        this.sha256 = sha256;
        this.byteSize = byteSize;
    }

    public long lookup(long msisdn, long nid) {
        return entries.get(msisdn, nid);
    }

    public boolean contains(long msisdn, long nid) {
        return entries.contains(msisdn, nid);
    }

    public List<SourceDefinition> sources() {
        return sources;
    }

    public int sourceCount() {
        return sources.size();
    }

    public String version() {
        return version;
    }

    public long versionNumber() {
        return versionNumber;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int recordCount() {
        return recordCount;
    }

    public long rowsRead() {
        return rowsRead;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public String sha256() {
        return sha256;
    }

    public long byteSize() {
        return byteSize;
    }
}
