package com.company.msisdn.service;

import com.company.msisdn.audit.AuditEvent;
import com.company.msisdn.audit.AuditRecorder;
import com.company.msisdn.blacklist.BitmaskCodec;
import com.company.msisdn.blacklist.BlacklistSnapshot;
import com.company.msisdn.blacklist.BlacklistStore;
import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.dto.EligibilityResponse;
import com.company.msisdn.exception.AuditRejectedException;
import com.company.msisdn.exception.InvalidMsisdnException;
import com.company.msisdn.exception.NotReadyException;
import com.company.msisdn.monitoring.TrafficGate;
import com.company.msisdn.util.MsisdnMasker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EligibilityService {

    private static final Logger log = LoggerFactory.getLogger(EligibilityService.class);

    private final ApiProperties api;
    private final MsisdnValidator validator;
    private final BlacklistStore store;
    private final AuditRecorder auditRecorder;
    private final TrafficGate trafficGate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public EligibilityService(
            ApiProperties api,
            MsisdnValidator validator,
            BlacklistStore store,
            AuditRecorder auditRecorder,
            TrafficGate trafficGate,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.api = api;
        this.validator = validator;
        this.store = store;
        this.auditRecorder = auditRecorder;
        this.trafficGate = trafficGate;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public EligibilityResponse check(String requestId, String msisdn) {
        Instant requestTimestamp = clock.instant();
        long started = System.nanoTime();
        try {
            validator.validate(msisdn);
        } catch (InvalidMsisdnException ex) {
            record(requestId, requestTimestamp, started, msisdn, "INVALID", 0L, List.of(), currentVersion(), 400);
            log.info("requestId={} msisdn={} result=INVALID status=400", requestId, MsisdnMasker.mask(msisdn));
            throw ex;
        }
        trafficGate.requireAccepting();
        BlacklistSnapshot snapshot = store.current();
        long key = Long.parseLong(msisdn);
        long mask = snapshot.lookup(key);
        boolean eligible = mask == 0L;
        String result = eligible ? api.response().eligibleValue() : api.response().ineligibleValue();
        List<String> reasons = eligible ? List.of() : BitmaskCodec.activeDisplayNames(mask, snapshot.sources());
        record(requestId, requestTimestamp, started, msisdn, result, mask, reasons, snapshot.version(), 200);
        long elapsedMs = elapsedMillis(started);
        log.info("requestId={} msisdn={} result={} version={} status=200 processingMs={}",
                requestId, MsisdnMasker.mask(msisdn), result, snapshot.version(), elapsedMs);
        meterRegistry.timer("eligibility.check", "result", result).record(elapsedMs, TimeUnit.MILLISECONDS);
        return new EligibilityResponse(
                api.response().includeRequestId() ? requestId : null,
                api.response().includeMsisdn() ? msisdn : null,
                result,
                api.response().includeReasons() ? reasons : null,
                api.response().includeBlacklistVersion() ? snapshot.version() : null);
    }

    public void auditUnavailable(String requestId, String msisdn, int status, String result) {
        record(requestId, clock.instant(), System.nanoTime(), msisdn, result, 0L, List.of(), currentVersion(), status);
    }

    private void record(
            String requestId,
            Instant requestTimestamp,
            long started,
            String msisdn,
            String result,
            long mask,
            List<String> reasons,
            String version,
            int status
    ) {
        Instant responseTimestamp = clock.instant();
        AuditEvent event = new AuditEvent(
                requestId,
                requestTimestamp,
                responseTimestamp,
                msisdn == null ? "" : msisdn,
                result,
                BitmaskCodec.toUnsignedDecimal(mask),
                reasons,
                version,
                elapsedMillis(started),
                status,
                api.instanceId());
        try {
            auditRecorder.record(event);
        } catch (AuditRejectedException ex) {
            meterRegistry.counter("eligibility.audit.rejected").increment();
            throw ex;
        } catch (NotReadyException ex) {
            throw ex;
        }
    }

    private String currentVersion() {
        BlacklistSnapshot snapshot = store.current();
        return snapshot == null ? "none" : snapshot.version();
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
