package com.company.msisdn.audit;

import com.company.msisdn.config.AuditProperties;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class SegmentPaths {

    private final AuditProperties properties;

    public SegmentPaths(AuditProperties properties) {
        this.properties = properties;
    }

    public Path active() {
        return root().resolve(properties.log().activeSubdir());
    }

    public Path completed() {
        return root().resolve(properties.log().completedSubdir());
    }

    public Path archive() {
        return root().resolve(properties.log().archiveSubdir());
    }

    public Path quarantine() {
        return root().resolve(properties.log().quarantineSubdir());
    }

    private Path root() {
        return Path.of(properties.log().directory());
    }
}
