package com.company.msisdn.util;

public final class Redaction {

    private Redaction() {
    }

    public static String jdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.replaceAll("(?i)(password=)[^;&]*", "$1***");
    }

    public static String secretPresence(String secret) {
        return secret == null || secret.isBlank() ? "[empty]" : "[set]";
    }
}
