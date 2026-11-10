package com.company.msisdn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EligibilityResponse(
        String requestId,
        String msisdn,
        String result,
        List<String> reasons,
        String blacklistVersion
) {
}
