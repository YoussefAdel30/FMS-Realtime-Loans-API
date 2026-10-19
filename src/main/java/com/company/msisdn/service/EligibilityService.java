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
import com.company.msisdn.exception.InvalidNationalIdException;
import com.company.msisdn.exception.NotReadyException;
import com.company.msisdn.monitoring.TrafficGate;
import com.company.msisdn.util.MsisdnMasker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EligibilityService {

    private static final Logger log = LoggerFactory.getLogger(EligibilityService.class);

    private final ApiProperties api;
    private final MsisdnValidator validator;
    private final NationalIdValidator nationalIdValidator;
    private final BlacklistStore store;
    private final AuditRecorder auditRecorder;
    private final TrafficGate trafficGate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public EligibilityService(
            ApiProperties api,
            MsisdnValidator validator,
            NationalIdValidator nationalIdValidator,
            BlacklistStore store,
            AuditRecorder auditRecorder,
            TrafficGate trafficGate,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.api = api;
        this.validator = validator;
        this.nationalIdValidator = nationalIdValidator;
        this.store = store;
        this.auditRecorder = auditRecorder;
        this.trafficGate = trafficGate;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public EligibilityResponse check(String requestId, String msisdn, String nid) {
        Instant requestTimestamp = clock.instant();
        long started = System.nanoTime();
        String safeMsisdn = msisdn == null ? "" : msisdn;
        String safeNid = nid == null ? "" : nid;
        try {
            validator.validate(msisdn);
        } catch (InvalidMsisdnException ex) {
            record(requestId, requestTimestamp, started, safeMsisdn, safeNid, "INVALID", 0L, "", currentVersion(), 400);
            log.info("requestId={} msisdn={} nid={} result=INVALID status=400",
                    requestId, MsisdnMasker.mask(safeMsisdn), MsisdnMasker.mask(safeNid));
            throw ex;
        }
        try {
            nationalIdValidator.validate(nid);
        } catch (InvalidNationalIdException ex) {
            record(requestId, requestTimestamp, started, safeMsisdn, safeNid, "INVALID", 0L, "", currentVersion(), 400);
            log.info("requestId={} msisdn={} nid={} result=INVALID status=400",
                    requestId, MsisdnMasker.mask(safeMsisdn), MsisdnMasker.mask(safeNid));
            throw ex;
        }
        trafficGate.requireAccepting();
        BlacklistSnapshot snapshot = store.current();
        long mask = snapshot.lookup(Long.parseLong(msisdn), Long.parseLong(nid));
        boolean eligible = mask == 0L;
        String result = eligible ? api.response().eligibleValue() : api.response().ineligibleValue();
        String reason = eligible ? "" : BitmaskCodec.formatKoReason(mask, snapshot.sources());
        record(requestId, requestTimestamp, started, msisdn, nid, result, mask, reason, snapshot.version(), 200);
        long elapsedMs = elapsedMillis(started);
        log.info("requestId={} msisdn={} nid={} result={} version={} status=200 processingMs={}",
                requestId, MsisdnMasker.mask(msisdn), MsisdnMasker.mask(nid), result, snapshot.version(), elapsedMs);
        meterRegistry.timer("eligibility.check", "result", result).record(elapsedMs, TimeUnit.MILLISECONDS);
        return new EligibilityResponse(
                api.response().includeRequestId() ? requestId : null,
                api.response().includeMsisdn() ? msisdn : null,
                api.response().includeNid() ? nid : null,
                result,
                api.response().includeReasons() ? reason : null,
                api.response().includeBlacklistVersion() ? snapshot.version() : null);
    }

    public void auditUnavailable(String requestId, String msisdn, String nid, int status, String result) {
        record(requestId, clock.instant(), System.nanoTime(), msisdn, nid, result, 0L, "", currentVersion(), status);
    }

    private void record(
            String requestId,
            Instant requestTimestamp,
            long started,
            String msisdn,
            String nid,
            String result,
            long mask,
            String reason,
            String version,
            int status
    ) {
        Instant responseTimestamp = clock.instant();
        AuditEvent event = new AuditEvent(
                requestId,
                requestTimestamp,
                responseTimestamp,
                msisdn == null ? "" : msisdn,
                nid == null ? "" : nid,
                result,
                BitmaskCodec.toUnsignedDecimal(mask),
                reason == null ? "" : reason,
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
