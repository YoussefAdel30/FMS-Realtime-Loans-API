package com.company.msisdn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String requestId,
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
