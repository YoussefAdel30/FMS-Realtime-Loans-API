package com.company.msisdn.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DirectoryInitializer {

    private static final Logger log = LoggerFactory.getLogger(DirectoryInitializer.class);

    private final BlacklistProperties blacklist;
    private final AuditProperties audit;
    private final OracleAuditProperties oracle;
    private final CsvExportProperties csv;

    public DirectoryInitializer(
            BlacklistProperties blacklist,
            AuditProperties audit,
            OracleAuditProperties oracle,
            CsvExportProperties csv
    ) {
        this.blacklist = blacklist;
        this.audit = audit;
        this.oracle = oracle;
        this.csv = csv;
    }

    @PostConstruct
    public void createDirectories() {
        Map<String, Path> required = new LinkedHashMap<>();
        required.put("blacklist.input-directory", Path.of(blacklist.inputDirectory()));
        required.put("blacklist.processed-directory", Path.of(blacklist.processedDirectory()));
        required.put("blacklist.rejected-directory", Path.of(blacklist.rejectedDirectory()));
        required.put("blacklist.snapshot-directory", Path.of(blacklist.snapshotDirectory()));
        required.put("audit.log.directory/active", auditDir(audit.log().activeSubdir()));
        required.put("audit.log.directory/completed", auditDir(audit.log().completedSubdir()));
        required.put("audit.log.directory/archive", auditDir(audit.log().archiveSubdir()));
        required.put("audit.log.directory/quarantine", auditDir(audit.log().quarantineSubdir()));
        required.put("oracle-audit.checkpoint-directory", Path.of(oracle.checkpointDirectory()));
        required.put("csv-export.destination-directory", Path.of(csv.destinationDirectory()));
        required.put("csv-export.checkpoint-directory", Path.of(csv.checkpointDirectory()));
        String logRoot = System.getenv().getOrDefault("MSISDN_LOG_DIR", "logs");
        required.put("application-log-directory", Path.of(logRoot, "application"));

        for (Map.Entry<String, Path> entry : required.entrySet()) {
            Path path = entry.getValue().toAbsolutePath().normalize();
            try {
                Files.createDirectories(path);
                log.info("Directory ready: {} -> {}", entry.getKey(), path);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot create " + entry.getKey() + " at " + path, ex);
            }
        }
    }

    private Path auditDir(String subdir) {
        return Path.of(audit.log().directory()).resolve(subdir);
    }
}
