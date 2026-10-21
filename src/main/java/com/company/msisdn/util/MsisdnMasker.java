package com.company.msisdn.util;

public final class MsisdnMasker {

    private MsisdnMasker() {
    }

    public static String mask(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return "***";
        }
        if (msisdn.length() < 6) {
            return "***";
        }
        int stars = msisdn.length() - 6;
        return msisdn.substring(0, 4) + "*".repeat(stars) + msisdn.substring(msisdn.length() - 2);
    }
}
