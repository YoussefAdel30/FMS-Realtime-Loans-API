package com.company.msisdn.dto;

import java.time.Instant;

public record SystemStatusResponse(
        String applicationVersion,
        String instanceId,
        long uptimeSeconds,
        boolean ready,
        String readinessReason,
        BlacklistStatus blacklist,
        QueueStatus auditQueue,
        ConsumerStatus oracle,
        ConsumerStatus csvExport
) {

    public record BlacklistStatus(
            String version,
            Integer recordCount,
            Integer sourceCount,
            Instant loadedAt,
            Instant publishedAt,
            Long ageSeconds,
            String freshness
    ) {
    }

    public record QueueStatus(
            int size,
            int capacity,
            double utilization
    ) {
    }

    public record ConsumerStatus(
            boolean enabled,
            String status,
            long backlogSegments,
            long backlogBytes,
            long processedTotal,
            long failureTotal,
            Instant lastSuccessAt,
            String lastError
    ) {
    }
}
