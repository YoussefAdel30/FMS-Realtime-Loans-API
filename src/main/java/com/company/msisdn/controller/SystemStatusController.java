package com.company.msisdn.controller;

import com.company.msisdn.dto.SystemStatusResponse;
import com.company.msisdn.monitoring.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemStatusController {

    private final SystemStatusService systemStatusService;

    public SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("${api.status-path}")
    public SystemStatusResponse status() {
        return systemStatusService.current();
    }
}
