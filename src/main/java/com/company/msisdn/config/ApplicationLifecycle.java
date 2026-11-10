package com.company.msisdn.config;

import com.company.msisdn.audit.AuditWorkers;
import com.company.msisdn.audit.SegmentArchiveCoordinator;
import com.company.msisdn.audit.SegmentRecoveryService;
import com.company.msisdn.blacklist.BlacklistReloadService;
import com.company.msisdn.export.CsvExportService;
import com.company.msisdn.oracle.OracleIngestionService;
import com.company.msisdn.util.Redaction;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycle.class);

    private final SegmentRecoveryService recoveryService;
    private final AuditWorkers auditWorkers;
    private final ObjectProvider<OracleIngestionService> oracleIngestion;
    private final CsvExportService csvExportService;
    private final BlacklistReloadService blacklistReloadService;
    private final SegmentArchiveCoordinator archiveCoordinator;
    private final ApiProperties api;
    private final BlacklistProperties blacklist;
    private final OracleAuditProperties oracle;
    private final SecurityProperties security;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "msisdn-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ApplicationLifecycle(
            SegmentRecoveryService recoveryService,
            AuditWorkers auditWorkers,
            ObjectProvider<OracleIngestionService> oracleIngestion,
            CsvExportService csvExportService,
            BlacklistReloadService blacklistReloadService,
            SegmentArchiveCoordinator archiveCoordinator,
            ApiProperties api,
            BlacklistProperties blacklist,
            OracleAuditProperties oracle,
            SecurityProperties security
    ) {
        this.recoveryService = recoveryService;
        this.auditWorkers = auditWorkers;
        this.oracleIngestion = oracleIngestion;
        this.csvExportService = csvExportService;
        this.blacklistReloadService = blacklistReloadService;
        this.archiveCoordinator = archiveCoordinator;
        this.api = api;
        this.blacklist = blacklist;
        this.oracle = oracle;
        this.security = security;
    }

    @Override
    public void start() {
        log.info("Starting MSISDN eligibility API instance={} security={} oracleSchema={} oracleUser={} jdbc={}",
                api.instanceId(),
                security.enabled(),
                oracle.schema(),
                oracle.username(),
                Redaction.jdbcUrl(oracle.jdbcUrl()));
        recoveryService.recover();
        auditWorkers.start();
        OracleIngestionService oracleService = oracleIngestion.getIfAvailable();
        if (oracleService != null) {
            oracleService.start();
        }
        csvExportService.start();
        blacklistReloadService.loadInitial();
        long interval = Math.max(1000L, blacklist.reloadInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::background, interval, interval, TimeUnit.MILLISECONDS);
        running.set(true);
        log.info("MSISDN eligibility API lifecycle is up");
    }

    private void background() {
        try {
            blacklistReloadService.scanOnce();
            archiveCoordinator.sweep();
        } catch (Exception ex) {
            log.error("Background maintenance failed", ex);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping MSISDN eligibility API");
        scheduler.shutdownNow();
        auditWorkers.stop(Duration.ofSeconds(20));
        OracleIngestionService oracleService = oracleIngestion.getIfAvailable();
        if (oracleService != null) {
            oracleService.processAvailable();
            oracleService.stop();
        }
        csvExportService.stop();
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE / 2;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

}
