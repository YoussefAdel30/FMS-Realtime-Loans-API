package com.company.msisdn.audit;

import java.time.Instant;
import java.util.List;

public record AuditEvent(
        String requestId,
        Instant requestTimestamp,
        Instant responseTimestamp,
        String msisdn,
        String result,
        String reasonMask,
        List<String> reasons,
        String blacklistVersion,
        long processingTimeMs,
        int httpStatus,
        String apiInstance
) {
}
