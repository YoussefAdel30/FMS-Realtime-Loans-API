package com.company.msisdn.audit;

import java.time.Instant;

public record AuditEvent(
        String requestId,
        Instant requestTimestamp,
        Instant responseTimestamp,
        String msisdn,
        String nid,
        String result,
        String reasonMask,
        String reason,
        String blacklistVersion,
        long processingTimeMs,
        int httpStatus,
        String apiInstance
) {
}
