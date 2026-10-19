package com.company.msisdn.oracle;

import com.company.msisdn.audit.AuditEvent;
import com.company.msisdn.audit.SegmentPaths;
import com.company.msisdn.blacklist.BitmaskCodec;
import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.util.AtomicFiles;
import com.company.msisdn.util.RetryBackoff;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(prefix = "oracle-audit", name = "enabled", havingValue = "true")
public class OracleAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(OracleAuditRepository.class);
    private static final int REASONS_LIMIT = 3900;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OracleAuditSql sql;
    private final OracleAuditProperties properties;
    private final OracleIngestionStats stats;
    private final SegmentPaths segmentPaths;

    public OracleAuditRepository(
            JdbcTemplate oracleJdbcTemplate,
            DataSourceTransactionManager oracleTransactionManager,
            OracleAuditSql sql,
            OracleAuditProperties properties,
            OracleIngestionStats stats,
            SegmentPaths segmentPaths
    ) {
        this.jdbcTemplate = oracleJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(oracleTransactionManager);
        this.sql = sql;
        this.properties = properties;
        this.stats = stats;
        this.segmentPaths = segmentPaths;
    }

    public String insertSql() {
        return sql.insertSql();
    }

    public void write(List<AuditEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        DataAccessException last = null;
        for (int attempt = 1; attempt <= properties.retry().maxAttempts(); attempt++) {
            try {
                writeOnce(events);
                stats.recordInserted(events.size());
                return;
            } catch (DataAccessException ex) {
                last = ex;
                stats.recordFailure(trim(ex.getMessage()));
                log.warn("Oracle audit batch failed on attempt {} of {}: {}",
                        attempt, properties.retry().maxAttempts(), trim(ex.getMessage()));
                if (attempt == properties.retry().maxAttempts()) {
                    break;
                }
                sleep(RetryBackoff.delayMs(
                        attempt,
                        properties.retry().initialBackoffMs(),
                        properties.retry().maxBackoffMs(),
                        properties.retry().multiplier()));
            }
        }
        throw last;
    }

    public void probe() {
        jdbcTemplate.queryForObject("SELECT 1 FROM DUAL", Integer.class);
        stats.recordInserted(0);
    }

    private void writeOnce(List<AuditEvent> events) {
        try {
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(sql.insertSql(), setter(events)));
        } catch (DataAccessException batchFailure) {
            log.warn("Retrying Oracle audit batch row by row after {}", batchFailure.getClass().getSimpleName());
            transactionTemplate.executeWithoutResult(status -> {
                for (AuditEvent event : events) {
                    try {
                        jdbcTemplate.update(sql.insertSql(), ps -> bind(ps, event));
                    } catch (DuplicateKeyException ignored) {
                        log.info("Skipping duplicate audit requestId={}", event.requestId());
                    } catch (DataIntegrityViolationException ex) {
                        quarantine(event, ex);
                    }
                }
            });
        }
    }

    private BatchPreparedStatementSetter setter(List<AuditEvent> events) {
        return new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bind(ps, events.get(i));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        };
    }

    private void bind(PreparedStatement ps, AuditEvent event) throws SQLException {
        ps.setString(1, limit(event.requestId(), 64));
        ps.setTimestamp(2, Timestamp.from(event.requestTimestamp()));
        ps.setTimestamp(3, Timestamp.from(event.responseTimestamp()));
        ps.setString(4, limit(event.msisdn(), 32));
        ps.setString(5, limit(event.nid(), 32));
        ps.setString(6, limit(event.result(), 16));
        ps.setString(7, BitmaskCodec.toHex(Long.parseUnsignedLong(event.reasonMask())));
        ps.setString(8, limit(event.reason(), REASONS_LIMIT));
        ps.setString(9, limit(event.blacklistVersion(), 64));
        ps.setLong(10, Math.max(0L, event.processingTimeMs()));
        ps.setInt(11, event.httpStatus());
        ps.setString(12, limit(event.apiInstance(), 128));
        ps.setString(13, limit(event.requestId(), 64));
    }

    private void quarantine(AuditEvent event, Exception ex) {
        try {
            Path file = AtomicFiles.unique(segmentPaths.quarantine(), event.requestId() + ".oracle-rejected.json");
            String body = event.requestId() + " " + trim(ex.getMessage());
            Files.writeString(file, body, StandardCharsets.UTF_8);
            log.error("Quarantined audit requestId={} because Oracle rejected the row", event.requestId());
        } catch (Exception io) {
            log.error("Unable to quarantine rejected audit requestId={}", event.requestId(), io);
        }
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String trim(String message) {
        if (message == null) {
            return "";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Oracle retry interrupted", ex);
        }
    }
}
