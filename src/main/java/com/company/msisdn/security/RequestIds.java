package com.company.msisdn.security;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIds {

    public static final String ATTRIBUTE = "msisdn.requestId";

    private RequestIds() {
    }

    public static String get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? "req-unknown" : value.toString();
    }
}
