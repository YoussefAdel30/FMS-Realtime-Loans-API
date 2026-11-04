package com.company.msisdn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EligibilityResponse(
        String requestId,
        String msisdn,
        String nid,
        String result,
        String reason,
        String blacklistVersion
) {
}
