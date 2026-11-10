package com.company.msisdn.controller;

import com.company.msisdn.dto.EligibilityRequest;
import com.company.msisdn.dto.EligibilityResponse;
import com.company.msisdn.security.RequestIds;
import com.company.msisdn.service.EligibilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EligibilityController {

    private final EligibilityService eligibilityService;

    public EligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    @PostMapping("${api.eligibility-path}")
    public EligibilityResponse check(@Valid @RequestBody EligibilityRequest request, HttpServletRequest httpRequest) {
        return eligibilityService.check(RequestIds.get(httpRequest), request.msisdn());
    }
}
