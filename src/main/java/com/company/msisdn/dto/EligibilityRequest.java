package com.company.msisdn.dto;

import jakarta.validation.constraints.NotBlank;

public record EligibilityRequest(@NotBlank String msisdn, @NotBlank String nid) {
}
